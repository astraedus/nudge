#!/usr/bin/env bash
# device-qa.sh — Nudge's SCRIPTED release-gate device QA.
#
# Replaces the 60–100 minute LLM tap-walk that gated every release with a deterministic
# Maestro + ADB run that finishes in minutes and exits nonzero on any failure.
#
#   scripts/device-qa.sh            # every case (same as `all`)
#   scripts/device-qa.sh all
#   scripts/device-qa.sh delay-block
#   scripts/device-qa.sh list
#
# ── Which APK is under test (APK=) ────────────────────────────────────────────
#   APK=main       (default) download `nudge-main.apk` from the rolling `main-latest`
#                  GitHub prerelease and install it. This is what CI built from main.
#   APK=release    download the APK asset of the newest `v*` GitHub release.
#   APK=installed  install nothing; drive whatever build is already on the device.
#                  This is the RELEASE-GATE mode: it validates the exact artefact a
#                  human/CI put on the bench.
#   APK=debug      build + install `assembleDebug` locally. Debug is signed with debug
#                  keys, so this UNINSTALLS the release build first (data is wiped by
#                  `setup` anyway). The ONLY mode in which the `refusal-alert` case can
#                  run: its trigger receiver lives in `app/src/debug/` and does not
#                  exist in a release APK at all (see
#                  docs/architecture/service-lifecycle-and-watchdog.md).
#   APK=/path/to/app.apk  install that file.
#
# ── Other knobs ───────────────────────────────────────────────────────────────
#   ADB_SERIAL=192.168.1.68:5555   bench Pixel 3 (auto-reconnects)
#   NOTIF_IDLE_SECS=300            idle window for the notif-idle case (#63)
#   HOME_REOPEN_TRIALS=5           repeats inside the home-reopen case (#58)
#   EXPECT_VERSION_CODE=<n>        default: parsed from app/build.gradle.kts
#   SKIP_VERSION_CHECK=1           accept whatever versionCode is installed
#   KEEP_SCREENSHOTS_IN_REPO=1     don't move Maestro's PNGs out of the repo root
#
# ── What it owns and restores (trap, on every exit path) ──────────────────────
#   screen_off_timeout · svc power stayon · enabled_accessibility_services ·
#   accessibility_enabled · SYSTEM_ALERT_WINDOW + GET_USAGE_STATS appops ·
#   the shared Pixel device lock (only if this run acquired it) · logcat + sampler pids.
#
# Everything is idempotent and re-runnable: `setup` starts from `pm clear`, so no case
# depends on state a previous run left behind.

set -uo pipefail

# ─── Configuration ────────────────────────────────────────────────────────────
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="dev.astraedus.nudge"
NAMESPACE="com.astraedus.nudge"
A11Y_COMPONENT="${APP_ID}/${NAMESPACE}.service.NudgeAccessibilityService"
WATCHDOG_RECEIVER="${APP_ID}/${NAMESPACE}.service.WatchdogDebugReceiver"
WATCHDOG_ACTION="${APP_ID}.debug.RUN_WATCHDOG"
LAUNCHER_PKG="com.google.android.apps.nexuslauncher"
YOUTUBE_PKG="com.google.android.youtube"
LIMIT_PKG="com.google.android.calculator"

ADB_SERIAL="${ADB_SERIAL:-192.168.1.68:5555}"
APK="${APK:-main}"
NOTIF_IDLE_SECS="${NOTIF_IDLE_SECS:-300}"
HOME_REOPEN_TRIALS="${HOME_REOPEN_TRIALS:-5}"
MAESTRO_BIN="${MAESTRO_BIN:-${HOME}/.maestro/bin/maestro}"
GH_REPO="${GH_REPO:-astraedus/nudge}"

RUN_TS="$(date +%Y%m%d-%H%M%S)"
ALBUM="${HOME}/Pictures/screenshots/nudge/qa-${RUN_TS}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/nudge-qa-${RUN_TS}-XXXX")"
LOGCAT_FILE="${WORK}/logcat.txt"
SAMPLER_FILE="${WORK}/load.txt"

export PATH="${HOME}/.maestro/bin:${PATH}"
export MAESTRO_CLI_NO_ANALYTICS=true

# ─── Case registry — order is the run order for `all` ──────────────────────────
ALL_CASES=(
  setup
  delay-block
  home-reopen
  walkaway-count
  daily-limit-refresh
  notif-idle
  crash-check
  refusal-alert
)

# ─── Output helpers ───────────────────────────────────────────────────────────
info() { printf '[qa] %s\n' "$*"; }
warn() { printf '[qa] WARN: %s\n' "$*" >&2; }
fail() { printf '[qa] ERROR: %s\n' "$*" >&2; }
die()  { fail "$*"; exit 2; }

adbs() { adb -s "$ADB_SERIAL" "$@"; }
ash()  { adb -s "$ADB_SERIAL" shell "$@"; }

# ─── Results table ────────────────────────────────────────────────────────────
RESULT_NAMES=()
RESULT_STATUS=()
RESULT_SECS=()
RESULT_NOTE=()

record() { # name status secs note
  RESULT_NAMES+=("$1"); RESULT_STATUS+=("$2"); RESULT_SECS+=("$3"); RESULT_NOTE+=("${4:-}")
}

# ─── Saved device state (restored by the trap) ────────────────────────────────
SAVED_TIMEOUT=""
SAVED_A11Y_SERVICES=""
SAVED_A11Y_ENABLED=""
SAVED_OP_OVERLAY=""
SAVED_OP_USAGE=""
STATE_SAVED=0
LOCK_HELD=0
LOCK_OWNER="device-qa-${RUN_TS}"
LOGCAT_PID=""
SAMPLER_PID=""

stop_logcat() {
  [[ -n "$LOGCAT_PID" ]] && kill "$LOGCAT_PID" 2>/dev/null
  LOGCAT_PID=""
}

cleanup() {
  local code=$?
  stop_logcat
  [[ -n "$SAMPLER_PID" ]] && kill "$SAMPLER_PID" 2>/dev/null
  if [[ "$STATE_SAVED" -eq 1 ]]; then
    info "Restoring device state …"
    ash svc power stayon false >/dev/null 2>&1
    [[ -n "$SAVED_TIMEOUT" && "$SAVED_TIMEOUT" != "null" ]] &&
      ash settings put system screen_off_timeout "$SAVED_TIMEOUT" >/dev/null 2>&1
    if [[ -n "$SAVED_A11Y_SERVICES" && "$SAVED_A11Y_SERVICES" != "null" ]]; then
      ash settings put secure enabled_accessibility_services "$SAVED_A11Y_SERVICES" >/dev/null 2>&1
    fi
    [[ -n "$SAVED_A11Y_ENABLED" && "$SAVED_A11Y_ENABLED" != "null" ]] &&
      ash settings put secure accessibility_enabled "$SAVED_A11Y_ENABLED" >/dev/null 2>&1
    [[ -n "$SAVED_OP_OVERLAY" ]] &&
      ash appops set "$APP_ID" SYSTEM_ALERT_WINDOW "$SAVED_OP_OVERLAY" >/dev/null 2>&1
    [[ -n "$SAVED_OP_USAGE" ]] &&
      ash appops set "$APP_ID" GET_USAGE_STATS "$SAVED_OP_USAGE" >/dev/null 2>&1
  fi
  if [[ "$LOCK_HELD" -eq 1 ]]; then
    "${HOME}/bin/device-lock.sh" release pixel --owner "$LOCK_OWNER" >/dev/null 2>&1 &&
      info "Device lock released."
  fi
  exit "$code"
}
trap cleanup EXIT INT TERM

# ─── Device access ────────────────────────────────────────────────────────────
take_lock() {
  local out status
  out="$("${HOME}/bin/device-lock.sh" acquire pixel --owner "$LOCK_OWNER" --ttl 3600 2>&1)"
  status=$?
  if [[ $status -ne 0 ]]; then
    die "device lock held by another agent: ${out}"
  fi
  info "Device lock: ${out}"
  # Only release what we actually took. An `inherited` lock belongs to the calling
  # session, and releasing it would hand the phone to a peer mid-run.
  if [[ "$out" == *inherited* ]]; then LOCK_HELD=0; else LOCK_HELD=1; fi
}

connect_device() {
  adb start-server >/dev/null 2>&1
  adb connect "$ADB_SERIAL" >/dev/null 2>&1
  if ! adbs get-state >/dev/null 2>&1; then
    # One heal attempt through the canonical ladder before giving up.
    "${HOME}/bin/astra-adb" reconnect >/dev/null 2>&1 || true
    adb connect "$ADB_SERIAL" >/dev/null 2>&1
  fi
  adbs get-state >/dev/null 2>&1 || die "device $ADB_SERIAL not reachable"
  info "Device online: $ADB_SERIAL ($(ash getprop ro.build.version.release | tr -d '\r') / API $(ash getprop ro.build.version.sdk | tr -d '\r'))"
}

save_state() {
  SAVED_TIMEOUT="$(ash settings get system screen_off_timeout | tr -d '\r')"
  SAVED_A11Y_SERVICES="$(ash settings get secure enabled_accessibility_services | tr -d '\r')"
  SAVED_A11Y_ENABLED="$(ash settings get secure accessibility_enabled | tr -d '\r')"
  SAVED_OP_OVERLAY="$(appop_mode SYSTEM_ALERT_WINDOW)"
  SAVED_OP_USAGE="$(appop_mode GET_USAGE_STATS)"
  STATE_SAVED=1
  info "Saved: screen_off_timeout=${SAVED_TIMEOUT} overlay=${SAVED_OP_OVERLAY} usage=${SAVED_OP_USAGE}"
}

appop_mode() { # op -> allow|deny|ignore|default
  ash appops get "$APP_ID" "$1" 2>/dev/null | head -1 |
    sed -n 's/^[A-Z_]*: \([a-z]*\).*/\1/p'
}

pin_screen() {
  "${HOME}/bin/astra-pixel-unlock.sh" >/dev/null 2>&1 || warn "unlock helper reported a problem"
  ash settings put system screen_off_timeout 1800000 >/dev/null 2>&1
  ash svc power stayon true >/dev/null 2>&1
  info "Screen pinned (stayon, 30-minute timeout)."
}

# ─── Grants ───────────────────────────────────────────────────────────────────
# The OS-level route, not the UI one: onboarding's permission screens are a Play-policy
# surface and the `setup` flow still walks them, but a Maestro flow cannot drive the
# system accessibility Settings list reliably, so the grant itself is set here.
ensure_grants() {
  ash settings put secure enabled_accessibility_services "$A11Y_COMPONENT" >/dev/null 2>&1
  ash settings put secure accessibility_enabled 1 >/dev/null 2>&1
  ash appops set "$APP_ID" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  ash appops set "$APP_ID" GET_USAGE_STATS allow >/dev/null 2>&1
  ash pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

  # Bound, not merely enabled: the settings string is INTENT, the bound list is REALITY,
  # and the gap between them is exactly the fault the watchdog exists for.
  local i
  for i in $(seq 1 20); do
    if ash dumpsys accessibility 2>/dev/null |
       tr -d '\r' | grep -A3 'Bound services' | grep -q 'NudgeAccessibilityService'; then
      info "Accessibility service bound."
      return 0
    fi
    sleep 1
  done
  warn "accessibility service did not appear under 'Bound services' within 20s"
  return 1
}

# ─── APK resolution + install ─────────────────────────────────────────────────
expected_version_code() {
  if [[ -n "${EXPECT_VERSION_CODE:-}" ]]; then printf '%s' "$EXPECT_VERSION_CODE"; return; fi
  sed -n 's/.*versionCode *= *\([0-9]\+\).*/\1/p' "${REPO_ROOT}/app/build.gradle.kts" | head -1
}

installed_version_code() {
  ash dumpsys package "$APP_ID" 2>/dev/null | tr -d '\r' |
    sed -n 's/.*versionCode=\([0-9]\+\).*/\1/p' | head -1
}

installed_version_name() {
  ash dumpsys package "$APP_ID" 2>/dev/null | tr -d '\r' |
    sed -n 's/.*versionName=\(.*\)/\1/p' | head -1
}

install_apk() {
  case "$APK" in
    installed)
      info "APK=installed — driving the build already on the device."
      ;;
    main)
      info "Downloading nudge-main.apk from the main-latest prerelease …"
      gh release download main-latest --repo "$GH_REPO" --pattern 'nudge-main.apk' \
        --dir "$WORK" --clobber >/dev/null || die "gh release download (main-latest) failed"
      do_install "${WORK}/nudge-main.apk"
      ;;
    release)
      local tag
      tag="$(gh release list --repo "$GH_REPO" --limit 30 --json tagName \
             --jq '[.[] | select(.tagName | startswith("v"))][0].tagName')" ||
        die "gh release list failed"
      [[ -n "$tag" ]] || die "no v* release found on $GH_REPO"
      info "Downloading the APK asset of $tag …"
      gh release download "$tag" --repo "$GH_REPO" --pattern '*.apk' \
        --dir "$WORK" --clobber >/dev/null || die "gh release download ($tag) failed"
      do_install "$(find "$WORK" -maxdepth 1 -name '*.apk' | head -1)"
      ;;
    debug)
      info "Building assembleDebug locally …"
      ( cd "$REPO_ROOT" && ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" \
        ./gradlew --quiet assembleDebug ) || die "assembleDebug failed"
      # Debug is signed with debug keys; an in-place update over the release build is
      # refused with INSTALL_FAILED_UPDATE_INCOMPATIBLE. `setup` wipes data anyway.
      warn "uninstalling the release build so the debug-signed APK can install"
      ash pm uninstall "$APP_ID" >/dev/null 2>&1 || true
      do_install "${REPO_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
      ;;
    *)
      [[ -f "$APK" ]] || die "APK=$APK is neither a mode (main|release|installed|debug) nor a file"
      do_install "$APK"
      ;;
  esac

  ash pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:${APP_ID}" ||
    die "$APP_ID is not installed"

  local got want
  got="$(installed_version_code)"
  want="$(expected_version_code)"
  info "Installed: versionName=$(installed_version_name) versionCode=${got} (expected ${want})"
  if [[ "${SKIP_VERSION_CHECK:-0}" != "1" && -n "$want" && "$got" != "$want" ]]; then
    die "versionCode mismatch: device has ${got}, this checkout expects ${want}. Set SKIP_VERSION_CHECK=1 to override."
  fi
}

do_install() {
  local apk="$1"
  [[ -f "$apk" ]] || die "APK not found: $apk"
  info "Installing $(basename "$apk") …"
  local out
  out="$(adbs install -r -d "$apk" 2>&1)"
  if ! grep -q 'Success' <<<"$out"; then
    warn "install -r failed: ${out}"
    warn "retrying after uninstall (signature or downgrade mismatch)"
    ash pm uninstall "$APP_ID" >/dev/null 2>&1 || true
    out="$(adbs install "$apk" 2>&1)"
    grep -q 'Success' <<<"$out" || die "install failed: ${out}"
  fi
  # `install -r` leaves the OLD code running in the current process; force-stop so the
  # next launch is unambiguously the build we just pushed.
  ash am force-stop "$APP_ID" >/dev/null 2>&1
}

# ─── Laptop load sampler ──────────────────────────────────────────────────────
# Anti's question was "does this make the laptop laggy", so we measure the real thing:
# instantaneous CPU% of the Maestro JVM from /proc deltas (ps's %cpu is a lifetime
# average and would under-report a peak by an order of magnitude) plus its peak RSS.
start_sampler() {
  cat >"${WORK}/sampler.sh" <<'SAMPLER'
#!/usr/bin/env bash
# args: outfile interval_secs
out="$1"; iv="${2:-2}"
clk=$(getconf CLK_TCK)
ncpu=$(nproc)
declare -A prev_t prev_w
while :; do
  for pid in $(pgrep -f 'maestro' 2>/dev/null); do
    st="/proc/${pid}/stat"; [[ -r "$st" ]] || continue
    read -ra f < "$st" || continue
    # utime=14 stime=15 (1-indexed) -> f[13] f[14]
    ticks=$(( ${f[13]:-0} + ${f[14]:-0} ))
    now=$(date +%s%3N)
    if [[ -n "${prev_t[$pid]:-}" ]]; then
      dt=$(( now - prev_w[$pid] ))
      dticks=$(( ticks - prev_t[$pid] ))
      if (( dt > 0 )); then
        cpu=$(awk -v d="$dticks" -v c="$clk" -v t="$dt" 'BEGIN{printf "%.1f", (d/c)/(t/1000)*100}')
        rss=$(awk '/^VmRSS:/{print $2}' "/proc/${pid}/status" 2>/dev/null)
        echo "${pid} ${cpu} ${rss:-0} ${ncpu}" >> "$out"
      fi
    fi
    prev_t[$pid]=$ticks; prev_w[$pid]=$now
  done
  sleep "$iv"
done
SAMPLER
  chmod +x "${WORK}/sampler.sh"
  : >"$SAMPLER_FILE"
  nohup "${WORK}/sampler.sh" "$SAMPLER_FILE" 2 >/dev/null 2>&1 &
  SAMPLER_PID=$!
}

report_load() {
  if [[ ! -s "$SAMPLER_FILE" ]]; then
    echo "laptop load: not sampled (no maestro process seen)"
    return
  fi
  awk '{ if ($2+0 > pc) pc=$2+0; if ($3+0 > pr) pr=$3+0; n++; s+=$2+0; ncpu=$4 }
       END { printf "laptop load (maestro JVM, %d samples @2s, %d cores): peak CPU %.1f%% of one core (%.1f%% of the machine), mean %.1f%%, peak RSS %.0f MiB\n",
                    n, ncpu, pc, pc/ncpu, s/n, pr/1024 }' "$SAMPLER_FILE"
}

# ─── Maestro ──────────────────────────────────────────────────────────────────
run_flow() { # flow-file [extra maestro args…]
  local flow="$1"; shift
  [[ -f "${REPO_ROOT}/${flow}" ]] || { fail "missing flow ${flow}"; return 1; }
  ( cd "$REPO_ROOT" && "$MAESTRO_BIN" --udid "$ADB_SERIAL" test "$@" "$flow" )
}

archive_screenshots() {
  mkdir -p "$ALBUM"
  [[ "${KEEP_SCREENSHOTS_IN_REPO:-0}" == "1" ]] && return 0
  # Maestro's takeScreenshot writes PNGs into the CWD. MOVE them out so they never
  # litter the working tree, then also copy Maestro's own failure screenshots.
  find "$REPO_ROOT" -maxdepth 1 -name '*.png' -exec mv -f {} "$ALBUM/" \; 2>/dev/null
  local last
  last="$(find "${HOME}/.maestro/tests" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -1)"
  [[ -n "$last" ]] && find "$last" -name '*.png' -exec cp -f {} "$ALBUM/" \; 2>/dev/null
  return 0
}

# ─── logcat capture ───────────────────────────────────────────────────────────
start_logcat() {
  stop_logcat
  ash logcat -c >/dev/null 2>&1 || true
  : >"$LOGCAT_FILE"
  nohup adb -s "$ADB_SERIAL" logcat -v time >>"$LOGCAT_FILE" 2>&1 &
  LOGCAT_PID=$!
  sleep 1
}

# ─── Small device helpers ─────────────────────────────────────────────────────
ui_dump() { # prints the uiautomator XML of whatever is on screen
  ash uiautomator dump /sdcard/nudge-qa-dump.xml >/dev/null 2>&1
  ash cat /sdcard/nudge-qa-dump.xml 2>/dev/null | tr -d '\r'
}

launch_nudge_home() {
  ash am force-stop "$APP_ID" >/dev/null 2>&1
  ash monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
}

# The dashboard's week summary renders ONE string, "<n> blocked · <m> walked away"
# (ui/screens/home/HomeScreen.kt), which is why it is the anchor rather than the
# "Blocked" stat tile: the tile's label and value are separate semantics nodes and a
# second tile carries the same label.
read_blocked_counts() { # -> "<blocked> <walkedaway>", empty if not found
  local dump i out
  for i in 1 2 3 4; do
    dump="$(ui_dump)"
    out="$(grep -o '[0-9]\+ blocked · [0-9]\+ walked away' <<<"$dump" | head -1)"
    if [[ -n "$out" ]]; then
      awk '{print $1, $4}' <<<"$out"
      return 0
    fi
    ash input swipe 540 1500 540 700 300 >/dev/null 2>&1
    sleep 1
  done
  return 1
}

has_watchdog_receiver() {
  ash dumpsys package "$APP_ID" 2>/dev/null | tr -d '\r' | grep -q 'WatchdogDebugReceiver'
}

nudge_notif_records() { # prints "<count> <mUpdateTimeMs of id=1>"
  local d count upd
  d="$(ash dumpsys notification --noredact 2>/dev/null | tr -d '\r')"
  count="$(grep -c "NotificationRecord.*pkg=${APP_ID} " <<<"$d")"
  upd="$(awk -v pkg="$APP_ID" '
      $0 ~ ("NotificationRecord.*pkg=" pkg " ") { want=1 }
      want && /mUpdateTimeMs=/ { sub(/.*mUpdateTimeMs=/, ""); print $1; exit }
    ' <<<"$d")"
  echo "${count:-0} ${upd:-0}"
}

# ═══════════════════════════════════════════════════════════════════════════════
# CASES
# ═══════════════════════════════════════════════════════════════════════════════

case_setup() {
  info "pm clear — every case starts from a first-run install."
  ash pm clear "$APP_ID" >/dev/null 2>&1
  sleep 2
  ensure_grants || return 1
  ash am force-stop "$YOUTUBE_PKG" >/dev/null 2>&1
  ash am force-stop "$LIMIT_PKG" >/dev/null 2>&1
  run_flow .maestro/nudge-setup.yaml || return 1
  # Debug logging is a DataStore preference wiped by pm clear, and on a release build
  # NudgeLogger emits nothing without it (util/NudgeLogger.kt: BuildConfig.DEBUG ||
  # the preference). The home-reopen case greps those lines, so prove it took.
  if ! ash dumpsys accessibility 2>/dev/null | tr -d '\r' |
       grep -A3 'Bound services' | grep -q 'NudgeAccessibilityService'; then
    fail "accessibility service is not bound after setup"
    return 1
  fi
  return 0
}

case_delay_block() {
  run_flow .maestro/nudge-delay-block.yaml
}

case_home_reopen() {
  start_logcat
  local rc=0
  run_flow .maestro/nudge-home-reopen.yaml -e TRIALS="$HOME_REOPEN_TRIALS" || rc=1
  sleep 2
  stop_logcat
  cp -f "$LOGCAT_FILE" "${ALBUM}/logcat-home-reopen.txt" 2>/dev/null || true

  local went_home dropped redeemed
  went_home="$(grep -c "sitting ended package=${YOUTUBE_PKG} cause=WENT_HOME" "$LOGCAT_FILE")"
  dropped="$(grep -c "block overlay launch dropped target=${YOUTUBE_PKG}.*reason=DROP_FOREGROUND_MOVED" "$LOGCAT_FILE")"
  redeemed="$(grep -c "re-evaluating a dropped block target=${YOUTUBE_PKG}" "$LOGCAT_FILE")"
  info "logcat: WENT_HOME=${went_home} DROP_FOREGROUND_MOVED=${dropped} redeemed=${redeemed} (trials=${HOME_REOPEN_TRIALS})"

  if (( went_home < HOME_REOPEN_TRIALS )); then
    fail "only ${went_home}/${HOME_REOPEN_TRIALS} trials logged 'cause=WENT_HOME' — the grant was not revoked on every Home press"
    rc=1
  fi
  if (( dropped > redeemed )); then
    fail "${dropped} launches dropped as DROP_FOREGROUND_MOVED but only ${redeemed} were re-evaluated — issue #58 regressed"
    rc=1
  fi
  CASE_NOTE="WENT_HOME=${went_home}/${HOME_REOPEN_TRIALS} dropped=${dropped} redeemed=${redeemed}"
  return $rc
}

case_walkaway_count() {
  launch_nudge_home
  local before after b_before b_after w_before w_after
  before="$(read_blocked_counts)" || { fail "could not read the week summary on the dashboard"; return 1; }
  b_before="${before% *}"; w_before="${before#* }"
  info "before: blocked=${b_before} walkedAway=${w_before}"

  run_flow .maestro/nudge-walkaway-count.yaml || return 1

  launch_nudge_home
  after="$(read_blocked_counts)" || { fail "could not re-read the week summary"; return 1; }
  b_after="${after% *}"; w_after="${after#* }"
  info "after:  blocked=${b_after} walkedAway=${w_after}"

  CASE_NOTE="blocked ${b_before}->${b_after}, walkedAway ${w_before}->${w_after}"
  local db=$(( b_after - b_before )) dw=$(( w_after - w_before ))
  local rc=0
  (( db == 3 )) || { fail "Blocked moved by ${db}, expected +3"; rc=1; }
  (( dw == 3 )) || { fail "Walked away moved by ${dw}, expected +3"; rc=1; }
  return $rc
}

case_daily_limit_refresh() {
  run_flow .maestro/nudge-daily-limit-refresh.yaml -e LIMIT_SECS="${LIMIT_ACCRUE_SECS:-75}"
}

# ── notif-idle (#63): the ongoing notification must not be re-posted on a timer ──
case_notif_idle() {
  local rc=0
  local r0 r1 c0 u0 c1 u1
  r0="$(nudge_notif_records)"; c0="${r0% *}"; u0="${r0#* }"
  [[ "$u0" == "0" ]] && { fail "no nudge notification record found (id=1, channel=nudge_monitor)"; return 1; }
  info "baseline: records=${c0} mUpdateTimeMs=${u0}; sleeping ${NOTIF_IDLE_SECS}s with the screen off"
  ash input keyevent KEYCODE_SLEEP >/dev/null 2>&1
  sleep "$NOTIF_IDLE_SECS"
  "${HOME}/bin/astra-pixel-unlock.sh" >/dev/null 2>&1 || true
  r1="$(nudge_notif_records)"; c1="${r1% *}"; u1="${r1#* }"
  info "after idle: records=${c1} mUpdateTimeMs=${u1}"
  if [[ "$u1" != "$u0" ]]; then
    fail "the ongoing notification was re-posted while idle (mUpdateTimeMs ${u0} -> ${u1}) — #63 regressed"
    rc=1
  fi
  if [[ "$c1" != "$c0" ]]; then
    fail "nudge notification count changed while idle (${c0} -> ${c1})"
    rc=1
  fi

  # The other half of #63: posting on CHANGE must still happen. Revoking the
  # accessibility grant changes the copy, so exactly ONE re-post is owed; restoring it
  # owes exactly one more. A notification that never updates is as broken as one that
  # updates every 90 seconds.
  local saved="$SAVED_A11Y_SERVICES" mid mu mc back bu
  ash settings put secure enabled_accessibility_services "" >/dev/null 2>&1
  sleep 10
  mid="$(nudge_notif_records)"; mc="${mid% *}"; mu="${mid#* }"
  if [[ "$mu" == "$u1" ]]; then
    fail "the ongoing notification did NOT update after the accessibility grant was revoked — it is claiming Nudge is active while it is not"
    rc=1
  else
    info "grant revoked -> notification updated (${u1} -> ${mu}), as designed"
  fi
  ash settings put secure enabled_accessibility_services "$saved" >/dev/null 2>&1
  ash settings put secure accessibility_enabled 1 >/dev/null 2>&1
  sleep 10
  back="$(nudge_notif_records)"; bu="${back#* }"
  if [[ "$bu" == "$mu" ]]; then
    fail "the ongoing notification did NOT update after the grant was restored"
    rc=1
  else
    info "grant restored -> notification updated (${mu} -> ${bu})"
  fi
  ensure_grants >/dev/null 2>&1 || warn "accessibility service did not rebind after the toggle"
  CASE_NOTE="idle ${NOTIF_IDLE_SECS}s: mUpdateTimeMs unchanged; change-driven re-post seen twice"
  return $rc
}

CRASH_BASELINE=""
crash_baseline() {
  CRASH_BASELINE="$(ash "dumpsys dropbox --print | grep -c ${APP_ID}" 2>/dev/null | tr -d '\r')"
  CRASH_BASELINE="${CRASH_BASELINE:-0}"
}

case_crash_check() {
  local rc=0 crashed now
  crashed="$(ash dumpsys accessibility 2>/dev/null | tr -d '\r' | grep -i 'Crashed services' | head -1)"
  info "accessibility: ${crashed:-<no Crashed services line>}"
  if [[ "$crashed" != *'Crashed services:{}'* ]]; then
    fail "accessibility reports crashed services: ${crashed}"
    rc=1
  fi
  now="$(ash "dumpsys dropbox --print | grep -c ${APP_ID}" 2>/dev/null | tr -d '\r')"
  now="${now:-0}"
  info "dropbox entries mentioning ${APP_ID}: baseline=${CRASH_BASELINE} now=${now}"
  if (( now > CRASH_BASELINE )); then
    fail "dropbox gained $(( now - CRASH_BASELINE )) entries mentioning ${APP_ID} during this run"
    ash "dumpsys dropbox --print | grep -A20 ${APP_ID}" 2>/dev/null | tail -60 >&2
    rc=1
  fi
  CASE_NOTE="Crashed services:{} · dropbox delta $(( now - CRASH_BASELINE ))"
  return $rc
}

# ── refusal-alert (#62): a REFUSED foreground-service start is a fault of its own ──
case_refusal_alert() {
  if ! has_watchdog_receiver; then
    CASE_NOTE="debug-only trigger absent (release build) — run with APK=debug to cover #62"
    return 77   # SKIP
  fi
  local rc=0 first second
  ash appops set "$APP_ID" SYSTEM_ALERT_WINDOW deny >/dev/null 2>&1
  ash am force-stop "$APP_ID" >/dev/null 2>&1
  sleep 2
  # --ez reset true clears the degraded flag and the 12-hour alert cooldown, which is
  # what makes this case re-runnable instead of testable twice a day.
  first="$(ash am broadcast -a "$WATCHDOG_ACTION" -n "$WATCHDOG_RECEIVER" --ez reset true 2>&1 | tr -d '\r')"
  info "cycle 1: ${first}"
  sleep 3
  second="$(ash am broadcast -a "$WATCHDOG_ACTION" -n "$WATCHDOG_RECEIVER" 2>&1 | tr -d '\r')"
  info "cycle 2: ${second}"
  grep -q 'reported=MONITOR_START_BLOCKED' <<<"$second" ||
    { fail "cycle 2 did not report MONITOR_START_BLOCKED: ${second}"; rc=1; }
  grep -q 'service start REFUSED by platform' <<<"$second" ||
    warn "cycle 2 did not carry the '(service start REFUSED by platform)' suffix"
  if ! ash dumpsys notification --noredact 2>/dev/null | tr -d '\r' |
       grep -q 'nudge_protection_alerts'; then
    fail "no notification on channel nudge_protection_alerts — the verdict was reached but the post was dropped"
    rc=1
  else
    info "protection alert present on nudge_protection_alerts."
  fi
  # The alert's tap target is MainActivity, and that is also the CURE: a visible
  # MainActivity is a legal foreground moment, so the resume retry restarts the service.
  ash appops set "$APP_ID" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  launch_nudge_home
  sleep 3
  if ash dumpsys activity services "$APP_ID" 2>/dev/null | tr -d '\r' |
     grep -q 'NudgeMonitorService'; then
    info "NudgeMonitorService is running again after opening Nudge."
  else
    fail "NudgeMonitorService did not come back after MainActivity resumed"
    rc=1
  fi
  CASE_NOTE="cycle2 reported=MONITOR_START_BLOCKED · alert posted · service healed on resume"
  return $rc
}

# ═══════════════════════════════════════════════════════════════════════════════
# Driver
# ═══════════════════════════════════════════════════════════════════════════════

run_case() { # name
  local name="$1" fn start end rc
  fn="case_${name//-/_}"
  declare -F "$fn" >/dev/null || { fail "unknown case: $name"; return 2; }
  CASE_NOTE=""
  printf '\n[qa] ══ %s ══\n' "$name"
  start=$(date +%s)
  "$fn"; rc=$?
  end=$(date +%s)
  archive_screenshots
  case $rc in
    0)  record "$name" PASS "$(( end - start ))" "$CASE_NOTE"; info "$name PASS ($(( end - start ))s)" ;;
    77) record "$name" SKIP "$(( end - start ))" "$CASE_NOTE"; warn "$name SKIP — $CASE_NOTE" ;;
    *)  record "$name" FAIL "$(( end - start ))" "$CASE_NOTE"; fail "$name FAIL ($(( end - start ))s)" ;;
  esac
  return 0
}

print_table() {
  local total=0 failed=0 i
  printf '\n'
  printf '┌─────────────────────────┬────────┬──────────┐\n'
  printf '│ %-23s │ %-6s │ %8s │\n' "case" "result" "duration"
  printf '├─────────────────────────┼────────┼──────────┤\n'
  for i in "${!RESULT_NAMES[@]}"; do
    printf '│ %-23s │ %-6s │ %7ss │\n' "${RESULT_NAMES[$i]}" "${RESULT_STATUS[$i]}" "${RESULT_SECS[$i]}"
    total=$(( total + RESULT_SECS[i] ))
    [[ "${RESULT_STATUS[$i]}" == "FAIL" ]] && failed=$(( failed + 1 ))
  done
  printf '├─────────────────────────┼────────┼──────────┤\n'
  printf '│ %-23s │ %-6s │ %7ss │\n' "TOTAL" "$([[ $failed -eq 0 ]] && echo PASS || echo FAIL)" "$total"
  printf '└─────────────────────────┴────────┴──────────┘\n'
  for i in "${!RESULT_NAMES[@]}"; do
    [[ -n "${RESULT_NOTE[$i]}" ]] && printf '  %-23s %s\n' "${RESULT_NAMES[$i]}:" "${RESULT_NOTE[$i]}"
  done
  printf '\n%s\n' "$(report_load)"
  printf 'screenshots: %s (%s files)\n' "$ALBUM" "$(find "$ALBUM" -name '*.png' 2>/dev/null | wc -l | tr -d ' ')"
  return $failed
}

usage() {
  sed -n '2,45p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  printf 'cases: %s\n' "${ALL_CASES[*]}"
}

main() {
  local target="${1:-all}"
  case "$target" in
    -h|--help|help) usage; exit 0 ;;
    list) printf '%s\n' "${ALL_CASES[@]}"; exit 0 ;;
  esac

  local cases=()
  if [[ "$target" == "all" ]]; then
    cases=("${ALL_CASES[@]}")
  else
    # shellcheck disable=SC2076
    [[ " ${ALL_CASES[*]} " == *" ${target} "* ]] || die "unknown case '${target}'. Try: ${ALL_CASES[*]}"
    cases=("$target")
    # Every case but `setup` assumes the fixtures `setup` creates, so a single case
    # runs against whatever is on the phone. That is deliberate (it is how you iterate
    # on one case), and the version + grant checks below still run.
  fi

  take_lock
  connect_device
  save_state
  pin_screen
  install_apk
  ensure_grants || warn "grants incomplete — cases may fail for the wrong reason"
  crash_baseline
  mkdir -p "$ALBUM"
  start_sampler
  info "album: ${ALBUM}"
  info "work:  ${WORK}"

  local c
  for c in "${cases[@]}"; do run_case "$c"; done

  print_table
  local failed=$?
  if (( failed > 0 )); then
    fail "${failed} case(s) FAILED"
    exit 1
  fi
  info "ALL CASES PASSED"
  exit 0
}

main "$@"
