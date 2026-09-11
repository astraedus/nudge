#!/usr/bin/env bash
#
# passive-observe.sh — launch an app, touch NOTHING, and record what Nudge sees.
#
# Issue #28, bug 2: "holding the screen in certain places makes the counter tick continuously", and
# the counter rising on apps the user is not interacting with. The counter's fallback path
# (`InteractionHandler.handleContentChanged`) counts one "tap" per second of
# TYPE_WINDOW_CONTENT_CHANGED for any package outside `InAppDetector.SUPPORTED_PACKAGES` — which is
# a claim about user input made from a signal that has nothing to do with input. An autoplaying
# feed emits content changes forever.
#
# This probe settles that with no input at all, which also means it is safe to run on Instagram and
# TikTok: it never drives them (see the hard rule in tools/qa/ig-walkthrough-capture.sh), it only
# opens them and watches. Zero taps in, any counter movement out is a false count by definition.
#
# USAGE:  tools/qa/passive-observe.sh <package> [seconds]
# Env:    DEVICE=<serial>

set -euo pipefail

PKG="${1:?usage: passive-observe.sh <package> [seconds]}"
SECS="${2:-60}"
DEVICE="${DEVICE:-192.168.1.68:5555}"
RAW="/tmp/nudge-passive-${PKG//./_}.log"

adb() { command adb -s "$DEVICE" "$@"; }

adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
sleep 2
adb logcat -c || true
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "launched $PKG — observing ${SECS}s with ZERO input..."
sleep "$SECS"
adb logcat -d > "$RAW"

echo
echo "-- counter activity (any line here is a count with no user input) --"
grep -E 'NudgeLog' "$RAW" | grep -Ei 'counter|interaction|auto-kick' \
  | sed 's/^\([0-9-]* [0-9:.]*\).*NudgeLog[^ ]*: /\1  /' | tail -25 || echo "   (none)"

echo
echo "-- packages that fired window events while $PKG was in front --"
grep 'NudgeA11yTrace' "$RAW" | sed 's/.*EV //' | grep '^{' \
  | sed -E 's/.*"t":"([A-Z_]+)".*"p":"([^"]+)".*/\1 \2/' | sort | uniq -c | sort -rn

echo
echo "  raw log: $RAW"
