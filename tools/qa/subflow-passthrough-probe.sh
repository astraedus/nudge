#!/usr/bin/env bash
#
# subflow-passthrough-probe.sh — does a completed delay survive an app-launched SUB-FLOW?
#
# Issue #28, bug 1: "on Tinder if I try to open 'Add photos' I get the delay screen again, even if
# I already waited to open the app", and "most frequently after I adjust the volume". The claim
# under test is that ANY foreign package's window event revokes the post-delay passthrough, so
# returning from a picker / share sheet / permission dialog / volume panel re-blocks.
#
# WHY IT DRIVES NOTHING:
#   Every step is `am start`, `monkey -c LAUNCHER` or `cmd media_session` — no taps, swipes or
#   keyevents. That matters twice over: `tools/qa/ig-walkthrough-capture.sh` records the hard rule
#   that automation never drives Instagram/TikTok over adb, and Nudge's DELAY overlay auto-completes
#   on its own timer (`DelayContent`'s LaunchedEffect), so EARNING a passthrough needs no input at
#   all. The excursions are launched as real system intents, which is what the reported sub-flows
#   actually are.
#
# HOST CHOICE MATTERS: pick a host that does NOT enter picture-in-picture when the block overlay
# backgrounds it. YouTube does, and issue #19's PiP gate then swallows every one of its later
# events, so the return leg of the experiment silently records nothing. Google Keep is the
# bench default for exactly this reason (the same choice `ContentChangeAppSwitchTest`'s device
# verification made).
#
# USAGE:
#   tools/qa/subflow-passthrough-probe.sh <case>
#
#     <case> one of:
#       none          no excursion, just leave and come back via Home  (control: SHOULD re-block)
#       picker        ACTION_GET_CONTENT   (the "Add photos" repro)
#       share         ACTION_SEND chooser  (Android 13+ com.android.intentresolver)
#       volume        the system volume panel
#       settings      an app-details Settings excursion
#       permissions   the permission-controller's app-permissions screen
#
#   Env: DEVICE=<serial>  HOST=<package under a DELAY rule, default com.google.android.keep>
#
# OUTPUT: an ordered Nudge decision narrative and a verdict line. Raw log kept for the event stream.

set -euo pipefail

CASE="${1:-picker}"
DEVICE="${DEVICE:-192.168.1.68:5555}"
HOST="${HOST:-com.google.android.keep}"
RAW="/tmp/nudge-subflow-$CASE.log"

adb() { command adb -s "$DEVICE" "$@"; }

go_home() {
  adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
}
launch_host() { adb shell monkey -p "$HOST" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }

excursion() {
  case "$CASE" in
    none)        : ;;
    picker)      adb shell am start -a android.intent.action.GET_CONTENT -t "image/*" >/dev/null 2>&1 || true ;;
    share)       adb shell am start -a android.intent.action.SEND -t "text/plain" -e android.intent.extra.TEXT nudge >/dev/null 2>&1 || true ;;
    volume)      adb shell cmd media_session volume --show --stream 3 --adj raise >/dev/null 2>&1 || true ;;
    settings)    adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$HOST" >/dev/null 2>&1 || true ;;
    permissions) adb shell am start -a android.intent.action.MANAGE_APP_PERMISSIONS -e android.intent.extra.PACKAGE_NAME "$HOST" >/dev/null 2>&1 || true ;;
    *) echo "unknown case '$CASE'" >&2; exit 2 ;;
  esac
}

echo "== case=$CASE host=$HOST device=$DEVICE =="

# Settle to a known state, then capture ONE continuous window. Clearing logcat mid-run is
# unreliable on this device (Android 12 does not always drop every buffer), and a run whose
# narrative is split across two dumps is a run you cannot read in order.
go_home; sleep 2
adb shell am force-stop "$HOST" >/dev/null 2>&1 || true
adb logcat -c || true
sleep 1

echo "-- phase 1: earn the passthrough (launch host, DELAY auto-completes) --"
launch_host; sleep 12

echo "-- phase 2: excursion '$CASE' --"
if [[ "$CASE" == "none" ]]; then go_home; else excursion; fi
sleep 6

echo "-- phase 3: return to $HOST --"
launch_host; sleep 8

adb logcat -d > "$RAW"

echo
echo "-- Nudge decision narrative (in order) --"
grep -E 'NudgeLog' "$RAW" \
  | sed 's/^\([0-9-]* [0-9:.]*\).*NudgeLog[^ ]*: /\1  /' \
  | grep -Ev 'evaluate package=|pip escape not explained' || true

# A re-block on the RETURN leg is the defect. Count only decisions attributed to the host, and only
# after the excursion, so the block that EARNED the passthrough in phase 1 is not double-counted.
RETURN_BLOCKS="$(awk '/phase-marker/{seen=1} /handling block package='"$HOST"'/{n++} END{print n+0}' "$RAW")"
CLEARED_BY="$(grep -oE 'passthrough cleared on app switch package=[a-zA-Z0-9_.]+' "$RAW" | sed 's/.*package=//' | sort -u | tr '\n' ' ')"
TOTAL_HOST_BLOCKS="$(grep -c "handling block package=$HOST" "$RAW" || true)"

echo
echo "VERDICT case=$CASE: host blocks in the whole run = $TOTAL_HOST_BLOCKS (1 = earned only, 2+ = re-blocked)"
echo "                    passthrough cleared by: ${CLEARED_BY:-<nothing>}"
echo "  raw log: $RAW"
