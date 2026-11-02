#!/usr/bin/env bash
# qa-driver.sh — runs the `:qa-driver` instrumented device-QA cases against the `qa` build.
#
#   scripts/qa-driver.sh                    # delay-block walkaway-count home-reopen notif-idle
#   scripts/qa-driver.sh delay-block home-reopen
#   scripts/qa-driver.sh spike spike-control   # the phase 0 suppression proof and its control
#   scripts/qa-driver.sh list
#
# ── WHAT THIS IS, AND WHY IT IS NOT device-qa.sh ─────────────────────────────
# `scripts/device-qa.sh` drives Nudge with plain ADB because every UiAutomator-based driver
# suppresses other accessibility services, and Nudge IS one. The `:qa-driver` tests opt out of that
# with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES (proved by `spike` / `spike-control`, see
# docs/TESTING.md, L6), so the same cases become typed Kotlin with real selectors, and the bench's
# preconditions come from the qa build's control receiver (reset / seed-rules / state) instead of
# `pm clear` plus an onboarding walk. This script is only the host half: lock, unlock, build,
# install, grants, one `am instrument` per case, pull the evidence, print a table, restore.
#
# ── The qa build is DEBUG-signed; the bench carries the RELEASE key ───────────
# Installing it is a signing-key switch, so it goes through scripts/cert-guard.sh like every other
# bench install: refused unless ALLOW_CERT_SWITCH=1, which uninstalls (wiping the bench's Nudge).
# Debug signing is deliberate: a qa APK must never be signable as something Play would accept.
# Put the release build back afterwards with:
#   APK=main ALLOW_CERT_SWITCH=1 scripts/device-qa.sh setup
#
# ── Why one `am instrument` per case, started DETACHED on the device ─────────
#  · The bench PIN stays out of this public repo, so unlocking is a host step
#    (~/bin/astra-pixel-unlock.sh) and runs before EVERY case; notif-idle turns the screen off.
#  · notif-idle runs ~7 minutes with the screen off. A `-w` session tied to this adb connection
#    dies with it (Wi-Fi adb across a screen-off is not something to bet a gate on), so each case
#    runs under `nohup` on the device, writing its result to a file this script polls.
#
# ── Knobs ─────────────────────────────────────────────────────────────────────
#   ADB_SERIAL=192.168.1.68:5555   bench Pixel 3
#   ALLOW_CERT_SWITCH=1            allow replacing a release-signed Nudge with the debug-signed qa build
#   SKIP_BUILD=1                   install the APKs already in app/ and qa-driver/ build outputs
#   NOTIF_IDLE_SECS=300            notif-idle's screen-off window (#63)
#   HOME_REOPEN_TRIALS=5           home-reopen's repeats (#58)
#   QA_LOCK_OWNER=<name>           reuse a Pixel lock the caller already holds (same process tree)
#
# Evidence (screenshots, logcat windows, raw instrumentation output) lands in
# ~/Pictures/screenshots/nudge/qa-driver/<run>/. Exits nonzero if any case fails.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="dev.astraedus.nudge"
A11Y_COMPONENT="${APP_ID}/com.astraedus.nudge.service.NudgeAccessibilityService"
QA_RECEIVER="${APP_ID}/com.astraedus.nudge.qa.QaControlReceiver"
QA_ACTION="dev.astraedus.nudge.qa.CMD"
DRIVER_ID="dev.astraedus.qadriver"
RUNNER="${DRIVER_ID}/androidx.test.runner.AndroidJUnitRunner"
FIXTURE="${REPO_ROOT}/.maestro/fixtures/rules.json"

ADB_SERIAL="${ADB_SERIAL:-192.168.1.68:5555}"
ALLOW_CERT_SWITCH="${ALLOW_CERT_SWITCH:-0}"
NOTIF_IDLE_SECS="${NOTIF_IDLE_SECS:-300}"
HOME_REOPEN_TRIALS="${HOME_REOPEN_TRIALS:-5}"
RUN_TS="$(date +%Y%m%d-%H%M%S)"
LOCK_OWNER="${QA_LOCK_OWNER:-qa-driver-${RUN_TS}}"
ALBUM="${HOME}/Pictures/screenshots/nudge/qa-driver/${RUN_TS}"
DEVICE_OUT="/data/local/tmp/qa-driver/${RUN_TS}"

# shellcheck source=scripts/cert-guard.sh
. "${REPO_ROOT}/scripts/cert-guard.sh"

DEFAULT_CASES=(delay-block walkaway-count home-reopen notif-idle)

# case -> "<test class> <timeout secs> [extra instrumentation args]". The timeouts are ceilings on
# a hung case, sized at ~3x a measured run, never the oracle.
case_spec() {
  case "$1" in
    delay-block)    echo "DelayBlockTest 180" ;;
    walkaway-count) echo "WalkawayCountTest 300" ;;
    home-reopen)    echo "HomeReopenTest 900 -e qa.trials ${HOME_REOPEN_TRIALS}" ;;
    notif-idle)     echo "NotifIdleTest $((NOTIF_IDLE_SECS + 600)) -e qa.idleSecs ${NOTIF_IDLE_SECS}" ;;
    spike)          echo "SuppressionSpikeTest 180" ;;
    spike-control)  echo "SuppressionSpikeTest 180 -e qa.suppress true" ;;
    *) return 1 ;;
  esac
}

info() { printf '[qa-driver] %s\n' "$*"; }
warn() { printf '[qa-driver] WARN: %s\n' "$*" >&2; }
die()  { printf '[qa-driver] ERROR: %s\n' "$*" >&2; exit 2; }
adbs() { adb -s "$ADB_SERIAL" "$@"; }
ash()  { adb -s "$ADB_SERIAL" shell "$@"; }

# ─── Saved device state, restored on every exit path ──────────────────────────
SAVED_TIMEOUT=""
# The accessibility grant is not saved: this run leaves it set to Nudge, which is the bench's normal
# state, and the release restore (CERT_RESTORE_CMD) re-applies every grant anyway.
STATE_SAVED=0
LOCK_HELD=0
INSTALLED_QA=0

cleanup() {
  local code=$?
  if [[ "$STATE_SAVED" -eq 1 ]]; then
    ash svc power stayon false >/dev/null 2>&1
    [[ -n "$SAVED_TIMEOUT" && "$SAVED_TIMEOUT" != "null" ]] &&
      ash settings put system screen_off_timeout "$SAVED_TIMEOUT" >/dev/null 2>&1
  fi
  if [[ "$LOCK_HELD" -eq 1 ]]; then
    "${HOME}/bin/device-lock.sh" release pixel --owner "$LOCK_OWNER" >/dev/null 2>&1 && info "Device lock released."
  fi
  if [[ "$INSTALLED_QA" -eq 1 ]]; then
    info "The bench now carries the DEBUG-signed qa build. Put the release build back with:"
    info "  ${CERT_RESTORE_CMD}"
  fi
  exit "$code"
}
trap cleanup EXIT INT TERM

take_lock() {
  local out
  out="$("${HOME}/bin/device-lock.sh" acquire pixel --owner "$LOCK_OWNER" --ttl 3600 2>&1)" ||
    die "device lock held by another agent: ${out}"
  info "Device lock: ${out}"
  # Release only what this run took; an inherited lock is the caller's.
  [[ "$out" == *inherited* || "$out" == *re-entrant* ]] || LOCK_HELD=1
}

connect_device() {
  adb connect "$ADB_SERIAL" >/dev/null 2>&1
  adbs get-state >/dev/null 2>&1 || die "device $ADB_SERIAL not reachable (try ~/bin/pixel-adb-heal.sh once)"
  info "Device online: $ADB_SERIAL (API $(ash getprop ro.build.version.sdk | tr -d '\r'))"
}

# Unlock and keep the screen on. Fatal if it fails: a case run against a black lock screen FAILs
# for a reason that is not the app (device-qa.sh's pin_screen, 2026-09-29).
pin_screen() {
  local i
  for i in 1 2; do
    "${HOME}/bin/astra-pixel-unlock.sh" >/dev/null 2>&1 || warn "unlock helper reported a problem (attempt ${i})"
    ash svc power stayon true >/dev/null 2>&1
    sleep 2
    if ash dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake' &&
      ! ash dumpsys window 2>/dev/null | grep -q 'mDreamingLockscreen=true'; then
      return 0
    fi
  done
  return 1
}

# ─── Install ──────────────────────────────────────────────────────────────────
install_qa() { # apk case...
  local apk="$1" pulled installed_sha incoming_sha decision rc=0
  shift
  pulled="$(mktemp -d)/installed.apk"
  pull_installed_apk "$ADB_SERIAL" "$APP_ID" "$pulled" || rc=$?
  case $rc in
    0) installed_sha="$(apk_signer_sha256 "$pulled")" ;;
    1) installed_sha="none" ;;
    *) installed_sha="" ;;
  esac
  incoming_sha="$(apk_signer_sha256 "$apk")"
  signer_is_debug "$(apk_signer_dn "$apk")" || die "the qa APK is not debug-signed ($(apk_signer_dn "$apk")); refusing to install it"
  decision="$(cert_decision "$installed_sha" "$incoming_sha" "$ALLOW_CERT_SWITCH")"
  case "$decision" in
    fresh|same) info "Signing key: ${decision}" ;;
    unknown) warn "could not compare signing keys; adb's own check is the backstop" ;;
    refuse)
      printf '%s\n' "$(cert_refusal_message "$(signer_label "$(apk_signer_dn "$pulled")")" \
        "$(signer_label "$(apk_signer_dn "$apk")")" "ALLOW_CERT_SWITCH=1 scripts/qa-driver.sh $*")" >&2
      exit 2 ;;
    switch)
      cert_switch_banner "$(signer_label "$(apk_signer_dn "$pulled")")" "$(signer_label "$(apk_signer_dn "$apk")")" >&2
      ash pm uninstall "$APP_ID" >/dev/null 2>&1 || true ;;
  esac
  local out
  out="$(adbs install -r "$apk" 2>&1)"
  grep -q Success <<<"$out" || die "qa install failed: ${out}"
  INSTALLED_QA=1
  # `install -r` leaves the OLD code in a running process.
  ash am force-stop "$APP_ID" >/dev/null 2>&1
}

# ─── Grants ───────────────────────────────────────────────────────────────────
a11y_bound() {
  # The LABEL on the `Bound services` line, never a grep that can reach the Enabled line below it
  # (DeviceText.boundServicesLine says why).
  ash dumpsys accessibility 2>/dev/null | tr -d '\r' | grep -m1 'Bound services:' | grep -q 'label=Nudge'
}

# Applies the OS grants and proves the accessibility one STUCK: the OS can prune a fresh grant
# seconds after it binds (device-qa.sh's ensure_grants has the measurement).
ensure_grants() {
  local attempt i
  for attempt in 1 2 3; do
    ash settings put secure enabled_accessibility_services "$A11Y_COMPONENT" >/dev/null 2>&1
    ash settings put secure accessibility_enabled 1 >/dev/null 2>&1
    ash appops set "$APP_ID" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
    ash appops set "$APP_ID" GET_USAGE_STATS allow >/dev/null 2>&1
    ash pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    for ((i = 0; i < 15; i++)); do a11y_bound && break; sleep 1; done
    if a11y_bound; then
      sleep 4
      a11y_bound && { info "Accessibility service bound (stable, attempt ${attempt})."; return 0; }
    fi
    warn "attempt ${attempt}: the accessibility grant did not stay bound; re-applying"
  done
  return 1
}

# One qa control command from the host (the tests send their own; this seeds `spike`, which is not
# a NudgeCase). Prints the receiver's answer line.
qa_cmd() {
  ash am broadcast -n "$QA_RECEIVER" -a "$QA_ACTION" "$@" 2>/dev/null | tr -d '\r' | grep 'Broadcast completed'
}

# ─── Running one case ─────────────────────────────────────────────────────────
RESULT_NAMES=(); RESULT_STATUS=(); RESULT_SECS=(); RESULT_NOTE=()

run_case() { # name
  local name="$1" spec cls timeout extra out started status note file
  spec="$(case_spec "$name")" || die "unknown case '$name' (try: list)"
  read -r cls timeout extra <<<"$spec"
  file="${DEVICE_OUT}/${name}.instrument.txt"
  info "── ${name} (${cls}) ──"
  pin_screen || warn "the screen is not usable before ${name}; its result may be the harness's"
  if [[ "$name" == spike* ]]; then
    qa_cmd --es cmd reset >/dev/null
    qa_cmd --es cmd seed-rules --es b64 "$(base64 -w0 "$FIXTURE")" | sed 's/^/[qa-driver]   seed: /'
  fi
  started=$(date +%s)
  # Single-quoted for the DEVICE shell: nohup + redirect + & must all happen over there.
  ash "nohup am instrument -w -r -e class ${DRIVER_ID}.${cls} -e qa.out ${DEVICE_OUT} ${extra:-} ${RUNNER} > ${file} 2>&1 &"
  out=""
  while (( $(date +%s) - started < timeout )); do
    sleep 10
    out="$(ash cat "$file" 2>/dev/null | tr -d '\r')"
    [[ "$out" == *INSTRUMENTATION_CODE* ]] && break
    adbs get-state >/dev/null 2>&1 || adb connect "$ADB_SERIAL" >/dev/null 2>&1
  done
  if [[ "$out" == *"OK (1 test)"* ]]; then
    status=PASS; note=""
  elif [[ "$out" != *INSTRUMENTATION_CODE* ]]; then
    status=FAIL; note="no result within ${timeout}s"
  else
    status=FAIL
    # The first assertion message: the line after `stack=` names what broke.
    note="$(grep -m1 -A0 'stack=' <<<"$out" | sed 's/.*stack=//' | cut -c1-160)"
  fi
  RESULT_NAMES+=("$name"); RESULT_STATUS+=("$status"); RESULT_SECS+=("$(( $(date +%s) - started ))"); RESULT_NOTE+=("$note")
  info "${name}: ${status} ${note}"
}

print_table() {
  local i failed=0
  printf '\n%-16s %-6s %6s  %s\n' CASE RESULT SECS NOTE
  for i in "${!RESULT_NAMES[@]}"; do
    printf '%-16s %-6s %6s  %s\n' "${RESULT_NAMES[$i]}" "${RESULT_STATUS[$i]}" "${RESULT_SECS[$i]}" "${RESULT_NOTE[$i]}"
    [[ "${RESULT_STATUS[$i]}" == PASS ]] || failed=1
  done
  printf '\nEvidence: %s\n' "$ALBUM"
  return $failed
}

main() {
  local cases=("$@")
  if [[ "${1:-}" == list ]]; then
    printf '%s\n' "${DEFAULT_CASES[@]}" spike spike-control
    return 0
  fi
  [[ ${#cases[@]} -gt 0 ]] || cases=("${DEFAULT_CASES[@]}")
  local c
  for c in "${cases[@]}"; do case_spec "$c" >/dev/null || die "unknown case '$c' (try: list)"; done

  take_lock
  connect_device
  SAVED_TIMEOUT="$(ash settings get system screen_off_timeout | tr -d '\r')"
  STATE_SAVED=1
  ash settings put system screen_off_timeout 1800000 >/dev/null 2>&1
  pin_screen || die "the phone will not wake/unlock; run ~/bin/astra-pixel-unlock.sh by hand and look at the device"

  if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
    info "Building the qa APK and the driver …"
    (cd "$REPO_ROOT" && ./gradlew :app:assembleQa :qa-driver:assembleDebugAndroidTest --max-workers=2 -q) ||
      die "build failed"
  fi
  local qa_apk driver_apk
  qa_apk="$(find "${REPO_ROOT}/app/build/outputs/apk/qa" -name '*.apk' | head -1)"
  driver_apk="${REPO_ROOT}/qa-driver/build/outputs/apk/androidTest/debug/qa-driver-debug-androidTest.apk"
  [[ -f "$qa_apk" && -f "$driver_apk" ]] || die "missing APKs (qa='${qa_apk}', driver='${driver_apk}'); run without SKIP_BUILD"

  install_qa "$qa_apk" "${cases[@]}"
  adbs install -r -t "$driver_apk" | grep -q Success || die "driver install failed"
  # A freshly installed app is in the STOPPED state, which keeps broadcasts (the qa control channel
  # included) away from it until something launches it once.
  ash monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  ash input keyevent KEYCODE_HOME >/dev/null 2>&1
  ensure_grants || die "the accessibility service would not stay bound; nothing below would mean anything"

  ash mkdir -p "$DEVICE_OUT"
  for c in "${cases[@]}"; do run_case "$c"; done

  mkdir -p "$ALBUM"
  adbs pull "${DEVICE_OUT}/." "$ALBUM" >/dev/null 2>&1 || warn "could not pull ${DEVICE_OUT}"
  ash rm -rf "$DEVICE_OUT" >/dev/null 2>&1
  print_table
}

main "$@"
