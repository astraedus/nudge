#!/usr/bin/env bash
#
# rebind-revokes-grant-probe.sh -- does an accessibility-service REBIND re-block a user mid-session?
#
# Issue #28 FAIL 1: a second delay overlay fired on an in-app tab tap while the user held a valid
# grant. It did not reproduce from the tab tap alone (two scripted runs, one with a 150s dwell:
# one block, no picture-in-picture, no sitting end). The remaining suspect is a service REBIND.
#
# `onServiceConnected` calls `PassthroughManager.resetSitting()`, which drops the grant. The
# reasoning was "a bind is the start of observation, so an inherited away-clock is meaningless" --
# but `docs/BACKLOG.md` records that this service churns and reconnects on this very device under
# memory pressure, which turns every rebind into a spurious re-block of a user who never left. That
# is the #28 bug class itself, reintroduced by its own fix.
#
# This forces the rebind deterministically (toggling the setting, no gestures) instead of waiting
# for memory pressure, so the question is answered in thirty seconds rather than by luck.
#
# USAGE: tools/qa/rebind-revokes-grant-probe.sh
#   Env: DEVICE=<serial>  HOST=<package under a DELAY rule, default YouTube>

set -euo pipefail

DEVICE="${DEVICE:-192.168.1.68:5555}"
HOST="${HOST:-com.google.android.youtube}"
SVC=dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService
RAW=/tmp/nudge-rebind-probe.log

adb() { command adb -s "$DEVICE" "$@"; }

enable_service() {
  adb shell settings put secure enabled_accessibility_services "$SVC" >/dev/null 2>&1
  adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
}

adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
sleep 2
adb shell am force-stop "$HOST" >/dev/null 2>&1
sleep 1
adb logcat -c || true

echo "-- earn the grant (launch, let the DELAY auto-complete) --"
adb shell monkey -p "$HOST" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12

echo "-- force an accessibility REBIND, touching nothing else --"
adb shell settings put secure enabled_accessibility_services "" >/dev/null 2>&1
sleep 2
enable_service
sleep 4

echo "-- bring the SAME app forward again; the user never left it --"
adb shell monkey -p "$HOST" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8

adb logcat -d > "$RAW"

echo
echo "-- narrative --"
grep -E 'NudgeLog' "$RAW" \
  | sed 's/^\([0-9-]* [0-9:.]*\).*NudgeLog[^ ]*: /\1  /' \
  | grep -Ev 'evaluate package=|feature detection|allow package|counter cache|not counted' || true

echo
echo "VERDICT: blocks of $HOST in this run = $(grep -c "handling block package=$HOST" "$RAW" || true)"
echo "         (1 = the grant survived the rebind, 2 = the rebind re-blocked a user who never left)"
echo "  raw log: $RAW"
