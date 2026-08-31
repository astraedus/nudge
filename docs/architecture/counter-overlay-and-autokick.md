# Counter overlay, time-remaining overlay and auto-kick

Covers the floating interaction counter, the time-remaining overlay, both auto-kick triggers
(interaction count and foreground minutes), the auto-kick cooldown, and the duration inputs that configure them.
**Read before touching `service/` overlay code, `InteractionTracker`, `CounterCacheRefresher`, `AutoKick*`, or `ui/components/DurationInput.kt`.**

## Feature summary

- **Floating interaction counter** — centered touch-through overlay (40sp counter, 16sp label, 13sp daily) showing reels/shorts scrolled or taps per session. Escalating colors: white (0-9), orange (10-19), deep orange (20-29), red with red background tint (30+). TYPE_ACCESSIBILITY_OVERLAY from service, no extra permission. Per-rule `showCounter` toggle (default ON for new rules).
- **Time remaining overlay** — per-rule opt-in (`showTimeRemaining`). Displays "42m left" or "1h 12m left" below counter, color-coded: green (>50% remaining), orange (25-50%), red (<25%). Uses UsageStatsManager for actual foreground time. Requires daily limit to be set.
- **Auto-kick** — optional per-rule feature: sends user to home screen after N scrolls/taps in one session. Configurable threshold 5-100 (step 5, default 30). Session counter resets after kick. Stored as `autoKickAfter` on BlockRule. Requires the interaction counter (it is what feeds the count).
- **Auto-kick by time (v1.10.0)** — the second trigger, per-rule `autoKickAfterMinutes` (null = off). Kicks after N minutes of foreground time in one session. Independent of the interaction trigger — both can be set, whichever fires first kicks — and independent of the interaction counter, because its whole point is PASSIVE use (autoplaying video produces zero tap/scroll events). See "Time-based auto-kick architecture".
- **Auto-kick cooldown** — configurable per-rule, stored as `autoKickCooldownSeconds` on BlockRule. After auto-kick, returning to the app forces a DELAY overlay for the remaining cooldown. Session counter preserved during cooldown. **v1.10.0**: the 0-300s slider became a free-form MINUTES input (0-1440), so issue #6's "30 minutes on, 15 minutes off" is expressible. See "Duration inputs".

## Counter overlay architecture

- `InteractionTracker` (@Singleton): in-memory session/daily counts per package. No DB writes per interaction. Also tracks cooldown state per package after auto-kick.
- `CounterOverlayManager` (@Singleton): WindowManager overlay using service context (required for TYPE_ACCESSIBILITY_OVERLAY token). `setServiceContext()` called in `onServiceConnected()`. Centered on screen with escalating colors (white -> orange -> deep orange -> red) based on session count.
- `TimeRemainingOverlayManager` (@Singleton): Standalone floating overlay in top-right corner. Shows "Xm left" with color-coded text (green >50%, orange 25-50%, red <25%) and increasingly opaque background. Separate from counter overlay so both can show independently.
- `activeReelLabel`: once Shorts/Reels feature detected, skip tree inspection on subsequent scrolls. Reset on app switch.
- Tracked packages cached every 10s via `CounterCacheRefresher` (Map<String, CounterCacheEntry> with showCounter, autoKickAfter, showTimeRemaining, dailyLimitMinutes, autoKickCooldownSeconds, autoKickAfterMinutes per package). A rule enters the cache if it wants **any** of: the counter, the time-remaining overlay, or a time-based auto-kick. `mergeEntries` collapses multiple rules per package to the strictest reading (lowest thresholds, longest cooldown, any overlay wins).
- **`hasEntry` vs `isCounterEnabled` (v1.10.0)** — these are different questions and conflating them is a bug. `hasEntry` = "this package is tracked at all" (drives foreground/session bookkeeping); `isCounterEnabled` = `showCounter`, and is the ONLY thing that may draw or feed the interaction counter. Before the split, cache membership implied a counter, so a rule that only wanted a time-based kick (or only the time-remaining overlay) would have switched on a floating tap counter the user never asked for. Guarded by `InteractionHandlerTest."a package tracked only for a time-based kick gets no counter overlay"`.
- Auto-kick: two triggers, ONE kick. `AutoKickExecutor.kick(pkg, reason)` is the single place the kick happens — arm cooldown, go home, `resetSession`, hide the counter — so the interaction trigger (`InteractionHandler`) and the time trigger (`AutoKickTimeHandler`) can never drift in what they do to the user. It takes a `goHome` lambda rather than building the Intent itself, which keeps the policy JVM-testable and lets the service prefer `requestGoHome()` (accessibility `GLOBAL_ACTION_HOME`) over a HOME intent, as `EmergencyPassManager` already does.
- Auto-kick cooldown: configurable per-rule (default 60s). After auto-kick, re-opening the app shows a DELAY overlay for the remaining cooldown. Session counter NOT reset during cooldown.
- Time remaining overlay: optional per-rule (`showTimeRemaining`). Uses UsageStatsManager to get actual foreground time, displays remaining daily limit as color-coded overlay line.

## Time-based auto-kick + the foreground-time clock — v1.10.0 (fixes #6)

Fix for [#6](https://github.com/astraedus/nudge/issues/6): "kick you out of the app on a timer and then lock the app for x amount of time … use an app for 30 minutes, then block it for 15 minutes."

Built by EXTENDING the auto-kick machinery, not beside it: the new trigger feeds the same `AutoKickExecutor`, the same `autoKickCooldownSeconds`, the same cooldown DELAY overlay on re-entry, and the same session reset.

**The clock had to be built.** The pre-existing "30s updates" of the time-remaining overlay were a 30s *debounce* on event-driven calls (`TimeRemainingHandler.maybeUpdate` was only reached from an interaction or an app-entry) — there was no timer anywhere in the service. That is fine for counting taps and useless for passive watching, which is exactly the case #6 is about. It also meant the **daily-limit HARD_BLOCK could be late** for a passively-watched app; driving it from the new tick fixes that too.

- **`domain/autokick/TimeKickEvaluator.kt`** — pure Kotlin. `evaluate(thresholdMinutes, baselineUsageMs, currentUsageMs)` -> `DISABLED | START_SESSION | WAIT | REBASELINE | KICK`. A null/0/negative threshold is DISABLED (a stored 0 must never mean "kick after 0 minutes"); a reading BELOW the baseline is REBASELINE, not a kick (the daily total resets at midnight, and a negative elapsed time is not evidence of overstaying).
- **The session marker** lives in `InteractionTracker` alongside the interaction count: `sessionUsageBaseline[pkg]`, the `UsageProvider.getDailyForegroundTimeMs` reading taken when the session began. Elapsed session time = current reading − baseline. This choice does the work for free: `getDailyForegroundTimeMs` sums ACTIVITY_RESUMED→PAUSED spans, so **time in other apps and time with the screen off are simply not in the reading** — no wall-clock bookkeeping, no stint accounting.
- **Session semantics — deliberately identical to the interaction counter's.** The baseline is cleared in exactly the same branches that zero `sessionCounts`: on `onAppChanged` when the user has been away ≥ `SESSION_EXPIRY_MS` (5 min) and is not in cooldown, and on `resetSession` (which the kick calls). So a quick tab-out-and-back CONTINUES the budget (closing the obvious bypass), a real break restarts it, and the two triggers can never disagree about whether this is still the same sitting. Pinned by `InteractionTrackerTest` + `AutoKickTimeHandlerTest`.
- **`service/AutoKickTimeHandler.kt`** — reads the clock, advances/repairs the baseline, returns whether to kick. Deliberately does NOT kick: it runs off-main (the usage read is a binder call) while the kick touches the WindowManager, so the caller hops to Main. A failing usage read returns false — an unreadable clock must never eject a user.
- **`NudgeAccessibilityService.updateForegroundTimeTicker(pkg)`** — starts one 30s coroutine per foreground app, but ONLY when `CounterCacheEntry.needsForegroundTimeTick` (a minutes threshold, or time-remaining with a daily limit); a counter-only package spins no timer. Idempotent per package, because `evaluateForegroundPackage` is re-entered on debounced events and the issue #7 content-change fallback — restarting the job each time would keep resetting the `delay` and the clock would never tick. Started **before** the emergency-pass / cooldown / passthrough early-returns (a user who just completed a delay is precisely who this is for); stopped in `clearOverlays`, `hideAllOverlays`, `onDestroy` and immediately after a kick.
- **Each tick** re-checks `globalEnabledCached` and `EmergencyPassManager.isPassActive` (a timer is not covered by the synchronous event gate, and the daily pass promises uninterrupted minutes), then feeds the kick check and `timeRemainingHandler.maybeUpdate`.
- **Granularity**: a kick can overshoot its threshold by up to one tick (30s). Acceptable against thresholds measured in minutes, and cheaper than a tighter poll on the 3GB Pixel 3.
- **Scope**: time-kick is APP-level only — no per-feature (Reels/Shorts) minutes input, because the cache is keyed by package and a per-feature threshold would leak to the whole app.
- **Tests**: `TimeKickEvaluatorTest` (every branch incl. zero-threshold and midnight rollover), `AutoKickTimeHandlerTest` (baseline lifecycle, quick-return does not reset, real break does, cross-package isolation, failing read never kicks), `CounterCacheRefresherMergeTest` (merge + `needsForegroundTimeTick`), `InteractionHandlerTest` (shared executor; time-kick-only package gets no counter).

## Duration inputs (`ui/components/DurationInput.kt`) — v1.10.0

The auto-kick cooldown and the new minutes threshold are free-form numeric fields in MINUTES (0-1440), replacing the old 0-300s slider. The **UI state holds the raw String**, not an Int, so a blank field round-trips as blank ("off") instead of snapping back to "0".

- Storage is unchanged: `autoKickCooldownSeconds` is still seconds. `DurationInput` owns the one conversion.
- **Display rounds UP** (`cooldownSecondsToText`): the old slider was `steps = 5` over `0f..300f`, which Compose resolves to the seven stops **0/50/100/150/200/250/300** — the old code comment claiming 0/60/120/… was wrong — so 50s and 150s cooldowns exist in the wild and a minutes field can only show them rounded. Rounding down could shorten protection; 50s showing as "0" would read as off.
- **Rounding never reaches storage.** `resolveCooldownSeconds(text, originalSeconds)` / `resolveMinutes(text, originalMinutes)` return the ORIGINAL value verbatim when the field still reads what was rendered for it, so a save that did not touch the field re-persists the exact prior value. Both editors carry `original…` fields in state for this. It matters because `RuleWeakening` treats a lowered cooldown as a weakening: without it, opening an editor and saving an unrelated change could rewrite 150s→180s and raise a spurious Strict Mode challenge later.
- Turning auto-kick off PRESERVES the stored cooldown (it used to snap back to 60s) for the same reason.
- `RuleEditorViewModel.buildRule` is `internal` and pure so the whole save contract is JVM-testable (`RuleEditorRuleBuilderTest`) — including the regression that this editor used to drop `webDomains` on every save.

## Websites get the time trigger too, v1.15.2

The auto-kick machinery is keyed by an opaque `String`, never by a real package, and v1.15.2 uses
that: a blocked website is tracked as a foreground "package" named `web:<domain>`
(`domain/web/WebSessionKey.kt`), so `CounterCacheRefresher`, `InteractionTracker`'s
session/baseline/cooldown maps, `TimeKickEvaluator`, `AutoKickTimeHandler` and `AutoKickExecutor` all
work on it **unchanged**. Full rationale, and the three passthrough bugs found alongside it, are in
`web-domain-blocking.md`; what matters here is what it does to this subsystem:

- `CounterCacheRefresher.webEntriesFor` emits one entry per configured domain, and only when the
  rule's resolved WEB mode actually blocks (#21) and it carries an `autoKickAfterMinutes`. So the
  cache now holds two kinds of key, and `mergeEntries` treats them identically.
- Those entries deliberately set `showCounter = false` and `showTimeRemaining = false`. The counter
  is fed by `TYPE_VIEW_CLICKED`/`TYPE_VIEW_SCROLLED`, which arrive carrying the **browser's**
  package, so an interaction cannot be attributed to a site; and the time-remaining overlay needs a
  daily web total that does not exist (`docs/BACKLOG.md`). This is the `hasEntry` vs
  `isCounterEnabled` split doing its job, a package can be tracked for a clock without ever drawing
  a counter.
- The clock behind a `web:` key is the BROWSER's `getDailyForegroundTimeMs`, redirected by
  `WebSessionUsageProvider`. The session delta therefore still excludes time in other apps and time
  with the screen off, exactly as the app path's does.
- The web clock runs on its own `webTimeJob` in the service, NOT `foregroundTimeJob`. Browsers are
  not in the counter cache under their own package, so every browser window event runs
  `clearOverlays → stopForegroundTimeTicker()`; sharing the job would tear it down and restart it
  (re-reading usage) on each one.
- The cooldown after a web kick is armed on the domain's key. Arming it on `com.android.chrome`
  would lock every website the user has, which is the same over-blocking mistake as treating
  `CATEGORY_HOME` as "launcher".
- Tests: `CounterCacheWebEntriesTest` (9), `WebSessionUsageProviderTest` (5),
  `WebDomainEnforcementContractTest` (8, source-level).
