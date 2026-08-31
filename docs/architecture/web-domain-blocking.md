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
- Integration in `NudgeAccessibilityService`: on `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOW_CONTENT_CHANGED` for browser packages, calls `evaluateWebDomain()` which reads URL bar, extracts domain, queries `EvaluateBlockUseCase.evaluateWebDomain()`.
- `EvaluateBlockUseCase.evaluateWebDomain()` finds all enabled rules with matching `webDomains`, resolves each one's WEB mode, converts to `ActiveRule` list, passes through `BlockEngine`.
- **Web enforcement is independent of the app-level mode (fixes [#21](https://github.com/astraedus/nudge/issues/21))**. Web domains used to be evaluated with `BlockMode.valueOf(rule.mode)`, the APP-level mode, so a rule with `BlockMode.NONE` (whole-app blocking off, the state that makes Shorts-only blocking expressible) allowed every configured website too: a blocker silently blocking nothing, the worst failure class this app has. It was mitigated in the UI only, by disabling the "Block on web too" toggle in that state.
  - `BlockRule.webBlockMode` (nullable, DB v10) is the independent mode. **NULL = inherit the app-level mode**, which is exactly what every pre-v10 rule did, so existing behaviour is untouched wherever the app mode is a real blocking mode.
  - `domain/model/WebBlockMode.resolve(ruleMode, webBlockMode)` is the ONE resolver (pure, `WebBlockModeTest`): the web mode wins, else the app mode, else HARD_BLOCK (fail toward enforcement, matching how a corrupt mode string was already handled). Never read the column raw.
  - A rule whose web mode resolves to NONE is DROPPED from the match list rather than yielding a no-op Block, so a URL covered only by such rules still falls through to the generic content filter instead of being treated as "handled, allowed".
  - `MIGRATION_9_10` repairs the rows the bug created (`mode='NONE'` + non-empty `webDomains`) to `webBlockMode='DELAY'`, those users had opted into web blocking and were getting nothing. DELAY, not HARD_BLOCK: the rule already carries a `delaySeconds`, and it matches the editor's own fallback when no prior blocking choice exists.
  - `RuleWeakening` gained the web axis (softened web mode, or domains removed while previously set), otherwise Strict Mode could be sidestepped by weakening web enforcement while leaving the app-level rule alone.
  - `RuleEditorViewModel` carries `webBlockMode` through its rebuild, same reason it already carries `webDomains`: that editor has no web UI and replaces the loaded rule wholesale.
  - **Grayscale is still NOT carried by a NONE rule**, it rides only inside `BlockDecision.Block`, so a NONE rule's `grayscale` flag is inert (documented on `BlockMode.NONE`). Fixing it needs a separate "apply grayscale while allowing" path in the service; out of scope for #21.
  - Tests: `WebBlockModeTest` (resolution matrix), `EvaluateBlockWebDomainTest` (the NONE regression, real modes unchanged, override, content-filter fallthrough, trackingPackage/ruleName), `NudgeDatabaseMigrationTest` (ALTER + repair UPDATE), `RuleWeakeningTest` (web axis both directions), `RuleExporterTest` (round-trip + older exports), `UnifiedAppConfigViewModelTest`, `RuleEditorRuleBuilderTest`.
- Passthrough: the completed-block grant is domain-scoped; the same domain won't re-trigger until the user navigates away, leaves the browser or goes Home. See "What happens AFTER the entry block" below for its lifetime, which changed in v1.15.2.
- UI: "Block on web too" toggle in `UnifiedAppConfigScreen`, auto-populates known domains per `DEFAULT_WEB_DOMAINS` map. It is available **regardless** of whole-app blocking (issue #21). With whole-app blocking ON the websites follow the app's mode (`webBlockMode` persisted as null, and `setDefaultMode` keeps the web picker in sync so switching whole-app blocking off later doesn't silently change what the sites do); with it OFF the section shows a "Website block mode" segmented picker whose value IS persisted. The shared `delaySeconds` control (`UnifiedAppConfigState.showDelayDuration`) stays reachable in the web-only case, else a web DELAY would be stuck at whatever was last saved.

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
