#!/usr/bin/env bash
# device-qa.sh — Nudge's SCRIPTED release-gate device QA.
#
# Replaces the 60–100 minute LLM tap-walk that gated every release with a deterministic run
# that finishes in minutes, prints a PASS/FAIL table, and exits nonzero on any failure.
#
#   scripts/device-qa.sh            # every case (same as `all`)
#   scripts/device-qa.sh all
#   scripts/device-qa.sh delay-block
#   scripts/device-qa.sh list
#
# ── WHY MOST OF THIS IS NOT MAESTRO (the central design fact) ─────────────────
# Maestro — and every other UiAutomator-based driver — connects a `UiAutomation` session, and
# Android SUPPRESSES ALL OTHER ACCESSIBILITY SERVICES while one is connected (opting out needs
# `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, which Maestro does not pass). Nudge IS an
# accessibility service, so a Maestro flow silently DISABLES THE FEATURE UNDER TEST. Measured:
# during a Maestro session `Bound services:{}`, YouTube opens instantly, zero evaluation lines;
# the moment it exits, the same launch is blocked for 5s. See the long comment above `ui_dump`.
#
# So blocking behaviour is driven with plain ADB, and asserted against two non-invasive
# oracles — the foreground activity (`dumpsys activity activities`) and the service's own
# decision log (`block package=… reason=delay_rule delaySeconds=5`) — with `uiautomator dump`
# used only to read on-screen copy and to LOCATE tap targets at runtime (never fixed
# coordinates). `.maestro/nudge-setup.yaml` is the one Maestro flow kept, because it drives
# only Nudge's own UI (onboarding, Settings, backup import) where nothing needs to be blocked.
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
#   LIMIT_WAIT_SECS=150            how long to wait for the 1-minute daily limit to fire
#   QA_LOCK_OWNER=<name>           reuse a Pixel lock the caller already holds
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
# The 1-minute Calculator budget is spent by real foreground time and noticed by a clock that
# ticks every 30s (`FOREGROUND_TICK_MS`), so the worst case is ~90s. 150s is that plus slack.
LIMIT_WAIT_SECS="${LIMIT_WAIT_SECS:-150}"
MAESTRO_BIN="${MAESTRO_BIN:-${HOME}/.maestro/bin/maestro}"
GH_REPO="${GH_REPO:-astraedus/nudge}"

RUN_TS="$(date +%Y%m%d-%H%M%S)"
ALBUM="${HOME}/Pictures/screenshots/nudge/qa-${RUN_TS}"
# Work dir is deliberately NOT under /tmp. On this laptop /tmp is a 3.6G tmpfs that other
# lanes fill with screenshot and build scratch, and Maestro died mid-run with
# `java.io.IOException: No space left on device` at 265M free. ~/.cache is on the 229G
# root filesystem, and Maestro's own JVM temp dir is pointed here too.
mkdir -p "${HOME}/.cache/nudge-qa"
WORK="$(mktemp -d "${HOME}/.cache/nudge-qa/run-${RUN_TS}-XXXX")"
LOGCAT_FILE="${WORK}/logcat.txt"
SAMPLER_FILE="${WORK}/load.txt"
FIXTURE_DEVICE_PATH="/sdcard/Download/nudge-qa-rules.json"

export PATH="${HOME}/.maestro/bin:${PATH}"
export MAESTRO_CLI_NO_ANALYTICS=true
export MAESTRO_OPTS="${MAESTRO_OPTS:-} -Djava.io.tmpdir=${WORK}"

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
# Overridable so a caller that ALREADY holds the Pixel lock can hand it down instead of
# being locked out by itself: `~/bin/device-lock.sh` keys a holder on owner+pid, and every
# agent in one Claude session shares the pid.
LOCK_OWNER="${QA_LOCK_OWNER:-device-qa-${RUN_TS}}"
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
  # Only release what we actually took. An `inherited` or `re-entrant` lock belongs to the
  # caller, and releasing it at our exit would hand the phone to a peer mid-run — the
  # caller is still using it (this is why QA_LOCK_OWNER exists).
  if [[ "$out" == *inherited* || "$out" == *re-entrant* ]]; then
    LOCK_HELD=0
    info "Lock belongs to the caller; this run will not release it."
  else
    LOCK_HELD=1
  fi
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

a11y_is_bound() {
  ash dumpsys accessibility 2>/dev/null |
    tr -d '\r' | grep -A3 'Bound services' | grep 'NudgeAccessibilityService' >/dev/null
}

apply_grants() {
  ash settings put secure enabled_accessibility_services "$A11Y_COMPONENT" >/dev/null 2>&1
  ash settings put secure accessibility_enabled 1 >/dev/null 2>&1
  ash appops set "$APP_ID" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  ash appops set "$APP_ID" GET_USAGE_STATS allow >/dev/null 2>&1
  ash pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
}

# Applies the grants and proves they STUCK.
#
# The re-check is not belt-and-braces, it is the whole point. Measured on the bench:
# `pm clear` deletes `enabled_accessibility_services` outright, and a `settings put` issued
# straight afterwards binds the service — and is then silently PRUNED again the moment the
# app is first launched, because AccessibilityManagerService re-reads the setting against an
# installed-services snapshot our write had raced. Observed at 23:36:54 with MainActivity in
# front, ~11s after a confirmed-bound grant. A single apply-and-check therefore reports
# success for a device that is unprotected by the time the first case runs.
ensure_grants() {
  local attempt
  for attempt in 1 2 3; do
    apply_grants
    local i
    for i in $(seq 1 15); do
      a11y_is_bound && break
      sleep 1
    done
    if ! a11y_is_bound; then
      warn "attempt ${attempt}: accessibility service did not bind within 15s"
      continue
    fi
    # Give the OS the window in which it does the pruning, then look again.
    sleep 4
    if a11y_is_bound; then
      info "Accessibility service bound (stable after attempt ${attempt})."
      return 0
    fi
    warn "attempt ${attempt}: the grant was pruned after binding; re-applying"
  done
  fail "accessibility service would not stay bound after 3 attempts"
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

  ash pm list packages 2>/dev/null | tr -d '\r' | grep -x "package:${APP_ID}" >/dev/null ||
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
# Emits one line per sample: "<group> <cpu_pct_of_one_core> <rss_kib> <ncpu> <sysbusy_pct>"
#
# CPU% is computed from /proc/<pid>/stat DELTAS, not from `ps -o %cpu`: ps reports a LIFETIME
# average, which for a JVM that spikes then idles under-reports the peak by an order of
# magnitude — and the peak is precisely what "does this make the laptop laggy" is asking about.
# Groups are summed per sample, because the harness's cost is spread over the maestro JVM
# plus a fleet of short-lived adb clients, and either alone understates it.
out="$1"; iv="${2:-2}"
clk=$(getconf CLK_TCK); ncpu=$(nproc)
declare -A prev_t prev_w
prev_idle=0; prev_tot=0
group_of() { case "$1" in *maestro*|*java*) echo maestro ;; *adb*) echo adb ;; *) echo other ;; esac; }

while :; do
  # Whole-machine busy%, so the report can say what share of the laptop this run used.
  read -ra c < /proc/stat
  idle=$(( ${c[4]} + ${c[5]} )); tot=0
  for v in "${c[@]:1:8}"; do tot=$(( tot + v )); done
  sysbusy=0
  if (( prev_tot > 0 && tot > prev_tot )); then
    sysbusy=$(awk -v di=$(( idle - prev_idle )) -v dt=$(( tot - prev_tot )) \
      'BEGIN{printf "%.1f", (1 - di/dt) * 100}')
  fi
  prev_idle=$idle; prev_tot=$tot

  declare -A cpu_sum rss_sum
  cpu_sum=(); rss_sum=()
  for pid in $(pgrep -f '[m]aestro|[a]db' 2>/dev/null); do
    st="/proc/${pid}/stat"; [[ -r "$st" ]] || continue
    cmd="$(tr '\0' ' ' < "/proc/${pid}/cmdline" 2>/dev/null)"
    g="$(group_of "$cmd")"
    read -ra f < "$st" || continue
    ticks=$(( ${f[13]:-0} + ${f[14]:-0} ))   # utime + stime (1-indexed 14,15)
    now=$(date +%s%3N)
    if [[ -n "${prev_t[$pid]:-}" ]]; then
      dt=$(( now - prev_w[$pid] )); dticks=$(( ticks - prev_t[$pid] ))
      if (( dt > 0 )); then
        cpu_sum[$g]=$(awk -v a="${cpu_sum[$g]:-0}" -v d="$dticks" -v c="$clk" -v t="$dt" \
          'BEGIN{printf "%.1f", a + (d/c)/(t/1000)*100}')
      fi
    fi
    rss=$(awk '/^VmRSS:/{print $2}' "/proc/${pid}/status" 2>/dev/null)
    rss_sum[$g]=$(( ${rss_sum[$g]:-0} + ${rss:-0} ))
    prev_t[$pid]=$ticks; prev_w[$pid]=$now
  done
  for g in "${!rss_sum[@]}"; do
    echo "${g} ${cpu_sum[$g]:-0} ${rss_sum[$g]} ${ncpu} ${sysbusy}" >> "$out"
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
    echo "laptop load: not sampled"
    return
  fi
  awk '
    { g=$1
      if ($2+0 > pc[g]) pc[g]=$2+0
      if ($3+0 > pr[g]) pr[g]=$3+0
      s[g]+=$2+0; n[g]++
      ncpu=$4
      if ($5+0 > peaksys) peaksys=$5+0
      syssum+=$5+0; sysn++
    }
    END {
      printf "laptop load (%d cores, sampled every 2s):\n", ncpu
      for (g in pc)
        printf "  %-8s peak %6.1f%% of one core (%4.1f%% of the machine), mean %5.1f%%, peak RSS %5.0f MiB\n",
               g, pc[g], pc[g]/ncpu, s[g]/n[g], pr[g]/1024
      printf "  whole machine: peak %.1f%% busy, mean %.1f%% busy\n", peaksys, (sysn ? syssum/sysn : 0)
    }' "$SAMPLER_FILE"
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

# ══════════════════════════════════════════════════════════════════════════════
# Element-located UI driving, and the logcat DECISION oracle
# ══════════════════════════════════════════════════════════════════════════════
#
# WHY THIS IS NOT MAESTRO — read before "modernising" any of it.
#
# Maestro (and every other UiAutomator-based driver) connects a `UiAutomation` session, and
# Android SUPPRESSES ALL OTHER ACCESSIBILITY SERVICES for as long as one is connected unless
# the client passes `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, which Maestro's driver does
# not. Nudge IS an accessibility service. Measured on the bench, unambiguously:
#
#   during a Maestro session:  `Bound services:{}` · YouTube opens instantly ·
#                              ZERO evaluation lines in logcat · no block
#   after it exits:            service bound · YouTube blocked for 5s · 6 evaluation lines
#
# So a Maestro flow cannot observe Nudge blocking ANYTHING: it disables the feature under
# test. It fails as a flaky-looking assertion, which is presumably why this harness did not
# already exist. `.maestro/nudge-setup.yaml` is kept because it drives only Nudge's OWN UI
# (onboarding, Settings, the backup import) where no blocking is involved.
#
# A BARE `uiautomator dump` is a different matter and was measured too: the session is
# short-lived, `Bound services` stays populated across repeated dumps, and a block already on
# screen completes and grants passthrough normally. So reading the tree is safe; holding a
# driver session open is not.
#
# The oracle is therefore stronger than a text assertion would have been:
#   · `dumpsys activity activities` → which activity is in front (no a11y involved at all)
#   · logcat, e.g. `block package=… reason=delay_rule delaySeconds=5` from BlockEngine →
#     WHICH RULE fired, not merely that something appeared
#   · `uiautomator dump` → the on-screen copy, and the tap targets
# Taps are `input tap` at coordinates DERIVED AT RUNTIME from the dumped node's bounds —
# element-located, never hardcoded geometry.

ui_dump() { # prints the uiautomator XML of whatever is on screen
  ash uiautomator dump /sdcard/nudge-qa-dump.xml >/dev/null 2>&1
  ash cat /sdcard/nudge-qa-dump.xml 2>/dev/null | tr -d '\r'
}

# Writes the node-locator helper once. Python because an XML attribute soup is exactly what
# regex-in-shell gets wrong, and python3 is a hard dependency of this laptop anyway.
write_locator() {
  cat >"${WORK}/locate.py" <<'PYEOF'
"""Print "<cx> <cy>" for the first node whose text or content-desc EXACTLY equals the
needle, else nothing. Exact match on purpose: "Delay" must not select "Delay Duration",
and "5s" must not select "15s"."""
import re
import sys

xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
needle = sys.argv[2]
for attrs in re.findall(r"<node([^>]*)>", xml):
    def attr(name):
        m = re.search(r'\b%s="([^"]*)"' % name, attrs)
        return m.group(1) if m else ""
    if needle not in (attr("text"), attr("content-desc")):
        continue
    b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attr("bounds") or "")
    if not b:
        continue
    x1, y1, x2, y2 = (int(g) for g in b.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
PYEOF
}

UI_XML=""
ui_snapshot() { UI_XML="${WORK}/ui.xml"; ui_dump >"$UI_XML"; }

# Is this exact string on screen right now? (Uses the last snapshot.)
ui_has() { grep -qF "\"$1\"" "$UI_XML" 2>/dev/null; }

ui_wait_text() { # text timeout_secs
  local needle="$1" timeout="${2:-15}" i
  for ((i = 0; i < timeout; i++)); do
    ui_snapshot
    ui_has "$needle" && return 0
    sleep 1
  done
  return 1
}

ui_tap_text() { # text [timeout_secs] — locate and tap in ONE pass, retrying until timeout
  #
  # Locating and tapping must share ONE snapshot. The first version waited for the text with
  # `ui_wait_text` and then re-dumped inside the tap — two dumps, ~2-3s apart — and against a
  # 5-second delay overlay the target was routinely gone by the second one ("cannot tap 'I
  # changed my mind': not on screen" on a screen where it had just been seen). Tapping the
  # coordinates from the snapshot that found the node removes that race.
  local needle="$1" timeout="${2:-15}" xy i
  for ((i = 0; i < timeout; i++)); do
    ui_snapshot
    xy="$(python3 "${WORK}/locate.py" "$UI_XML" "$needle")"
    if [[ -n "$xy" ]]; then
      # shellcheck disable=SC2086
      ash input tap $xy >/dev/null 2>&1
      sleep 1
      return 0
    fi
    sleep 1
  done
  fail "cannot tap '${needle}': not on screen within ${timeout}s"
  return 1
}

go_home() {
  ash input keyevent KEYCODE_HOME >/dev/null 2>&1
  sleep 2
}

wait_fg() { # package timeout_secs
  local want="$1" timeout="${2:-20}" i
  for ((i = 0; i < timeout; i++)); do
    [[ "$(foreground_package)" == "$want" ]] && return 0
    sleep 1
  done
  return 1
}

shot() { # name — a screenshot straight into the album, no Maestro involved
  ash screencap -p /sdcard/nudge-qa-shot.png >/dev/null 2>&1
  adbs pull /sdcard/nudge-qa-shot.png "${ALBUM}/$1.png" >/dev/null 2>&1
}

scroll_to_text() { # text max_swipes — swipe up until the text is on screen
  local needle="$1" tries="${2:-6}" i
  for ((i = 0; i < tries; i++)); do
    ui_snapshot
    ui_has "$needle" && return 0
    ash input swipe 540 1500 540 700 300 >/dev/null 2>&1
    sleep 1
  done
  ui_snapshot
  ui_has "$needle"
}

# Puts Nudge on its DASHBOARD, wherever it happened to be.
#
# Nudge is a single-activity Compose app, so a plain launch resumes whatever screen it last
# showed — which no case may assume (a step that depends on where its predecessor stopped is
# a latent order dependency). `nudge.nav_route` is the app's own widget deep-link extra
# (ui/widget/WidgetDeepLink.kt) and "home" is in its allowlist, so this is a supported entry
# point rather than a test-only back door.
nudge_route_home() {
  ash am start -n "${APP_ID}/${NAMESPACE}.MainActivity" \
    --es nudge.nav_route home >/dev/null 2>&1
  sleep 3
}

# Count matches in the logcat capture started by start_logcat.
# NOT `grep -c … || echo 0`: grep -c already prints 0 when there is no match AND exits 1, so
# the fallback appends a SECOND zero and every arithmetic comparison downstream dies with
# `((: 0\n0 > 0: syntax error`.
log_count() {
  local n
  n="$(grep -cF "$1" "$LOGCAT_FILE" 2>/dev/null)"
  printf '%s' "${n:-0}"
}

log_count_re() { # same, for a pattern that needs a regex
  local n
  n="$(grep -cE "$1" "$LOGCAT_FILE" 2>/dev/null)"
  printf '%s' "${n:-0}"
}

# Opens Nudge's dashboard. Deliberately does NOT force-stop it first.
#
# `am force-stop dev.astraedus.nudge` leaves the accessibility service UNBOUND and Android
# does not rebind it — measured on the bench: after a force-stop, a YouTube launch produced
# no evaluation at all and `Bound services:{}` stayed empty until the grant was re-applied.
# So a force-stop here would silently disarm every later case. The dashboard's ViewModels
# re-collect on resume, so a plain launch already shows current data.
launch_nudge_home() {
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
  ash dumpsys package "$APP_ID" 2>/dev/null | tr -d '\r' | grep 'WatchdogDebugReceiver' >/dev/null
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

push_fixture() {
  adbs push "${REPO_ROOT}/.maestro/fixtures/rules.json" "$FIXTURE_DEVICE_PATH" >/dev/null 2>&1 ||
    { fail "could not push the rule fixture to the device"; return 1; }
  ash am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://${FIXTURE_DEVICE_PATH}" >/dev/null 2>&1
  info "fixture pushed: ${FIXTURE_DEVICE_PATH}"
}

case_setup() {
  push_fixture || return 1
  info "pm clear — every case starts from a first-run install."
  ash pm clear "$APP_ID" >/dev/null 2>&1
  sleep 2
  ensure_grants || return 1
  ash am force-stop "$YOUTUBE_PKG" >/dev/null 2>&1
  ash am force-stop "$LIMIT_PKG" >/dev/null 2>&1
  run_flow .maestro/nudge-setup.yaml || return 1
  # The first launch after `pm clear` is what prunes the accessibility grant (see
  # `ensure_grants`), and that launch happens INSIDE the flow above — so the grant is
  # re-established here, after it, rather than merely asserted. Blocking is dead without
  # it, and every later case would fail for the wrong reason.
  ensure_grants || return 1
  CASE_NOTE="onboarding walked, 2 fixture rules imported, debug logging on"
  return 0
}

foreground_package() {
  ash dumpsys activity activities 2>/dev/null | tr -d '\r' |
    sed -n 's/.*mResumedActivity.*u0 \([^/]*\)\/.*/\1/p' | head -1
}


# ── delay-block: a DELAY rule really gates the app, and really lets it through ──
#
# Always starts from HOME. A completed countdown GRANTS PASSTHROUGH, and that grant survives
# a force-stop-and-relaunch — the service logs `skip evaluation … reason=passthrough` and the
# app opens free. Only leaving to the launcher revokes it (that is issue #58's subject). A
# case that expects a block without going Home first asserts on its predecessor's leftovers.
case_delay_block() {
  local rc=0
  go_home
  wait_fg "$LAUNCHER_PKG" 10 || { fail "Home did not reach the launcher"; return 1; }
  start_logcat
  ash am force-stop "$YOUTUBE_PKG" >/dev/null 2>&1
  sleep 1
  ash monkey -p "$YOUTUBE_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

  # The decision, from BlockEngine's own log line: this proves WHICH RULE fired, where an
  # on-screen text assertion would only prove that something appeared.
  local i=0 decided=0
  for ((i = 0; i < 20; i++)); do
    if (( $(log_count "block package=${YOUTUBE_PKG} reason=delay_rule delaySeconds=5") > 0 )); then
      decided=1; break
    fi
    sleep 1
  done
  if (( decided == 0 )); then
    fail "no 'reason=delay_rule delaySeconds=5' decision for ${YOUTUBE_PKG} — the app was not gated"
    stop_logcat; return 1
  fi
  info "BlockEngine: delay_rule delaySeconds=5"

  # And the overlay the user actually sees. "I changed my mind" is unique to the delay
  # screen; the hard-block screen says "Go Back" instead.
  if ui_wait_text "I changed my mind" 8; then
    shot 10-delay-overlay
    ui_has "YouTube" || { fail "the delay overlay does not name the app"; rc=1; }
  else
    fail "the delay overlay never showed 'I changed my mind'"
    rc=1
  fi

  # Wait the countdown out. The overlay disappearing is the completion signal; a wall-clock
  # sleep would be the wrong model, since DelayContent only ticks while it is on screen (#8).
  if wait_fg "$YOUTUBE_PKG" 30; then
    shot 11-youtube-open
    info "countdown completed, ${YOUTUBE_PKG} in front"
  else
    fail "the countdown finished but ${YOUTUBE_PKG} never came to the front (got '$(foreground_package)')"
    rc=1
  fi
  stop_logcat
  cp -f "$LOGCAT_FILE" "${ALBUM}/logcat-delay-block.txt" 2>/dev/null || true
  CASE_NOTE="delay_rule delaySeconds=5 fired; overlay shown; app opened after the countdown"
  return $rc
}

# ── home-reopen (#58): a trip HOME must revoke the passthrough the countdown granted ──
#
# The bug was a RACE, so one green trial proves nothing — hence HOME_REOPEN_TRIALS (5) and
# the per-trial log assertions rather than a single end-of-run grep.
case_home_reopen() {
  local rc=0 trial blocks_before blocks_after
  start_logcat
  for ((trial = 1; trial <= HOME_REOPEN_TRIALS; trial++)); do
    info "trial ${trial}/${HOME_REOPEN_TRIALS}"
    go_home
    wait_fg "$LAUNCHER_PKG" 10 || { fail "trial ${trial}: Home did not reach the launcher"; rc=1; break; }

    # 1. open it and let the countdown through
    blocks_before="$(log_count "block package=${YOUTUBE_PKG} reason=delay_rule")"
    ash monkey -p "$YOUTUBE_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    local i ok=0
    for ((i = 0; i < 20; i++)); do
      (( $(log_count "block package=${YOUTUBE_PKG} reason=delay_rule") > blocks_before )) && { ok=1; break; }
      sleep 1
    done
    (( ok )) || { fail "trial ${trial}: the first launch was not blocked"; rc=1; break; }
    wait_fg "$YOUTUBE_PKG" 30 || { fail "trial ${trial}: the countdown never let YouTube through"; rc=1; break; }

    # 2. use the app, then leave
    sleep 5
    local homes_before
    homes_before="$(log_count "sitting ended package=${YOUTUBE_PKG} cause=WENT_HOME")"
    go_home
    wait_fg "$LAUNCHER_PKG" 10 || { fail "trial ${trial}: Home did not reach the launcher"; rc=1; break; }
    # The revocation itself, per trial. Asserted HERE rather than counted at the end so a
    # failure names the trial that broke.
    local j revoked=0
    for ((j = 0; j < 10; j++)); do
      (( $(log_count "sitting ended package=${YOUTUBE_PKG} cause=WENT_HOME") > homes_before )) && { revoked=1; break; }
      sleep 1
    done
    (( revoked )) || { fail "trial ${trial}: no 'sitting ended … cause=WENT_HOME' — the grant was not revoked"; rc=1; break; }

    # 3. re-open it. THIS is the assertion the issue is about.
    blocks_after="$(log_count "block package=${YOUTUBE_PKG} reason=delay_rule")"
    ash monkey -p "$YOUTUBE_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    ok=0
    for ((i = 0; i < 20; i++)); do
      (( $(log_count "block package=${YOUTUBE_PKG} reason=delay_rule") > blocks_after )) && { ok=1; break; }
      sleep 1
    done
    if (( ok == 0 )); then
      fail "trial ${trial}: re-opening ${YOUTUBE_PKG} after Home produced NO block decision — issue #58 regressed"
      shot "2${trial}-reopen-no-decision"
      rc=1; break
    fi
    # A DECISION IS NOT A BLOCK. `BlockEngine` logging `reason=delay_rule` only means the
    # verdict was reached; the overlay launch can still be refused afterwards with
    # `DROP_FOREGROUND_MOVED`, and then the user is sitting in the app unblocked with a
    # perfectly healthy-looking decision in the log. That ambiguity is what cost the v1.12.0
    # cycle, so the user-visible half is asserted separately.
    if ui_wait_text "I changed my mind" 10; then
      shot "2${trial}-trial-reblocked"
    else
      fail "trial ${trial}: the block was DECIDED but no overlay reached the screen — the launch was dropped and never redeemed"
      shot "2${trial}-reopen-overlay-missing"
      rc=1; break
    fi
    # No cleanup tap: the next trial opens with `go_home`, which is what revokes the grant.
    # Tapping "I changed my mind" here raced the 5s countdown and only produced noise.
  done

  sleep 2
  stop_logcat
  cp -f "$LOGCAT_FILE" "${ALBUM}/logcat-home-reopen.txt" 2>/dev/null || true

  local went_home dropped redeemed
  went_home="$(log_count "sitting ended package=${YOUTUBE_PKG} cause=WENT_HOME")"
  dropped="$(log_count_re "block overlay launch dropped target=${YOUTUBE_PKG}.*reason=DROP_FOREGROUND_MOVED")"
  redeemed="$(log_count "re-evaluating a dropped block target=${YOUTUBE_PKG}")"
  info "logcat totals: WENT_HOME=${went_home} DROP_FOREGROUND_MOVED=${dropped} redeemed=${redeemed}"
  if (( went_home < HOME_REOPEN_TRIALS )); then
    fail "only ${went_home}/${HOME_REOPEN_TRIALS} trials logged 'cause=WENT_HOME'"
    rc=1
  fi
  # dropped/redeemed are DIAGNOSTICS, not a gate, and `dropped <= redeemed` would be the wrong
  # invariant: a deferral is correctly DISCARDED when the user genuinely leaves, so a run that
  # presses Home as often as this one does will always show more drops than redemptions. The
  # thing that actually matters — did a dropped launch leave the user in the app unblocked — is
  # asserted per trial above, on the overlay reaching the screen.
  if (( dropped > redeemed )); then
    info "note: ${dropped} drops vs ${redeemed} redemptions. Expected here (every Home press discards a deferral); the per-trial overlay assertion is the gate."
  fi
  CASE_NOTE="${HOME_REOPEN_TRIALS} trials · WENT_HOME=${went_home} dropped=${dropped} redeemed=${redeemed}"
  return $rc
}

# ── walkaway-count: 3 declined launches must move BOTH dashboard counters by exactly +3 ──
#
# "Blocked" counts confrontations SHOWN and "Walked Away" the ones declined, so a decline
# moves both exactly once (HomeViewModel's KDoc: the raw `wasBlocked` count used to
# double-count a walk-away).
case_walkaway_count() {
  local rc=0 i
  nudge_route_home
  local before after b_before b_after w_before w_after
  before="$(read_blocked_counts)" || { fail "could not read the week summary on the dashboard"; return 1; }
  b_before="${before% *}"; w_before="${before#* }"
  info "before: blocked=${b_before} walkedAway=${w_before}"

  for ((i = 1; i <= 3; i++)); do
    go_home
    wait_fg "$LAUNCHER_PKG" 10 || { fail "decline ${i}: Home did not reach the launcher"; return 1; }
    ash monkey -p "$YOUTUBE_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    # Tap FIRST, screenshot after. The overlay only lives for the 5s countdown, so spending a
    # `screencap` on the critical path is what loses the race — and the tap succeeding is
    # itself proof the overlay was there.
    if ! ui_tap_text "I changed my mind" 20; then
      fail "decline ${i}: no delay overlay to decline"
      shot "3${i}-walkaway-missing"
      return 1
    fi
    shot "3${i}-walkaway-declined"
    sleep 2
    # Declining must LEAVE the app, not just dismiss the overlay on top of it — the
    # "'I changed my mind' can leave the user inside the blocked app" report.
    if ! wait_fg "$LAUNCHER_PKG" 10; then
      fail "decline ${i}: the launcher is not in front (got '$(foreground_package)') — the decline did not leave the app"
      return 1
    fi
  done

  nudge_route_home
  after="$(read_blocked_counts)" || { fail "could not re-read the week summary"; return 1; }
  b_after="${after% *}"; w_after="${after#* }"
  info "after:  blocked=${b_after} walkedAway=${w_after}"
  shot 34-dashboard-after

  CASE_NOTE="blocked ${b_before}->${b_after}, walkedAway ${w_before}->${w_after}"
  local db=$(( b_after - b_before )) dw=$(( w_after - w_before ))
  (( db == 3 )) || { fail "Blocked moved by ${db}, expected +3"; rc=1; }
  (( dw == 3 )) || { fail "Walked away moved by ${dw}, expected +3"; rc=1; }
  return $rc
}

# ── daily-limit-refresh (#50): raising the limit must not leave the stale screen behind ──
#
# The fixture gives Calculator a 1-minute budget and mode NONE ("Not blocked"), so the app
# opens freely and the limit is the only gate. The block arrives unattended: the service runs
# a 30s foreground-time clock (`FOREGROUND_TICK_MS`) whose `onTimeLimitExceeded` launches the
# overlay. We wait for that real trigger rather than provoking a fake one.
case_daily_limit_refresh() {
  local rc=0
  go_home
  start_logcat
  ash am force-stop "$LIMIT_PKG" >/dev/null 2>&1
  sleep 1
  ash monkey -p "$LIMIT_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  shot 40-calculator-open

  info "burning the 1-minute budget (up to ${LIMIT_WAIT_SECS}s; the clock ticks every 30s) …"
  local i limited=0
  for ((i = 0; i < LIMIT_WAIT_SECS; i += 5)); do
    if (( $(log_count "block package=${LIMIT_PKG} reason=time_budget_exceeded") > 0 )); then
      limited=1; break
    fi
    sleep 5
  done
  if (( limited == 0 )); then
    fail "the 1-minute daily limit never fired for ${LIMIT_PKG} within ${LIMIT_WAIT_SECS}s"
    stop_logcat; return 1
  fi
  info "BlockEngine: time_budget_exceeded"

  # "Daily limit reached" is its OWN screen, not a plain hard block: the service passes that
  # as the rule name and HardBlockContent shows the line only when the remaining budget has
  # actually reached zero. So it is the right string to assert, and to assert the absence of.
  if ui_wait_text "Daily limit reached" 15; then
    shot 41-daily-limit-reached
  else
    fail "the limit fired but the 'Daily limit reached' screen never appeared"
    rc=1
  fi
  ui_tap_text "Go Back" || rc=1
  sleep 2

  # Raise the limit 1m -> 2h. Chip taps only: the "Daily Time Limit" switch is already ON
  # (the fixture set a limit), so its chips are on screen and no Compose Switch — which has
  # no text node of its own and cannot be reached by an element selector — is needed.
  nudge_route_home
  ui_wait_text "Manage Apps" 5 || scroll_to_text "Manage Apps" 6
  ui_tap_text "Manage Apps" || return 1
  ui_wait_text "Search apps..." 10 || { fail "the app list did not open"; return 1; }
  ui_tap_text "Search apps..." || return 1
  # A PREFIX, not the full name: the search field's own text is a node too, so typing
  # "Calculator" makes the field itself the first match for the app name.
  ash input text "Calcul" >/dev/null 2>&1
  sleep 2
  ash input keyevent KEYCODE_BACK >/dev/null 2>&1   # dismiss the IME; its suggestion strip
  sleep 1                                            # sits over the result list
  ui_tap_text "Calculator" || return 1
  ui_wait_text "Daily Time Limit" 10 || { fail "the app config screen did not open"; return 1; }
  ui_has "1m" || warn "the daily-limit chip does not read '1m' (fixture drift?)"
  ui_tap_text "2h" || return 1
  shot 42-limit-raised
  ui_tap_text "Save" || return 1
  sleep 2

  # Cold-launch Calculator. The stale screen must NOT be back.
  go_home
  ash am force-stop "$LIMIT_PKG" >/dev/null 2>&1
  sleep 1
  ash monkey -p "$LIMIT_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  ui_snapshot
  shot 43-calculator-not-stale
  if ui_has "Daily limit reached"; then
    fail "after raising the limit to 2h, the stale 'Daily limit reached' screen came back — issue #50 regressed"
    rc=1
  fi
  local fg
  fg="$(foreground_package)"
  if [[ "$fg" != "$LIMIT_PKG" ]]; then
    fail "after raising the limit, ${LIMIT_PKG} is not in front (got '${fg}') — something re-blocked it"
    rc=1
  fi
  stop_logcat
  cp -f "$LOGCAT_FILE" "${ALBUM}/logcat-daily-limit.txt" 2>/dev/null || true
  CASE_NOTE="time_budget_exceeded fired, limit raised 1m->2h, cold launch clean (fg=${fg})"
  return $rc
}

case_notif_idle() {
  local rc=0
  local r0 r1 c0 u0 c1 u1
  r0="$(nudge_notif_records)"; c0="${r0% *}"; u0="${r0#* }"
  [[ "$u0" == "0" ]] && { fail "no nudge notification record found (id=1, channel=nudge_monitor)"; return 1; }
  info "baseline: records=${c0} mUpdateTimeMs=${u0}; idling ${NOTIF_IDLE_SECS}s with the screen off"
  # `svc power stayon true` — which this runner sets so the other cases can drive the UI —
  # keeps the screen awake while charging and would win against KEYCODE_SLEEP, leaving this
  # case measuring an AWAKE phone and quietly proving nothing. Drop it for the idle window,
  # verify the screen actually went off, and put it back afterwards.
  ash svc power stayon false >/dev/null 2>&1
  sleep 1
  ash input keyevent KEYCODE_SLEEP >/dev/null 2>&1
  sleep 3
  local wake
  wake="$(ash dumpsys power 2>/dev/null | tr -d '\r' | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)"
  if [[ "$wake" == "Awake" ]]; then
    warn "the screen did not go off (mWakefulness=${wake}); #63 is about an IDLE phone, so this reading is weaker than it looks"
  else
    info "screen off (mWakefulness=${wake})"
  fi
  sleep "$NOTIF_IDLE_SECS"
  "${HOME}/bin/astra-pixel-unlock.sh" >/dev/null 2>&1 || true
  ash svc power stayon true >/dev/null 2>&1
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
  # ONE post on the change, then quiet again — that pair is the invariant, and checking only
  # the first half would pass a build that re-posts on a timer the moment anything happens.
  local saved="$SAVED_A11Y_SERVICES" mid mu settle su back bu
  ash settings put secure enabled_accessibility_services "" >/dev/null 2>&1
  sleep 10
  mid="$(nudge_notif_records)"; mu="${mid#* }"
  if [[ "$mu" == "$u1" ]]; then
    fail "the ongoing notification did NOT update after the accessibility grant was revoked — it is claiming Nudge is active while it is not"
    rc=1
  else
    info "grant revoked -> notification updated (${u1} -> ${mu}), as designed"
    sleep 12
    settle="$(nudge_notif_records)"; su="${settle#* }"
    if [[ "$su" != "$mu" ]]; then
      fail "the notification kept re-posting after the state settled (${mu} -> ${su}) — that is #63"
      rc=1
    else
      info "and then went quiet, as designed"
    fi
    mu="$su"
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
  CASE_NOTE="idle ${NOTIF_IDLE_SECS}s: mUpdateTimeMs unchanged; then posted on grant revoke, went quiet, posted on restore"
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
       grep 'nudge_protection_alerts' >/dev/null; then
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
     grep 'NudgeMonitorService' >/dev/null; then
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
  # Every case needs a bound accessibility service as a PRECONDITION, not as something
  # `setup` arranged once — `notif-idle` deliberately revokes it mid-case, and the OS can
  # prune it on its own. Checked (and repaired) per case so a failure lands on the case
  # that actually broke, not on the next one.
  if [[ "$name" != "setup" ]] && ! a11y_is_bound; then
    warn "accessibility service was not bound entering '${name}' — repairing"
    ensure_grants || { record "$name" FAIL 0 "precondition: accessibility service would not bind"; return 0; }
  fi
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
  write_locator
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
