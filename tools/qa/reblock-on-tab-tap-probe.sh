#!/usr/bin/env bash
#
# reblock-on-tab-tap-probe.sh -- issue #28 FAIL 1, reproduced scripted.
#
# Reported by device QA 2026-09-12: YouTube cold-launches into Shorts and is blocked; the user waits
# the delay out and earns a grant; two slow swipes on Shorts produce ZERO counter activity; tapping
# YouTube's in-app Home tab produces a SECOND delay overlay in the same session, while the grant
# should still be live.
#
# The hypothesis this exists to test: the block overlay backgrounds YouTube, YouTube enters
# picture-in-picture, issue #19's PiP gate then classifies it `PipOnly` and swallows every one of its
# events (which would explain the silent Shorts swipes), and something in that window ends the
# sitting -- so the first event that finally arrives as a real app window re-blocks.
#
# WHAT IT PRINTS is the whole point: the ordered Nudge narrative plus the PiP set over time, so the
# question "was youtube pipOnly, and when did the grant die" is answered by the device rather than by
# reading code.
#
# USAGE: tools/qa/reblock-on-tab-tap-probe.sh
#   Env: DEVICE=<serial>
#
# Requires: a DELAY rule on YouTube with the counter on, and debug logging (a debug build).
# Drives YouTube only -- never Instagram/TikTok/Threads (tools/qa/ig-walkthrough-capture.sh).

set -euo pipefail

DEVICE="${DEVICE:-192.168.1.68:5555}"
YT=com.google.android.youtube
RAW=/tmp/nudge-reblock-probe.log

adb() { command adb -s "$DEVICE" "$@"; }

echo "== FAIL 1 probe: does a tab tap re-block a held grant? =="

adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
sleep 2
# Force-stop first: relaunching a backgrounded YouTube is itself a picture-in-picture trigger, and a
# run that starts from an already-PiP state tells us nothing about whether the BLOCK caused it.
adb shell am force-stop "$YT" >/dev/null 2>&1
sleep 1
adb logcat -c || true

echo "-- cold launch (expect ONE block, then the delay auto-completes into a grant) --"
adb shell monkey -p "$YT" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12

echo "-- PiP state while the user is 'watching' --"
adb shell dumpsys accessibility 2>/dev/null \
  | grep -iE "pictureInPicture=true|title=" | head -8 || true

echo "-- dwell on Shorts for ${DWELL:-4}s, swiping (the reported run took ~2 minutes) --"
END=$(( $(date +%s) + ${DWELL:-4} ))
while [ "$(date +%s)" -lt "$END" ]; do
  adb shell input swipe 540 1500 540 700 400
  sleep 4
done

echo "-- tap the in-app Home tab (expect NO second block) --"
# YouTube's bottom navigation: Home is the leftmost of five tabs, at ~90% screen height.
adb shell input tap 108 2010
sleep 8

adb logcat -d > "$RAW"

echo
echo "-- Nudge narrative, in order --"
grep -E 'NudgeLog' "$RAW" \
  | sed 's/^\([0-9-]* [0-9:.]*\).*NudgeLog[^ ]*: /\1  /' \
  | grep -Ev 'skip evaluation|evaluate package=' || true

echo
echo "-- how many times was YouTube blocked? (1 = correct, 2+ = FAIL 1) --"
grep -c "handling block package=$YT" "$RAW" || true

echo
echo "-- picture-in-picture set over time --"
grep -E 'picture-in-picture windows changed' "$RAW" \
  | sed 's/^\([0-9-]* [0-9:.]*\).*NudgeLog[^ ]*: /\1  /' || echo "   (never changed)"

echo
echo "-- did any youtube event reach the interaction counter? --"
grep -cE "interaction (counted|not counted) package=$YT" "$RAW" || true

echo
echo "  raw log: $RAW"
