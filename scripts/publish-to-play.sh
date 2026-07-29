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
#   3. gplay release     — upload to a track and release it (100% by default).
#   4. gplay status      — print the resulting release-health snapshot.
#
# USAGE:
#   scripts/publish-to-play.sh <version> [aab-path]
#
#   Env overrides (all optional):
#     TRACK=production|beta|alpha|internal   (default: production)
#     ROLLOUT=0.0-1.0                        (default: 1.0  → everyone)
#     STATUS=draft|inProgress|halted|completed
#                                            (default: completed → live to 100%)
#
#   WHY the default is a FULL rollout, not a staged one (Anti, 2026-07-30):
#   a staged rollout is a SECOND owed step — someone must come back days later
#   and promote it, and `gplay rollout complete` is broken (see below), so the
#   promotion is a hand-run edit cycle. Twice now (v1.9.4, v1.10.0) that tail
#   step was forgotten or cost time, leaving users on an old build for no gain.
#   Our install base is small enough that a staged rollout buys ~nothing in
#   signal but reliably costs a follow-up. Ship to 100%; if a release is
#   genuinely risky (schema migration you can't test), opt IN explicitly with
#   STATUS=inProgress ROLLOUT=0.2 and file the promote step as a dated task.
#     SOURCE=release|run                     (default: release; "run" = pull the
#                                             AAB from the latest workflow_dispatch
#                                             run artifact instead of a GH Release)
#
# EXAMPLES:
#   # Default: go live to 100% of production users.
#   scripts/publish-to-play.sh 1.7.0
#
#   # Opt in to a staged, halt-able rollout (then you OWE the promote step).
#   STATUS=inProgress ROLLOUT=0.2 scripts/publish-to-play.sh 1.7.0
#
#   # Upload as a production DRAFT (no users affected) to eyeball it first.
#   STATUS=draft scripts/publish-to-play.sh 1.7.0
#
# PROMOTING a staged/draft release later: this script CANNOT do it — `gplay
# release` re-uploads the AAB and Play rejects a versionCode that already
# exists. And `gplay rollout complete` is ALSO broken: it sets status=completed
# while leaving userFraction set, which Play rejects with
# "COMPLETED release must not have fraction". The working recipe is the edit
# cycle (edits create → tracks get → jq the release to status=completed with
# userFraction DELETED → tracks update --releases @file → validate → commit).
# Full recipe: ~/ops/references/play-console-cli.md.
#
set -euo pipefail

REPO="astraedus/nudge"
PKG="dev.astraedus.nudge"
TRACK="${TRACK:-production}"
ROLLOUT="${ROLLOUT:-1.0}"
STATUS="${STATUS:-completed}"
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
' "$ROOT/CHANGELOG.md" | sed 's/\*\*//g; s/^- /• /' | sed '/^### /d' | grep -v '^[[:space:]]*$')"
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
