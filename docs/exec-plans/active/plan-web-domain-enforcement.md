# Web-domain enforcement after the entry block (v1.15.2)

Field report (Anti, 2026-08-31, Chrome on a Pixel): *"it'll delay me to go on insta web but once I'm
on there's no other blocks or it doesn't track anything."*

## Diagnosis (file:line evidence in the report)

The web path enforces exactly ONE thing — the entry gate — and everything downstream of it is dead:

1. **The domain pass is granted at BLOCK time, not at COMPLETION time.**
   `NudgeAccessibilityService.evaluateWebDomain` sets `lastBlockedDomain = extractedDomain` before
   `handleDecision` even launches the overlay, so abandoning the block (walk-away, tab-out/`onStop`,
   back) still leaves a granted pass. The app path grants only in
   `BlockOverlayActivity.onTimerComplete`, lifecycle-gated.
2. **A completed web delay grants the app-level passthrough to the WRONG package.**
   `handleDecision(decision, result.trackingPackage ?: browserPackage)` puts `com.instagram.android`
   in `EXTRA_PACKAGE_NAME`, so `onTimerComplete` grants a pass to the Instagram *app* for a delay
   completed on instagram.com in Chrome.
3. **An unreadable omnibox silently revokes a live pass** (`extractedDomain != lastBlockedDomain`
   with a null `extractedDomain`), re-blocking the user mid-visit. Issue #5's failure class.
4. **Nothing time-based can run in a browser at all.** `CounterCacheRefresher` is keyed by
   `rule.packageName`, so the browser package never has an entry ⇒ no foreground-time ticker, no
   `autoKickAfterMinutes`, no auto-kick cooldown, no time-remaining overlay, no counter.
5. **Web time never reaches a daily budget.** `dailyUsageMs(trackingPackage)` is the *app's*
   UsageStatsManager foreground time; browsing instagram.com adds zero to it. (The reverse direction
   works and is kept: once the app's budget is spent, the site hard-blocks too.)

## Scope decision

FIX (this change): 1, 2, 3, 4 — plus honest UI wording for 5.
DEFER (feature, `docs/BACKLOG.md`): daily-scoped web time. It needs persisted per-domain day records
(no UsageStatsManager source exists), which is the same store per-domain stats attribution needs.
A daily budget backed by in-memory state would reset on every service restart — a blocker that
silently blocks nothing, the worst failure class this app has (#21).

## Design

**A blocked domain is a foreground "package" named `web:<domain>` whose clock is the browser's
clock.** Every existing mechanism (counter cache, session expiry, cooldown, `TimeKickEvaluator`,
`AutoKickExecutor`) is keyed by an opaque `String` and works unchanged.

- `domain/web/WebSessionKey.kt` — the synthetic key (pure).
- `domain/web/WebDomainGate.kt` — the pass/unreadable/evaluate decision (pure). Unreadable = do
  nothing, never revoke: unverifiable means do nothing, as with a null active window in #7.
- `PassthroughManager` gains the web axis (`lastDomain`), so both passes have ONE lifetime and one
  clearing path. The service's `lastBlockedDomain` field is deleted.
- `BlockOverlayActivity` gains `EXTRA_PASSTHROUGH_PACKAGE` + `EXTRA_WEB_DOMAIN`; `onTimerComplete`
  grants both axes. `EXTRA_PACKAGE_NAME` stays the tracking package so the overlay still says
  "Instagram", the rule name and the `UsageEvent` are unchanged.
- `WebSessionUsageProvider` resolves a `web:` key to the browser's daily foreground ms, so
  `AutoKickTimeHandler` / `TimeKickEvaluator` are reused verbatim. Session elapsed therefore excludes
  time in other apps and with the screen off, for free, exactly as the app path does.
- A SEPARATE `webTimeJob` ticker (not the app-level one) so the documented hot path is untouched.

Out of scope on web, deliberately: the interaction counter and interaction-based auto-kick (the
event package is the browser, not the domain — a second plumbing job for a cosmetic surface) and the
time-remaining overlay (needs the deferred daily number).
