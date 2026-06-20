# Firefox web-block detection — on-device findings (2026-06-20)

Device: Pixel 3 (192.168.1.68:5555), Android 12. Firefox 152.0.1 (`org.mozilla.firefox`,
official Mozilla arm64 APK from ftp.mozilla.org/pub/fenix/releases).

## Problem
`WebDomainDetector` had Firefox "wired" via view-id suffix `mozac_browser_toolbar_url_view`
(+ `url_bar_title`), read from `node.text`. On modern Firefox this NEVER matches — web
blocking silently does nothing in Firefox.

## Root cause (verified via `uiautomator dump`)
Modern Firefox uses a Jetpack-Compose toolbar. The address-bar node:

```
resource-id="ADDRESSBAR_URL_BOX"          <-- BARE Compose testTag, NO "org.mozilla.firefox:id/" prefix
class="android.view.View"
text=""                                    <-- ALWAYS EMPTY
content-desc=" instagram.com. Search or enter address"   <-- URL lives HERE
```

On example.com/some/path:
```
content-desc=" example.com/some/path. Search or enter address"
```
`mozac_browser_toolbar_url_view` is ABSENT entirely (grep count 0).

Two bugs vs our code:
1. **Bare id**: `urlBarViewIdsFor()` builds `"$pkg:id/$suffix"`. Compose testTags are exposed
   to accessibility WITHOUT a package prefix (Firefox sets testTagsAsResourceId), so
   `findAccessibilityNodeInfosByViewId("ADDRESSBAR_URL_BOX")` is the only thing that matches —
   the prefixed form never will.
2. **URL in contentDescription, not text**: `detectUrl` reads `nodes[0].text` (blank for FF).
   Must fall back to `contentDescription`.

## Fix
- Add bare id `ADDRESSBAR_URL_BOX` as a Firefox/Fenix candidate; keep legacy
  `mozac_browser_toolbar_url_view` + `url_bar_title` for older builds. `urlBarViewIdsFor` must
  emit the BARE id (not only `$pkg:id/...`).
- In `detectUrl`: if `node.text` is blank, fall back to `node.contentDescription`, then clean.
- Clean the Firefox hint suffix: the URL never contains a period-followed-by-whitespace, so cut
  the content-desc at the first `\.\s` match — yields `instagram.com` / `example.com/some/path`.
  `WebDomainMatcher.extractDomain` then strips the path. Locale-agnostic (works regardless of
  the localized "Search or enter address" hint).
- Tests: update `WebDomainDetectorTest` for the new id list shape + a content-desc/clean case.

## Runtime correction (found in on-device QA — important)
`findAccessibilityNodeInfosByViewId("ADDRESSBAR_URL_BOX")` does NOT match bare Compose testTags
at runtime, even though the node IS in the tree with `viewIdResourceName == "ADDRESSBAR_URL_BOX"`
(uiautomator confirms it, and the service has report-view-ids enabled since Chrome's
`com.android.chrome:id/url_bar` lookup works). The platform `ByViewId` query is unreliable for
unprefixed (no `pkg:id/`) resource names. First fix attempt (just adding the bare id to the
candidate list) STILL returned null on device — logcat: `web domain: no URL detected in browser`.

**Working fix:** keep `findAccessibilityNodeInfosByViewId` as the fast path for fully-qualified
ids (Chrome etc., no regression); when it yields nothing, fall back to a bounded depth-first
**tree traversal** that matches `node.viewIdResourceName` against the bare candidate ids directly,
then reads text→contentDescription→clean. GeckoView web content isn't exposed as a11y nodes, so
the native tree is small and the walk is cheap (capped at ~600 nodes as a guard).

## QA (after build)
Use a BENIGN domain (e.g. `github.com`) to avoid the Instagram app-link dialog hijacking the active
window AND the `block-adb-social.sh` safety hook. Set a HARD_BLOCK web rule on github.com, open it
in Firefox → expect the Nudge overlay; the `no URL detected` log line should disappear. Re-test
Chrome for no regression; negative-control example.com (no overlay).
