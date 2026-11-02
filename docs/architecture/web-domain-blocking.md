# Per-rule web domain blocking

Covers blocking specific websites in a browser via the accessibility URL bar: domain extraction,
multi-browser URL-bar reading, and the independent `webBlockMode` (issue #21).
**Read before touching `WebDomainMatcher`, `WebDomainDetector`, `WebBlockMode`, or the browser paths in `NudgeAccessibilityService`.**
For the generic opt-in restricted-content blocklist, see `content-filter.md` instead.

## Feature summary

- **Web domain blocking (Chrome v1)** — blocks websites in Chrome that match app rules. When Chrome is foregrounded, reads URL bar via accessibility tree (`WebDomainDetector`), extracts domain (`WebDomainMatcher`), matches against rules' `webDomains` field. Same overlay modes (HARD_BLOCK/DELAY/BREATHING), at the rule's own web mode (see below). Passthrough prevents re-blocking same domain. Only Chrome for v1 (extensible via `BROWSER_PACKAGES`). UI toggle "Block on web too" auto-populates known domains (Instagram, YouTube, TikTok) or allows custom entry.

## Web domain blocking architecture

- `domain/WebDomainMatcher.kt` — pure Kotlin (no Android deps). `extractDomain(urlBarText)` strips protocol/path/port, normalizes subdomains (www, m, mobile, l, lm). `matches(urlBarText, webDomains)` checks extracted domain against comma-separated rule domains.
- `service/WebDomainDetector.kt` — `@Singleton`, two-strategy URL-bar read. Multi-browser: `BROWSER_URL_BAR_IDS` maps each package to ordered candidate view-id suffixes (Chrome/Brave/Edge/Kiwi `url_bar`+`omnibox_url_text`, Firefox/Fenix `ADDRESSBAR_URL_BOX`+`mozac_browser_toolbar_url_view`, Samsung Internet `location_bar_edit_text`, Opera `url_field`, DuckDuckGo `omnibarTextInput`). **Strategy 1 (fast path)**: `findAccessibilityNodeInfosByViewId()` over fully-qualified `pkg:id/suffix` ids (Chromium family, Samsung, Opera, DDG). **Strategy 2 (traversal fallback)**: when the fast path finds nothing, `findNodeByViewId()` does a bounded (≤600 nodes) DFS matching `node.viewIdResourceName` against the BARE suffixes — needed for modern Firefox, whose Compose toolbar exposes the URL bar as a bare testTag `ADDRESSBAR_URL_BOX` (no `pkg:id/` prefix) that `findAccessibilityNodeInfosByViewId` will NOT match at runtime, with the URL in `contentDescription` (not `text`). `readUrlRaw(node)` reads text→contentDescription; `cleanAddressBarText()` strips Firefox's localized "…. Search or enter address" hint by cutting at the first `\.\s` (locale-agnostic; URLs never contain period-space). `urlBarViewIdsFor`/`qualifiedUrlBarViewIdsFor`/`bareUrlBarViewIdsFor` are pure, unit-tested. `isBrowser(pkg)` checks map membership. Findings/rationale: `docs/firefox-webblock-findings.md`.
- Integration in `NudgeAccessibilityService`: on `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOW_CONTENT_CHANGED` for browser packages, calls `evaluateBrowser()`, which first evaluates a browser a group or All apps rule covers as an app (see "Browsers in a group" below) and otherwise calls `evaluateWebDomain()`, which reads URL bar, extracts domain, queries `EvaluateBlockUseCase.evaluateWebDomain()`. `evaluateBrowser` is the only caller of `evaluateWebDomain` (pinned by `GroupBrowserWiringContractTest`).
- `EvaluateBlockUseCase.evaluateWebDomain()` finds all enabled rules with matching `webDomains`, resolves each one's WEB mode, converts to `ActiveRule` list, passes through `BlockEngine`.
- **Web enforcement is independent of the app-level mode (fixes [#21](https://github.com/astraedus/nudge/issues/21))**. Web domains used to be evaluated with `BlockMode.valueOf(rule.mode)`, the APP-level mode, so a rule with `BlockMode.NONE` (whole-app blocking off, the state that makes Shorts-only blocking expressible) allowed every configured website too: a blocker silently blocking nothing, the worst failure class this app has. It was mitigated in the UI only, by disabling the "Block on web too" toggle in that state.
  - `BlockRule.webBlockMode` (nullable, DB v10) is the independent mode. **NULL = inherit the app-level mode**, which is exactly what every pre-v10 rule did, so existing behaviour is untouched wherever the app mode is a real blocking mode.
  - `domain/model/WebBlockMode.resolve(ruleMode, webBlockMode)` is the ONE resolver (pure, `WebBlockModeTest`): the web mode wins, else the app mode, else HARD_BLOCK (fail toward enforcement, matching how a corrupt mode string was already handled). Never read the column raw.
  - A rule whose web mode resolves to NONE is DROPPED from the match list rather than yielding a no-op Block, so a URL covered only by such rules still falls through to the generic content filter instead of being treated as "handled, allowed".
  - `MIGRATION_9_10` repairs the rows the bug created (`mode='NONE'` + non-empty `webDomains`) to `webBlockMode='DELAY'`, those users had opted into web blocking and were getting nothing. DELAY, not HARD_BLOCK: the rule already carries a `delaySeconds`, and it matches the editor's own fallback when no prior blocking choice exists.
  - `RuleWeakening` gained the web axis (softened web mode, or domains removed while previously set), otherwise Strict Mode could be sidestepped by weakening web enforcement while leaving the app-level rule alone.
  - (The old `RuleEditorViewModel`, which carried `webBlockMode` and `webDomains` through its wholesale rebuild, was deleted as dead code; App config is the only editor.)
  - **Grayscale is still NOT carried by a NONE rule**, it rides only inside `BlockDecision.Block`, so a NONE rule's `grayscale` flag is inert (documented on `BlockMode.NONE`). Fixing it needs a separate "apply grayscale while allowing" path in the service; out of scope for #21.
  - Tests: `WebBlockModeTest` (resolution matrix), `EvaluateBlockWebDomainTest` (the NONE regression, real modes unchanged, override, content-filter fallthrough, trackingPackage/ruleName), `NudgeDatabaseMigrationTest` (ALTER + repair UPDATE), `RuleWeakeningTest` (web axis both directions), `RuleExporterTest` (round-trip + older exports), `UnifiedAppConfigViewModelTest`.
- Passthrough: the completed-block grant is domain-scoped; the same domain won't re-trigger until the user navigates away, leaves the browser or goes Home. See "What happens AFTER the entry block" below for its lifetime, which changed in v1.15.2.
- UI: "Block on web too" toggle in `UnifiedAppConfigScreen`, auto-populates known domains per `DEFAULT_WEB_DOMAINS` map. It is available **regardless** of whole-app blocking (issue #21). With whole-app blocking ON the websites follow the app's mode (`webBlockMode` persisted as null, and `setDefaultMode` keeps the web picker in sync so switching whole-app blocking off later doesn't silently change what the sites do); with it OFF the section shows a "Website block mode" segmented picker whose value IS persisted. The shared `delaySeconds` control (`UnifiedAppConfigState.showDelayDuration`) stays reachable in the web-only case, else a web DELAY would be stuck at whatever was last saved.

## Browsers in a group (spec 5.5, [#109](https://github.com/astraedus/nudge/issues/109))

Before spec PR 2 every browser went straight to the website path, whatever group it was in. A user
who put Chrome in a "Social" group with a Hard block got website rules only: the group's rule has no
`webDomains`, so every site was allowed and the group did nothing to Chrome. That was #109.

**The rule now: a browser a group covers is evaluated as an app first.** "Covers" means
`WholeAppCoverage.coversNow` (pure, `domain/engine/`): some enabled GROUP rule whose group contains
the browser, or ALL APPS rule that does not allow it (see "Browsers under an All apps rule"), with no
in-app features (a feature rule blocks a part of an app, never a whole one) and
inside its schedule right now. The mode is NOT checked: a NONE group rule still covers, and its app
evaluation simply allows and hands over to the website path.

"Contains" means added to the group by hand. A browser never joins a group through a category (spec
PR 6): Chrome reports Social and Firefox Productivity, so checking either would otherwise hard-block
the whole browser without the user ever picking it. For the same reason a category group an All apps
rule allows does not let a browser through; only a hand-added row does. See `rules-and-features.md`,
"A browser never joins by category".

The flow, in `evaluateBrowser(browserPackage, grantAlive)`:

1. `coveredWhole = !grantAlive && EvaluateBlockUseCase.isCoveredWhole(browser)`, and
   `BrowserRouting.route(coveredWhole)` gives `APP_FIRST` or `WEB`. `isCoveredWhole` reads the rules
   through `RuleSnapshots.current()`, the same read every app evaluation uses, so it is fail closed
   on a cold start (see below).
2. On `APP_FIRST`, `EvaluateBlockUseCase.invoke(browser, excludeDirectRules = true)` evaluates the
   browser's GROUP rules only. A **direct** rule on a browser keeps its old meaning, web-only, so
   an existing "Chrome: block youtube.com" rule never starts blocking Chrome itself.
3. If that decision blocks (`!BrowserRouting.continuesToWeb(decision)`), the browser is blocked as
   an app: `handleDecision(decision, browser)` with no website, so the confrontation, the grant and
   the walk-away are the app's, and any web session's clock is ended (`endWebSession`). The overlay
   reads "Rule: <group name>".
4. Otherwise (Allow) the website path runs exactly as before, so a site rule still applies inside a
   browser the group let through.

The four service sites:

| Site | Where | What |
|---|---|---|
| 1 | `evaluateForegroundPackage` | the pinned `!nuked && isBrowser` branch calls `evaluateBrowser(packageName, grantAlive = false)` (a grant already returned above) |
| 2 | `handleWindowContentChanged`, browser branch | `evaluateBrowser(packageName, grantAlive = grantAliveFor(packageName))`, the grant read on the event thread, so a schedule starting or a limit running out reaches a user who is still browsing; debounced 2 s, swallowed under a live overlay, deduped by `BlockLaunchGate` |
| 3 | `detectAndEvaluateFeature` | returns for a covered browser, right after the Nuke line (no browser is in `InAppDetector.SUPPORTED_PACKAGES` today; this pins the rule for the day one is) |
| 4 | `grantLetsThrough` / `grantAliveFor` via `passthroughEarned` | a covered browser is let through only by a WHOLE-app grant (`PassthroughManager.isGrantedWhole`), so a finished website delay does not open a browser its group blocks |

**Cold start fails closed.** The decision to evaluate a browser as an app is made by
`EvaluateBlockUseCase.isCoveredWhole`, which reads `RuleSnapshots.current()`: before the in-memory
snapshot's first emission (or while its collector is failing) that is a direct table read, so Chrome
in a hard-blocked group is blocked in the first moments after the process starts exactly like
Instagram in the same group. The first version read `snapshot.value` and answered "not covered"
while it was null, which let a grouped browser open freely for that window; review
caught it before merge.

Sites 3 and 4 run on the event thread, which must not suspend or touch the database, so they keep
the synchronous `isCoveredWholeNow` over `RuleSnapshotSource.snapshot.value`. Cold, it answers
**true** for a browser: the grant check then accepts only a whole-app grant and `evaluateBrowser`
makes the table-backed decision (a browser that turns out not to be covered goes on to its website
rules as always), and feature detection steps aside for a browser that has no supported features
anyway.

Known edges, accepted:

- A Custom Tab runs in its browser's package, so it is blocked too while that browser is covered.
- `enforceExhaustedBudget` still evaluates a browser with its direct rules (the counter cache holds
  direct rules only), unchanged by 5.5.
- While a covered browser is allowed, site 2 runs one in-memory app evaluation per 2 s debounce.

Tests: `WholeAppCoverageTest` (L1, every condition), `PassthroughWholeAxisTest` (L1),
`EvaluateBlockGroupRulesTest` (L3, `excludeDirectRules`, strictest-wins with a direct rule, and
`isCoveredWhole` through a `RuleSnapshotSource` that never emits: a cold grouped browser is covered),
`GroupBrowserCaptureReplayTest` (L2: `chrome-open-and-browse.jsonl` through the classifier and the
routing; every Chrome evaluation blocks as an app, zero reach the website path, and the pre-fix
routing never blocks), `GroupBrowserWiringContractTest` (the four sites).

## Browsers under an All apps rule (spec 5.5, PR 4)

An All apps rule covers a browser like any launchable app: it is in "Blocks N apps", and the same
four sites route it through the same `WholeAppCoverage` / `isCoveredWhole` question, so a covered
browser is evaluated as an app and blocked before any URL is read. `excludeDirectRules = true` keeps
the All apps rule (it names no package). A browser on the rule's allowed list (or in one of its
allowed groups) is not covered by it and goes to its website rules as always; the All apps rule has
no websites of its own, so it adds nothing there. Allowing Chrome is the user's way to keep the web.

Browsers are never essential (spec 5.4), so coverage never skips one as essential.
`EssentialApps` takes every browser back off what its device sources return: Firefox declares an
autofill service, so before this fix the autofill source made Firefox essential and no group, All
apps rule or Nuke could cover it (found on the bench Pixel during PR 4).

The service's synchronous check fails closed exactly as for groups: cold, it says "covered" for any
browser; warm, it treats the browser as launchable and an unresolved essentials list as empty. Both
only send the browser to the suspending `isCoveredWhole`, which reads the real facts.

Tests: `AllAppsBrowserCaptureReplayTest` (L2: the same Chrome capture under an All apps rule; a
covered Chrome blocks as an app on every evaluation, an allowed Chrome reaches the website path and
its sites open, a Chrome with no launcher entry is not covered, and routing that ignores the All
apps rule never blocks), `EssentialAppsTest` (L1: a browser named by any source is not essential).

## What happens AFTER the entry block, v1.15.2

Reported from Anti's daily-driver phone: *"it'll delay me to go on insta web but once I'm on there's
no other blocks or it doesn't track anything."* Both halves were true. The web path enforced exactly
ONE thing, the entry gate, and **everything downstream of it was unreachable**, the user spends
their whole visit inside `evaluateWebDomain`'s passthrough early-return, and every line that could
have measured that visit sat below it. A website was a one-shot delay and then a free surface.

Four defects, three of them positional. The general shape is the one `tasks/lessons.md` already
names: *an early return added for surface X also skips every cleanup below it.*

### The pass was granted at BLOCK time, not at completion time

`evaluateWebDomain` set `lastBlockedDomain = extractedDomain` before `handleDecision` had even
launched the overlay. Every other grant in the app is **earned** in
`BlockOverlayActivity.onTimerComplete`, lifecycle-gated, precisely so that abandoning the block
grants nothing (issue #8). The web one was handed over on sight, so walking away from a website's
delay, or tabbing out of it, left the site open anyway.

The grant now travels with the overlay (`EXTRA_WEB_DOMAIN`) and is made on completion, which also
makes the HARD_BLOCK case correct **structurally** rather than by a special case: a hard block has no
completion path, so it can never grant.

### It was granted to the wrong package

`handleDecision(decision, result.trackingPackage ?: browserPackage)` put the *rule's app* in
`EXTRA_PACKAGE_NAME`, and that extra is what `onTimerComplete` grants against. So completing an
instagram.com delay in Chrome granted a free pass to the **Instagram app**, and granted nothing at
all to the browser the user was actually in.

Two extras now, because they answer two different questions: `EXTRA_PACKAGE_NAME` is what the block
is ATTRIBUTED to (the overlay's app label, the `UsageEvent`, the PiP session record, a website block
still belongs in Instagram's stats), and `EXTRA_PASSTHROUGH_PACKAGE` is the app the user is IN. The
emergency pass moved to the same extra for the same reason: the service checks `isPassActive` against
the foreground package, so a window opened on the rule's app would never have been seen and the
escape hatch would have re-blocked on the next event.

`PassthroughManager` now owns both axes (`lastPackage` + `lastDomain`), granted together and cleared
together. The service's `lastBlockedDomain` field is gone, one lifetime, one clearing path, and
`clearWebGrant()` for the "navigated away but did not leave the app" case.

### An unreadable URL bar revoked a live pass

```kotlin
if (extractedDomain != null && extractedDomain == lastBlockedDomain) return  // pass
if (extractedDomain != lastBlockedDomain) lastBlockedDomain = null           // revoke
```

`extractDomain` returns null for plenty of readings taken **while the user is still on the page**: a
page title in `contentDescription` rather than a URL in `text`, a search query, a half-typed address,
an internal scheme. `null != "instagram.com"` revoked the pass, and the next content change
re-blocked a user who had not gone anywhere, issue #5's failure class from a new direction.

`domain/web/WebDomainGate.kt` owns the three-way decision now, and its rule is the one the issue-#7
content-change fallback already applies to a null active window: **unverifiable means do nothing.** A
false negative retries on the next event; a false positive costs the user their pass mid-use.

### Nothing time-based could run in a browser at all

`CounterCacheRefresher` was keyed by `rule.packageName`, so a browser package was never in the cache.
`counterCache.hasEntry(chrome)` false ⇒ `clearOverlays(…, "counter_disabled")`;
`updateForegroundTimeTicker(chrome)` ⇒ `stopForegroundTimeTicker()` immediately. So while sitting on
a blocked website there was **no foreground-time clock, no `autoKickAfterMinutes`, no auto-kick
cooldown**, a rule saying "kick me off after 30 minutes" was enforced perfectly in the app and inert
on the web.

**A blocked domain is now a foreground "package" named `web:<domain>`** (`domain/web/WebSessionKey.kt`).
Everything the service keeps per foreground app is keyed by an opaque `String`, the counter cache,
`InteractionTracker`'s session/baseline/cooldown maps, `AutoKickExecutor.kick`, so a website needs no
parallel machinery, only a key. The key is deliberately neither the browser's package (a cooldown on
`com.android.chrome` would lock *every* website) nor the rule's app package (time on instagram.com
would spend the Instagram app's session), and the domain half is normalised, so `www.instagram.com`
and `instagram.com` are one session rather than two.

- **The clock is the browser's clock.** `WebSessionUsageProvider` resolves a `web:` key to
  `getDailyForegroundTimeMs(browserPackage)`. There is no UsageStatsManager stream for a website, and
  this is the only real measurement available, but it means `TimeKickEvaluator` and
  `AutoKickTimeHandler` are reused **verbatim**, and the session delta inherits their properties for
  free: time in other apps and time with the screen off are not in either reading, so they are not in
  the difference. Nothing new polls the system.
  - Known imprecision, accepted and documented on the class: a short detour to another site inside the
    same session still accrues, because the reading is browser-wide and a session survives an absence
    shorter than `SESSION_EXPIRY_MS`. That is the direction the app path already chose (a
    tab-out-and-back must not refill a budget), so it errs toward enforcement.
- **`onWebDomainForeground` is called from BOTH branches** of `evaluateWebDomain`, the passthrough
  one and the evaluate one, for exactly the reason `updateForegroundTimeTicker` sits above
  `evaluateForegroundPackage`'s early returns: *a user who has just completed a delay is precisely who
  a time-based auto-kick is for.* This is the fix for "it doesn't track anything".
- **A separate `webTimeJob`**, not the app-level ticker. Browsers are not in the counter cache, so
  every browser window event runs `clearOverlays → stopForegroundTimeTicker()`; a shared job would be
  torn down and restarted (re-reading usage) on each one. `clearOverlays` ends a web session only when
  what is now in front is *not* a browser.
- **Cost**: one 30s coroutine, existing only while a blocked domain is actually in the foreground, and
  only when a rule on that domain has a minutes threshold. The hot path pays one map lookup.
- **The kick revokes the pass it kicked out of** (`clearWebGrant()`), or returning would drop the user
  straight back on the page. Re-entry inside the cooldown gets the same DELAY overlay the app-level
  cooldown gets, keyed by domain.
- **Deliberately NOT carried across to the web**: the interaction counter and the interaction-based
  auto-kick (tap/scroll events arrive carrying the *browser's* package, so they cannot be attributed to
  a site), and the time-remaining overlay (it needs a DAILY web total, which does not exist, see
  below). Neither is silently half-wired: `webEntriesFor` sets both flags false and
  `CounterCacheWebEntriesTest` pins that.

### The daily budget on a web rule, one direction works, and the other is a BACKLOG feature

`dailyUsageMs(trackingPackage)` is the **app's** UsageStatsManager foreground time, so browsing
instagram.com adds nothing to it. That is not fixable here and is deliberately not half-fixed:

- **Working, and kept**: once the app's daily budget is spent, `BlockEngine`'s `time_budget_exceeded`
  branch hard-blocks the *website* too. Web rules therefore still carry `dailyLimitMinutes`.
- **Missing**: web time never spends a budget. Doing that needs a daily total per domain, which needs
  persisted per-domain day records, the same store per-domain stats attribution needs. An in-memory
  daily total would reset on every service restart, i.e. a budget that silently stops blocking, which
  is the exact failure class #21 exists to prevent. Design note: `docs/BACKLOG.md`.
- The rule editor now **says so** rather than presenting a control that quietly does nothing: with
  "Block on web too" on, the Daily Time Limit info explains that the budget counts app time and points
  at Auto-kick's time trigger for the websites, and the Auto-kick info says the time trigger covers the
  sites while the interaction trigger is app-only.

### Tests

`WebDomainGateTest` (6, incl. the unreadable-must-not-revoke regression), `WebSessionKeyTest` (6, incl.
subdomain spellings sharing one session and a real package never being mistaken for a web key),
`WebSessionUsageProviderTest` (5, incl. the synthetic key never reaching the platform, and the
end-to-end delta feeding `TimeKickEvaluator`), `CounterCacheWebEntriesTest` (9, incl. the
non-enforcing-rule exclusion, no counter/overlay carried across, and a site and its app staying
separate through the merge), `PassthroughManagerTest` (+5, the web axis in both directions), and
`WebDomainEnforcementContractTest` (8, source-level, **verified to fail all 8 against the pre-fix
source**, because every one of these bugs was about which side of a `return` a call sat on, which no
value-level test can see).
