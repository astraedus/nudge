#!/usr/bin/env bash
# emulator-smoke.sh — prove, on a stock CI emulator, that Nudge's blocking works end to end.
#
# WHY THIS EXISTS: every other check on this repo is either a JVM test (no Android runtime, so it
# cannot see the accessibility service) or `scripts/device-qa.sh` (one physical Pixel 3, API 31,
# one agent at a time). Neither can say "does the service bind and block on Android 8 / 12 / 15?".
# This script is the narrow, parallelisable answer, run by `.github/workflows/emulator-smoke.yml`
# inside android-emulator-runner. It is NOT a replacement for device-qa.sh and must never grow
# into one; the physical-only cases (OEM overlays, Recents/Home gestures) stay on the bench.
#
# NEVER run this on the laptop: it drives whatever `adb` device is attached. CI only.
#
# Usage: scripts/emulator-smoke.sh <debug-apk>
# Env:   SMOKE_OUT   artifact dir (default emulator-smoke-out)
#        EMU_T0      epoch seconds taken just before the emulator step started (boot-time metric)
#
# Oracle (same philosophy as device-qa.sh, see docs/TESTING.md L6):
#   · `dumpsys accessibility`  -> the service is BOUND, not merely enabled in settings
#   · logcat                   -> BlockEngine's own decision line, i.e. WHICH rule fired
#   · `dumpsys activity`       -> the foreground activity really is BlockOverlayActivity
#
# Fixture honesty (CLAUDE.md must (b)): the applicationId comes from the APK (aapt2) and the
# accessibility component from the installed package's own service table. Nothing is retyped.

set -uo pipefail

APK="${1:?usage: emulator-smoke.sh <debug-apk>}"
OUT="${SMOKE_OUT:-emulator-smoke-out}"
mkdir -p "$OUT"
LOGCAT="${OUT}/logcat.txt"
FIXTURE_DEVICE_PATH="/sdcard/Download/nudge-smoke-rules.json"
T_START="$(date +%s)"

log() { printf '[smoke %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# Always leave evidence: a screenshot + UI tree + logcat tail, so a red run is diagnosable from
# the artifacts alone (a blind rerun is exactly what this lane exists to avoid).
evidence() { # name
  adb exec-out screencap -p >"${OUT}/$1.png" 2>/dev/null || true
  adb shell uiautomator dump /sdcard/smoke-ui.xml >/dev/null 2>&1 &&
    adb shell cat /sdcard/smoke-ui.xml >"${OUT}/$1.xml" 2>/dev/null || true
}

fail() {
  log "FAIL: $*"
  evidence "fail"
  echo "RESULT=fail" >"${OUT}/result.env"
  echo "REASON=$*" >>"${OUT}/result.env"
  adb shell dumpsys accessibility >"${OUT}/dumpsys-accessibility.txt" 2>&1 || true
  adb shell dumpsys activity activities >"${OUT}/dumpsys-activities.txt" 2>&1 || true
  exit 1
}

# ── device ready ──────────────────────────────────────────────────────────────
adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; do sleep 2; done
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
BOOT_S=$(( T_START - ${EMU_T0:-$T_START} ))
log "device ready: API ${SDK}, ${BOOT_S}s from emulator step start (includes image download + AVD create)"

adb logcat -c
adb logcat -v time >"$LOGCAT" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

# ── 1. install ────────────────────────────────────────────────────────────────
AAPT2="$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
[[ -x "$AAPT2" ]] || fail "aapt2 not found under the SDK build-tools"
APP_ID="$("$AAPT2" dump packagename "$APK" | tr -d '\r')"
[[ -n "$APP_ID" ]] || fail "could not read the applicationId from ${APK}"
adb install -r -t "$APK" >"${OUT}/install.txt" 2>&1 || { cat "${OUT}/install.txt"; fail "adb install failed"; }
log "installed ${APP_ID}"

# The accessibility component, as the installed package registers it (full class name, no
# manifest-relative '.service.X' guesswork). The service resolver table lists it by its intent
# action on every API level from 26 up.
A11Y_COMPONENT="$(adb shell dumpsys package "$APP_ID" | tr -d '\r' |
  grep -A3 'android.accessibilityservice.AccessibilityService:' |
  grep -o "${APP_ID}/[A-Za-z0-9_.]*" | head -1)"
[[ -n "$A11Y_COMPONENT" ]] || fail "no accessibility service registered by ${APP_ID}"
A11Y_CLASS="${A11Y_COMPONENT#*/}"
log "accessibility component: ${A11Y_COMPONENT}"

# ── 2. grants (the set device-qa.sh apply_grants applies, minus the a11y part, done later) ──
apply_permissions() {
  adb shell appops set "$APP_ID" SYSTEM_ALERT_WINDOW allow
  adb shell appops set "$APP_ID" GET_USAGE_STATS allow
  adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
}
apply_permissions

# ── 3. pick the app to block ──────────────────────────────────────────────────
# Must be launchable on THIS image and NOT on Nudge's essentials list (dialer, SMS, clock,
# settings-class apps are never blockable). Calculator / Contacts differ per API level and per
# google_apis build, so resolve at runtime and record what was used.
TARGET=""
for cand in com.google.android.calculator com.android.calculator2 com.google.android.contacts com.android.contacts; do
  if adb shell pm list packages "$cand" | tr -d '\r' | grep -qx "package:${cand}" &&
     adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$cand" |
       tr -d '\r' | grep -q '/'; then
    TARGET="$cand"; break
  fi
done
adb shell pm list packages | tr -d '\r' | sort >"${OUT}/installed-packages.txt"
[[ -n "$TARGET" ]] || fail "no Calculator/Contacts app on this image; see installed-packages.txt"
log "block target: ${TARGET}"

# ── UI driving helpers (bare uiautomator dump; measured safe next to a bound a11y service) ──
cat >"${OUT}/locate.py" <<'PYEOF'
"""Print "<cx> <cy>" for the first node whose text/content-desc EXACTLY equals the needle."""
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for attrs in re.findall(r"<node([^>]*)>", xml):
    def attr(n):
        m = re.search(r'\b%s="([^"]*)"' % n, attrs)
        return m.group(1) if m else ""
    if sys.argv[2] not in (attr("text"), attr("content-desc")):
        continue
    b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attr("bounds") or "")
    if b:
        x1, y1, x2, y2 = (int(g) for g in b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PYEOF

UI="${OUT}/ui.xml"
# Swipe geometry from the real screen size: the emulator skin is 320x640, so the bench's
# hardcoded 1080-wide coordinates would swipe off-screen and silently scroll nothing.
read -r SCREEN_W SCREEN_H < <(adb shell wm size | tr -d '\r' | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
SWIPE="$((SCREEN_W / 2)) $((SCREEN_H * 3 / 4)) $((SCREEN_W / 2)) $((SCREEN_H / 4)) 300"
snapshot() { adb shell uiautomator dump /sdcard/smoke-ui.xml >/dev/null 2>&1; adb shell cat /sdcard/smoke-ui.xml 2>/dev/null | tr -d '\r' >"$UI"; }
has_text() { grep -qF "\"$1\"" "$UI" 2>/dev/null; }
wait_text() { local i; for ((i = 0; i < "${2:-20}"; i++)); do snapshot; has_text "$1" && return 0; sleep 1; done; return 1; }
tap_text() { # text [timeout] [scroll=0|1] — locate+tap on ONE snapshot (the target can vanish between two)
  local xy i
  for ((i = 0; i < "${2:-20}"; i++)); do
    snapshot
    xy="$(python3 "${OUT}/locate.py" "$UI" "$1")"
    if [[ -n "$xy" ]]; then adb shell input tap $xy >/dev/null 2>&1; sleep 1; return 0; fi
    [[ "${3:-0}" == 1 ]] && adb shell input swipe $SWIPE >/dev/null 2>&1
    sleep 1
  done
  fail "cannot tap '$1': not on screen within ${2:-20}s"
}

# ── 4. seed the Hard-block rule through the real import path ───────────────────
# No control channel exists yet (a sibling lane is building the qa receiver; switch to it then).
# Until then: the same onboarding + Settings > Import backup walk as .maestro/nudge-setup.yaml,
# driven with uiautomator instead of Maestro. Done BEFORE the a11y service is enabled so nothing
# is blocking while the UI is driven.
python3 - "$TARGET" >"${OUT}/rules.json" <<'PYEOF'
import json, sys
rule = {"packageName": sys.argv[1], "groupName": None, "mode": "HARD_BLOCK", "delaySeconds": 15,
        "dailyLimitMinutes": None, "enabled": True, "scheduleDays": None, "scheduleStartMinute": None,
        "scheduleEndMinute": None, "inAppFeatures": None, "grayscale": False, "showCounter": False,
        "autoKickAfter": None, "showTimeRemaining": False, "autoKickCooldownSeconds": 60,
        "webDomains": None, "autoKickAfterMinutes": None, "webBlockMode": None, "tabVanish": False,
        "followingSteer": False}
print(json.dumps({"version": 1, "exportedAt": 1759000000000, "rules": [rule], "groups": [], "history": []}))
PYEOF
adb push "${OUT}/rules.json" "$FIXTURE_DEVICE_PATH" >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://${FIXTURE_DEVICE_PATH}" >/dev/null 2>&1

adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_text "Mindful app usage through gentle friction" 45 || fail "onboarding welcome never appeared"
tap_text "Next"
wait_text "Permissions" 15 || fail "onboarding permissions page never appeared"
tap_text "Next"
wait_text "You're all set" 15 || fail "onboarding ready page never appeared"
tap_text "Get Started"
wait_text "Screen Time" 20 || fail "dashboard never appeared after onboarding"
tap_text "Settings" 20 1
tap_text "Import backup" 20 1
# API 26's DocumentsUI "Recent" is empty for a freshly pushed file (the media scan does not index
# JSON there), so fall back to browsing the Downloads root, which lists it on every level.
if ! wait_text "nudge-smoke-rules.json" 8; then
  tap_text "Show roots" 10
  tap_text "Downloads" 10
fi
wait_text "nudge-smoke-rules.json" 30 || { evidence picker; fail "document picker does not list the fixture"; }
tap_text "nudge-smoke-rules.json"
wait_text "Import" 20 || fail "import preview never appeared"
tap_text "Import"
wait_text "Import Complete" 25 || fail "import did not complete"
tap_text "OK"
log "rule seeded: HARD_BLOCK ${TARGET}"

# ── 5. enable the accessibility service and prove it BOUND ────────────────────
# Ordering is load-bearing (device-qa.sh ensure_grants): AccessibilityManagerService prunes a
# setting written before the app's first launch, so the grant goes in AFTER onboarding, and is
# re-checked a few seconds later instead of trusting the first success.
# API 26's dumpsys has no 'Bound services' section (it lists a plain `services:`, which also holds
# enabled-but-unbound services), so there the app's own "accessibility service connected" log line,
# newer than this attempt's grant, is the proof of binding.
a11y_bound() {
  if (( SDK >= 28 )); then
    adb shell dumpsys accessibility | tr -d '\r' | grep -A3 'Bound services' | grep -q "$A11Y_CLASS"
  else
    tail -n +"$MARK" "$LOGCAT" | grep -qF "accessibility service connected"
  fi
}
bound=0
for attempt in 1 2 3; do
  apply_permissions
  MARK=$(( $(wc -l <"$LOGCAT") + 1 ))
  adb shell settings put secure enabled_accessibility_services "$A11Y_COMPONENT"
  adb shell settings put secure accessibility_enabled 1
  for ((i = 0; i < 60; i++)); do a11y_bound && break; sleep 1; done
  a11y_bound || { log "attempt ${attempt}: not bound after 60s"; continue; }
  sleep 4
  a11y_bound && { bound=1; break; }
done
(( bound == 1 )) || fail "accessibility service never reached 'Bound services' in dumpsys accessibility"
log "accessibility service bound"

# ── 6. launch the blocked app; assert decision + foreground ───────────────────
adb shell input keyevent KEYCODE_HOME; sleep 2
# Restart the capture instead of clearing under it: on API 26 `logcat -c` ends a running
# `logcat` stream, so the old writer died and the file stayed empty (the block was happening,
# the assertion just could not see it). A fresh stream after the clear works on every level.
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true
adb logcat -c
adb logcat -v time >"$LOGCAT" 2>&1 &
LOGCAT_PID=$!
adb shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

want="block package=${TARGET} reason=unconditional_hard_block"
decided=0
for ((i = 0; i < 30; i++)); do grep -qF "$want" "$LOGCAT" && { decided=1; break; }; sleep 1; done
(( decided == 1 )) || { grep -F "evaluate package=${TARGET}" "$LOGCAT" | tail -3; fail "no '${want}' in logcat"; }
log "BlockEngine decided: ${want}"

fg=""
for ((i = 0; i < 15; i++)); do
  fg="$(adb shell dumpsys activity activities | tr -d '\r' |
    sed -n 's/.*ResumedActivity.* u0 \([^ ]*\)\/\([^ }]*\).*/\2/p' | head -1)"
  [[ "$fg" == *BlockOverlayActivity ]] && break
  sleep 1
done
[[ "$fg" == *BlockOverlayActivity ]] || fail "foreground activity is '${fg}', not BlockOverlayActivity"
evidence "blocked"
log "foreground: ${fg}"

TOTAL_S=$(( $(date +%s) - T_START ))
{
  echo "RESULT=pass"
  echo "API=${SDK}"
  echo "TARGET=${TARGET}"
  echo "BOOT_S=${BOOT_S}"
  echo "SMOKE_S=${TOTAL_S}"
} >"${OUT}/result.env"
log "PASS (API ${SDK}, target ${TARGET}, smoke ${TOTAL_S}s)"
