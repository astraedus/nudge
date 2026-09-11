#!/usr/bin/env bash
#
# a11y-capture.sh — record the real accessibility event stream off a device and save it as a
#                   replayable test fixture.
#
# WHY THIS EXISTS:
#   Every bug this subsystem has shipped (#5 keyboards, #7 recents re-entry, #19 picture-in-picture,
#   #28 spurious re-blocks + a wrong counter) was the same kind of bug: the code assumed a shape of
#   event stream that the device does not actually produce. Each one cost at least one device cycle
#   to localise by guessing. A capture removes the guessing — the stream is written down once, the
#   fix is written against it, and the same file becomes the regression test.
#
#   The workflow for the NEXT report is therefore:
#     1. reproduce it on the device with this script running,
#     2. commit the .jsonl under app/src/test/resources/a11y-captures/,
#     3. write a replay test that FAILS on it,
#     4. fix until it passes.
#   See docs/architecture/accessibility-event-pipeline.md.
#
# REQUIREMENTS:
#   - A debug build installed, OR a release build with debug logging enabled (tap the version
#     number in Settings seven times). The trace is gated on NudgeLogger.isDebugEnabled, so with
#     neither of those this script records nothing.
#   - The accessibility service actually enabled. `adb install -r` DROPS the grant — re-enable with:
#       adb -s "$DEVICE" shell settings put secure enabled_accessibility_services \
#         dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService
#       adb -s "$DEVICE" shell settings put secure accessibility_enabled 1
#
# USAGE:
#   scripts/a11y-capture.sh <name> [seconds]
#
#     <name>     fixture name, e.g. instagram-slow-feed-scroll  (becomes <name>.jsonl)
#     [seconds]  how long to record. Default 30. Ctrl-C also stops cleanly.
#
#   Env overrides:
#     DEVICE=<adb serial>   default: 192.168.1.68:5555 (the Pixel 3 bench device)
#     OUT_DIR=<path>        default: app/src/test/resources/a11y-captures
#
# OUTPUT:
#   One JSON object per line — exactly what AccessibilityEventCodec.decode() reads back. A header
#   comment records what was captured and when; '#' lines are ignored by the loader, so annotate
#   the file freely (the EXPECTED human count for a capture belongs there).

set -euo pipefail

NAME="${1:-}"
DURATION="${2:-30}"
DEVICE="${DEVICE:-192.168.1.68:5555}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/app/src/test/resources/a11y-captures}"

if [[ -z "$NAME" ]]; then
  echo "usage: scripts/a11y-capture.sh <name> [seconds]" >&2
  echo "  e.g. scripts/a11y-capture.sh instagram-slow-feed-scroll 20" >&2
  exit 2
fi

if ! adb -s "$DEVICE" get-state >/dev/null 2>&1; then
  echo "error: device '$DEVICE' is not connected (adb devices)" >&2
  exit 1
fi

# Fail loudly rather than silently recording an empty file: a capture nobody notices is empty is
# how a "fixed" bug ships unfixed.
ENABLED="$(adb -s "$DEVICE" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ "$ENABLED" != *"com.astraedus.nudge"* ]]; then
  echo "error: Nudge's accessibility service is not enabled on $DEVICE." >&2
  echo "       enabled_accessibility_services = '$ENABLED'" >&2
  echo "       adb -s $DEVICE shell settings put secure enabled_accessibility_services \\" >&2
  echo "         dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService" >&2
  echo "       adb -s $DEVICE shell settings put secure accessibility_enabled 1" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
OUT_FILE="$OUT_DIR/$NAME.jsonl"
RAW_FILE="$(mktemp)"
trap 'rm -f "$RAW_FILE"' EXIT

adb -s "$DEVICE" logcat -c
echo "capturing '$NAME' for ${DURATION}s from $DEVICE — drive the device now..."

# -s NudgeA11yTrace filters to the trace tag at the logcat level, so the thousands of lines a
# scrolling session produces never cross the adb connection as unrelated noise.
adb -s "$DEVICE" logcat -s "NudgeA11yTrace:I" > "$RAW_FILE" &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true; rm -f "$RAW_FILE"' EXIT INT TERM

sleep "$DURATION"
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true

{
  echo "# a11y capture: $NAME"
  echo "# device: $DEVICE  android: $(adb -s "$DEVICE" shell getprop ro.build.version.release | tr -d '\r')"
  echo "# captured: $(date -u +%Y-%m-%dT%H:%M:%SZ)  duration: ${DURATION}s"
  echo "# EXPECTED (fill this in — it is the test oracle): what a human did, and what the counter SHOULD read"
  # Keep everything after the 'EV ' marker, verbatim. sed rather than grep -o so a line that
  # somehow contains the marker twice cannot be silently split into two records.
  sed -n 's/^.*\bEV \({.*}\)$/\1/p' "$RAW_FILE"
} > "$OUT_FILE"

EVENTS="$(grep -c '^{' "$OUT_FILE" || true)"
echo "wrote $EVENTS events -> $OUT_FILE"

# The source-node binder read is the one non-free field in the trace. Surface its measured cost so
# "can we afford this in production?" is answered with numbers.
if grep -q 'COST ' "$RAW_FILE"; then
  echo "source-read cost (viewIdResourceName binder reads):"
  grep 'COST ' "$RAW_FILE" | tail -3 | sed 's/^.*COST /  /'
fi

if [[ "$EVENTS" -eq 0 ]]; then
  echo "warning: captured ZERO events. Is debug logging on (debug build, or 7 taps on the version" >&2
  echo "         number in Settings), and is the service actually running?" >&2
  exit 1
fi
