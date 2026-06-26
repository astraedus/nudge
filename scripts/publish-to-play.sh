#!/usr/bin/env bash
#
# publish-to-play.sh — push a Nudge release to Google Play via gplay.
#
# WHY THIS RUNS LOCALLY (not in CI):
#   Nudge is a PUBLIC, open-source repo. The Google Play API credential is a
#   powerful secret. We deliberately keep it OFF GitHub Actions so a malicious
#   PR or a compromised third-party action can never exfiltrate it. The signed
#   AAB is built in CI (no Play creds needed there — only the UPLOAD key, and
#   Nudge is enrolled in Play App Signing so even that can be rotated if leaked).
#   This script then takes that CI-built AAB and uploads it from the laptop,
#   where the gplay admin service-account key lives (chmod 600, never committed).
#
# WHAT IT DOES:
#   1. Resolve the signed AAB for a version (from the GitHub Release, a CI
#      workflow-run artifact, or an explicit path).
#   2. gplay preflight  — offline secret/compliance/hygiene scan of the bundle.
#   3. gplay release     — upload to a track with a staged rollout.
#   4. gplay status      — print the resulting release-health snapshot.
#
# USAGE:
#   scripts/publish-to-play.sh <version> [aab-path]
#
#   Env overrides (all optional):
#     TRACK=production|beta|alpha|internal   (default: production)
#     ROLLOUT=0.0-1.0                        (default: 0.2  → 20% staged rollout)
#     STATUS=draft|inProgress|halted|completed
#                                            (default: draft → uploaded but NOT
#                                             released to users until promoted)
#     SOURCE=release|run                     (default: release; "run" = pull the
#                                             AAB from the latest workflow_dispatch
#                                             run artifact instead of a GH Release)
#
# EXAMPLES:
#   # Safe: upload 1.7.0 as a production DRAFT (no users affected) to verify.
#   scripts/publish-to-play.sh 1.7.0
#
#   # Go live to 20% of production users, halt-able from Play Console.
#   STATUS=inProgress ROLLOUT=0.2 scripts/publish-to-play.sh 1.7.0
#
#   # Full rollout once you're confident.
#   STATUS=completed ROLLOUT=1.0 scripts/publish-to-play.sh 1.7.0
#
set -euo pipefail

REPO="astraedus/nudge"
PKG="dev.astraedus.nudge"
TRACK="${TRACK:-production}"
ROLLOUT="${ROLLOUT:-0.2}"
STATUS="${STATUS:-draft}"
SOURCE="${SOURCE:-release}"

VERSION="${1:-}"
AAB="${2:-}"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$VERSION" ] || die "version required. Usage: scripts/publish-to-play.sh <version> [aab-path]"
command -v gplay >/dev/null || die "gplay not on PATH (see ~/ops/references/play-console-cli.md)"
command -v gh    >/dev/null || die "gh CLI not on PATH"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# --- 1. resolve the signed AAB ------------------------------------------------
if [ -n "$AAB" ]; then
  [ -f "$AAB" ] || die "AAB not found: $AAB"
  echo "Using explicit AAB: $AAB"
elif [ "$SOURCE" = "run" ]; then
  echo "Downloading AAB from latest 'Release' workflow run artifact…"
  RUN_ID="$(gh run list -R "$REPO" --workflow release.yml --limit 1 --json databaseId -q '.[0].databaseId')"
  [ -n "$RUN_ID" ] || die "no workflow runs found for release.yml"
  gh run download "$RUN_ID" -R "$REPO" --dir "$WORKDIR" || die "artifact download failed (run still in progress?)"
  AAB="$(find "$WORKDIR" -name '*.aab' | head -1)"
  [ -n "$AAB" ] || die "no .aab in run $RUN_ID artifacts"
else
  echo "Downloading AAB from GitHub Release v${VERSION}…"
  gh release download "v${VERSION}" -R "$REPO" --pattern '*.aab' --dir "$WORKDIR" \
    || die "no .aab attached to release v${VERSION}. Re-run CI (it now builds an AAB) or pass an explicit path / SOURCE=run."
  AAB="$(find "$WORKDIR" -name '*.aab' | head -1)"
fi
echo "AAB: $AAB ($(du -h "$AAB" | cut -f1))"

# --- 2. release notes from CHANGELOG (Play caps at 500 chars/locale) ----------
NOTES="$(awk -v ver="$VERSION" '
  $0 ~ "^## \\[" ver "\\]" {grab=1; next}
  grab && /^## \[/ {exit}
  grab {print}
' "$ROOT/CHANGELOG.md" | sed 's/\*\*//g; s/^- /• /' | sed '/^### /d' | grep -v '^[[:space:]]*$' | head -8)"
[ -n "$NOTES" ] && NOTES="What's new in v${VERSION}:
${NOTES}" || NOTES="Bug fixes and improvements (v${VERSION})."
# Google Play hard-caps release notes at 500 chars/locale; trim the FINAL string.
NOTES="${NOTES:0:497}"
echo "----- release notes -----"; echo "$NOTES"; echo "-------------------------"

# --- 3. preflight (offline secret/compliance scan) ----------------------------
echo "Running gplay preflight…"
gplay preflight --file "$AAB" || die "preflight failed — fix before publishing"

# --- 4. release ---------------------------------------------------------------
echo "Releasing to track=$TRACK rollout=$ROLLOUT status=$STATUS …"
gplay release \
  --package "$PKG" \
  --track "$TRACK" \
  --bundle "$AAB" \
  --version-name "$VERSION" \
  --release-notes "$NOTES" \
  --rollout "$ROLLOUT" \
  --status "$STATUS" \
  --wait

echo "Done. Current Play status:"
gplay status --package "$PKG" --pretty
