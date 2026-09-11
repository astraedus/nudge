#!/usr/bin/env bash
#
# issue28-device-verify.sh -- the two sequences device QA failed on, run back to back.
#
# SEQUENCE A (FAIL 2, the caption): YouTube cold-launches onto Shorts, detection sets "shorts", the
#   user taps the in-app Home tab and uses the feed. The caption on the feed must be the GENERIC
#   items/taps label, never "shorts".
#
# SEQUENCE B (FAIL 1, the re-block): a grant is earned, the accessibility service is forced to
#   rebind, and the same app is brought forward again. There must be exactly ONE block -- a rebind is
#   not evidence the user went anywhere.
#
# Drives YouTube only. Never Instagram/TikTok/Threads (tools/qa/ig-walkthrough-capture.sh).
#
# USAGE: tools/qa/issue28-device-verify.sh     Env: DEVICE=<serial>

set -euo pipefail

DEVICE="${DEVICE:-192.168.1.68:5555}"
YT=com.google.android.youtube
SVC=dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService

adb() { command adb -s "$DEVICE" "$@"; }
home() { adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; }
launch() { adb shell monkey -p "$YT" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
nudge_lines() {
  grep -E 'NudgeLog' "$1" | sed 's/^\([0-9-]* [0-9:.]*\).*NudgeLog[^ ]*: /\1  /' \
    | grep -Ev 'skip evaluation|evaluate package=|counter cache|not counted' || true
}

# Install first, under the same lock hold: a run against a stale APK is worse than no run, and
# `adb install -r` DROPS the accessibility grant, so re-enabling it is part of installing, not an
# afterthought (docs/architecture/accessibility-event-pipeline.md).
APK="${APK:-/tmp/nudge-28/app/build/outputs/apk/debug/app-debug.apk}"
if [ -f "$APK" ]; then
  echo "-- installing $(basename "$APK") --"
  adb install -r "$APK" 2>&1 | tail -1
  adb shell settings put secure enabled_accessibility_services "$SVC" >/dev/null 2>&1
  adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
  sleep 3
  BOUND=$(adb shell dumpsys accessibility 2>/dev/null | grep -c "Bound services:{Service" || true)
  echo "   accessibility bound: $BOUND (must be 1, or the run exercises nothing)"
  [ "$BOUND" = "1" ] || { echo "ABORT: service not bound"; exit 1; }
fi

echo "############ SEQUENCE A -- caption must not follow Shorts onto the feed ############"
home; sleep 2
adb shell am force-stop "$YT" >/dev/null 2>&1; sleep 1
adb logcat -c || true
launch; sleep 12
echo "-- on Shorts; swiping --"
for _ in 1 2; do adb shell input swipe 540 1500 540 700 400; sleep 3; done
echo "-- tapping the in-app Home tab, then using the feed --"
adb shell input tap 108 2010; sleep 5
for _ in 1 2 3; do adb shell input swipe 540 1500 540 700 500; sleep 3; done
sleep 2
adb logcat -d > /tmp/nudge-verify-a.log
adb shell screencap -p /sdcard/vA.png >/dev/null 2>&1
adb pull /sdcard/vA.png /home/astraedus/Pictures/screenshots/nudge-issue28-verify-feed-caption.png >/dev/null 2>&1 || true

nudge_lines /tmp/nudge-verify-a.log
echo
CAPTIONS=$(grep -oE 'counter overlay (shown label=|label updated to )[a-z]+' /tmp/nudge-verify-a.log \
  | sed -E 's/.*(label=|to )//' | tr '\n' ' ')
LAST_CAPTION=$(echo "$CAPTIONS" | awk '{print $NF}')
COUNTED=$(grep -c 'interaction counted' /tmp/nudge-verify-a.log || true)

echo "A RESULT: captions over time = [$CAPTIONS]"
echo "          interactions counted = $COUNTED"
# Two different failures, reported differently on purpose. A run where NOTHING counted cannot say
# anything about the caption, and calling that a caption FAIL would send the next person hunting the
# wrong bug -- the same "one error string, two causes" trap this repo has already paid for once.
if [ "$COUNTED" -eq 0 ]; then
  echo "          INCONCLUSIVE: nothing was counted on the feed, so the caption was never asked to"
  echo "                        update. Re-run; check the swipes landed on a scrollable feed."
elif [ "$LAST_CAPTION" = "shorts" ] || [ "$LAST_CAPTION" = "reels" ] || [ "$LAST_CAPTION" = "videos" ]; then
  echo "          FAIL: the last caption is '$LAST_CAPTION' -- a feature caption survived onto the feed"
else
  echo "          PASS: the last caption is '$LAST_CAPTION' (generic), after $COUNTED counted interactions"
fi
echo "          screenshot: ~/Pictures/screenshots/nudge-issue28-verify-feed-caption.png"

echo
echo "############ SEQUENCE B -- a rebind must not re-block ############"
home; sleep 2
adb shell am force-stop "$YT" >/dev/null 2>&1; sleep 1
adb logcat -c || true
launch; sleep 12
echo "-- forcing an accessibility rebind (settings toggle, no gestures) --"
adb shell settings put secure enabled_accessibility_services "" >/dev/null 2>&1
sleep 2
adb shell settings put secure enabled_accessibility_services "$SVC" >/dev/null 2>&1
adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
sleep 4
echo "-- bringing the SAME app forward; the user never left --"
launch; sleep 8
adb logcat -d > /tmp/nudge-verify-b.log

nudge_lines /tmp/nudge-verify-b.log
echo
echo "B RESULT: blocks of YouTube = $(grep -c "handling block package=$YT" /tmp/nudge-verify-b.log || true)"
echo "          PASS if 1, FAIL if 2+"
