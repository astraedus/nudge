#!/usr/bin/env bash
#
# scroll-capture.sh — drive a known gesture, capture the accessibility event stream it produced,
#                     and print what Nudge's counter made of it.
#
# Issue #28, bug 2: the counter counts EVENT RATE, not user actions. A slow scroll between two
# posts fires six TYPE_VIEW_SCROLLED events and is counted six times; an autoplaying feed fires them
# with no input at all. To fix that we need the ground truth the events actually carry — fromIndex /
# toIndex / itemCount / scrollDeltaY / the source view id — measured on a real device, per gesture,
# against a KNOWN human-expected count.
#
# HARD RULE: never drive Instagram or TikTok from here. See tools/qa/ig-walkthrough-capture.sh —
# automating them over adb risks the device's real logins, and it has no QA carve-out. Use YouTube
# (same ViewPager2 / RecyclerView widgets, same comments-sheet shape) and AOSP list screens, which
# is enough because the counter must decide from GENERIC fields, never from per-app knowledge.
#
# USAGE:
#   tools/qa/scroll-capture.sh <case>
#
#     yt-shorts-5swipes    5 fast vertical swipes on YouTube Shorts   → expect 5
#     yt-feed-slow         one slow drag between two feed items       → expect 1
#     yt-feed-5scrolls     5 normal feed scrolls                      → expect 5
#     yt-hold              a 3s press-and-hold, no movement           → expect 0
#     yt-idle              10s of nothing, app open                   → expect 0
#     settings-list-slow   one slow drag on an AOSP RecyclerView      → expect 1
#
#   Env: DEVICE=<serial>
#
# OUTPUT: a per-source breakdown of the scroll events (which view scrolled, how the indices moved,
# what deltas it reported), plus every counter line Nudge logged. Writes the fixture straight into
# app/src/test/resources/a11y-captures/<case>.jsonl.

set -euo pipefail

CASE="${1:?usage: scroll-capture.sh <case>}"
DEVICE="${DEVICE:-192.168.1.68:5555}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$REPO_ROOT/app/src/test/resources/a11y-captures"
RAW="/tmp/nudge-scroll-$CASE.log"

adb() { command adb -s "$DEVICE" "$@"; }

# Pixel 3: 1080x2160. Gestures are expressed in those coordinates.
CX=540

open_youtube() {
  adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
  sleep 2
  adb shell monkey -p com.google.android.youtube -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  # The DELAY overlay auto-completes on its own timer; wait it out so the capture is of USE, not of
  # the block screen.
  sleep 12
}

open_settings() {
  adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
  sleep 2
  adb shell am start -a android.settings.APPLICATION_SETTINGS >/dev/null 2>&1
  sleep 4
}

gestures() {
  case "$CASE" in
    yt-shorts-5swipes)
      # Shorts is a vertical pager: a fast flick per short.
      for _ in 1 2 3 4 5; do
        adb shell input swipe $CX 1600 $CX 600 200
        sleep 1.5
      done
      ;;
    yt-feed-slow)
      # ONE slow drag, short distance: the exact gesture the report calls "scrolling slowly
      # between two posts", which today counts 5-6.
      adb shell input swipe $CX 1500 $CX 900 2500
      sleep 2
      ;;
    yt-feed-5scrolls)
      for _ in 1 2 3 4 5; do
        adb shell input swipe $CX 1600 $CX 700 300
        sleep 1.5
      done
      ;;
    yt-hold)
      # Same start and end point over 3s: a press-and-hold with no movement. Any count is false.
      adb shell input swipe $CX 1200 $CX 1200 3000
      sleep 2
      ;;
    yt-idle)
      sleep 10
      ;;
    settings-list-slow)
      adb shell input swipe $CX 1500 $CX 900 2500
      sleep 2
      ;;
    *) echo "unknown case '$CASE'" >&2; exit 2 ;;
  esac
}

case "$CASE" in
  settings-list-slow) open_settings ;;
  *) open_youtube ;;
esac

adb logcat -c || true
sleep 1
echo "== $CASE: performing gestures =="
gestures
sleep 2
adb logcat -d > "$RAW"

mkdir -p "$OUT_DIR"
{
  echo "# a11y capture: $CASE"
  echo "# device: $DEVICE  captured: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "# gesture: see tools/qa/scroll-capture.sh, case '$CASE'"
  echo "# EXPECTED (test oracle): fill in the human-intended count for this gesture"
  sed -n 's/^.*\bEV \({.*}\)$/\1/p' "$RAW"
} > "$OUT_DIR/$CASE.jsonl"

echo
echo "-- scroll events grouped by source view (count | view id | class | index moves | deltaY set?) --"
grep 'NudgeA11yTrace' "$RAW" | sed 's/.*EV //' | grep '"t":"VIEW_SCROLLED"' | python3 - <<'PY'
import sys, json, collections
rows = collections.OrderedDict()
for line in sys.stdin:
    line = line.strip()
    if not line.startswith('{'):
        continue
    d = json.loads(line)
    key = (d.get('vid'), d.get('c'))
    r = rows.setdefault(key, {'n': 0, 'moves': [], 'dy': set(), 'sy': set(), 'items': set()})
    r['n'] += 1
    r['moves'].append((d.get('fi'), d.get('ti'), d.get('ci')))
    r['dy'].add(d.get('dy'))
    r['sy'].add(d.get('sy'))
    r['items'].add(d.get('n'))
if not rows:
    print("   (no scroll events)")
for (vid, cls), r in rows.items():
    transitions = sum(1 for a, b in zip(r['moves'], r['moves'][1:]) if a != b)
    print(f"   {r['n']:4d}  vid={vid}  class={cls}")
    print(f"         index moves: {r['moves'][:8]}{' ...' if len(r['moves'])>8 else ''}")
    print(f"         distinct-transitions={transitions}  deltaY values={sorted(r['dy'])}  scrollY={sorted(r['sy'])}  itemCount={sorted(r['items'])}")
PY

echo
echo "-- clicks --"
grep 'NudgeA11yTrace' "$RAW" | sed 's/.*EV //' | grep -c '"t":"VIEW_CLICKED"' || true

echo
echo "-- what Nudge's counter did --"
grep -E 'NudgeLog' "$RAW" | grep -Ei 'counter|interaction|auto-kick' \
  | sed 's/^\([0-9-]* [0-9:.]*\).*NudgeLog[^ ]*: /\1  /' | tail -20 || echo "   (nothing)"

echo
echo "  fixture: $OUT_DIR/$CASE.jsonl"
echo "  raw log: $RAW"
