# Counter overlay, time-remaining overlay and auto-kick

> **WHAT FEEDS THE COUNTER CHANGED IN v1.16.0 — read `accessibility-event-pipeline.md` for that
> half.** Issue [#28](https://github.com/astraedus/nudge/issues/28): the counter was counting EVENT
> RATE (one per `TYPE_VIEW_SCROLLED` past a 500ms debounce, plus one per second of
> `TYPE_WINDOW_CONTENT_CHANGED` for unsupported packages), so one slow drag scored 5-6 and an
> untouched phone scored anything at all. It now counts item transitions from the scroll event's own
> `fromIndex`/`currentItemIndex`, the content-change proxy is gone, taps count in every app, and
> `InAppDetector.SUPPORTED_PACKAGES` no longer gates counting — it supplies the LABEL only. A session
> counts ONE unit (`CountMode.ITEMS` or `CountMode.TAPS`), never a mixture. Everything below about
> what the counter FEEDS — the overlay, both auto-kick triggers, the cooldown, the cache — is
> unchanged.

Covers the floating interaction counter, the time-remaining overlay, both auto-kick triggers
(interaction count and foreground minutes), the auto-kick cooldown, and the duration inputs that configure them.
**Read before touching `service/` overlay code, `InteractionTracker`, `CounterCacheRefresher`, `AutoKick*`, or `ui/components/DurationInput.kt`.**

## Feature summary

- **Floating interaction counter** — centered touch-through overlay (40sp counter, 16sp label, 13sp daily) showing reels/shorts scrolled or taps per session. Escalating colors: white (0-9), orange (10-19), deep orange (20-29), red with red background tint (30+). TYPE_ACCESSIBILITY_OVERLAY from service, no extra permission. Per-rule `showCounter` toggle (default ON for new rules).
- **Time remaining overlay** — per-rule opt-in (`showTimeRemaining`). Displays "42m left" or "1h 12m left" below counter, color-coded: green (>50% remaining), orange (25-50%), red (<25%). Uses UsageStatsManager for actual foreground time. Requires daily limit to be set. **The readout and the enforcement of the limit behind it are two different questions** (`showTimeRemaining` decides the first, a limit existing at all decides the second) — see "Why a plain daily limit needed its own clock, v1.18.4".
- **Auto-kick** — optional per-rule feature: sends user to home screen after N scrolls/taps in one session. Configurable threshold 5-100 (step 5, default 30). Session counter resets after kick. Stored as `autoKickAfter` on BlockRule. Requires the interaction counter (it is what feeds the count).
- **Auto-kick by time (v1.10.0)** — the second trigger, per-rule `autoKickAfterMinutes` (null = off). Kicks after N minutes of foreground time in one session. Independent of the interaction trigger — both can be set, whichever fires first kicks — and independent of the interaction counter, because its whole point is PASSIVE use (autoplaying video produces zero tap/scroll events). See "Time-based auto-kick architecture".
- **Auto-kick cooldown** — configurable per-rule, stored as `autoKickCooldownSeconds` on BlockRule. After auto-kick, returning to the app forces a DELAY overlay for the remaining cooldown. Session counter preserved during cooldown. **v1.10.0**: the 0-300s slider became a free-form MINUTES input (0-1440), so issue #6's "30 minutes on, 15 minutes off" is expressible. See "Duration inputs".

> **The overlays' windows are part of the event pipeline, not just pixels.** Both are
> `TYPE_ACCESSIBILITY_OVERLAY` windows owned by Nudge, so adding one — or setting a
> `TextView`'s text inside one — emits an accessibility event carrying our own package. Until
> v1.17.3 that was read as "Nudge is in front", which moved the block gate's foreground to Nudge
> and stopped a daily limit enforcing for as long as the user sat still
> ([#41](https://github.com/astraedus/nudge/issues/41)). Every view in both overlays is therefore
> an `AwarenessOverlayWindow` type, reporting one derived accessibility class name that
> `EventClassifier` recognises. **Never construct a bare `TextView`/`LinearLayout` in these
> managers** — `AwarenessOverlayContractTest` fails on it, and the reason is in
> `docs/architecture/accessibility-event-pipeline.md`, "Our own windows are three different things".

## Counter overlay architecture

- `InteractionTracker` (@Singleton): in-memory session/daily counts per package. No DB writes per interaction. Also tracks cooldown state per package after auto-kick.
- `CounterOverlayManager` (@Singleton): WindowManager overlay using service context (required for TYPE_ACCESSIBILITY_OVERLAY token). `setServiceContext()` called in `onServiceConnected()`. Centered on screen with escalating colors (white -> orange -> deep orange -> red) based on session count.
- `TimeRemainingOverlayManager` (@Singleton): Standalone floating overlay in top-right corner. Shows "Xm left" with color-coded text (green >50%, orange 25-50%, red <25%) and increasingly opaque background. Separate from counter overlay so both can show independently.
- `activeReelLabel`: once Shorts/Reels feature detected, skip tree inspection on subsequent scrolls. Reset on app switch.
- Tracked packages cached every 10s via `CounterCacheRefresher` (Map<String, CounterCacheEntry> with showCounter, autoKickAfter, showTimeRemaining, dailyLimitMinutes, autoKickCooldownSeconds, autoKickAfterMinutes per package). A rule enters the cache if it wants **any** of: the counter, the time-remaining overlay, a time-based auto-kick, or (**v1.18.4**) a daily time limit on its own — a budget needs the foreground clock to be enforced mid-session, not only on re-entry; see "Why a plain daily limit needed its own clock, v1.18.4". `mergeEntries` collapses multiple rules per package to the strictest reading (lowest thresholds, longest cooldown, any overlay wins).
- **`hasEntry` vs `isCounterEnabled` (v1.10.0)** — these are different questions and conflating them is a bug. `hasEntry` = "this package is tracked at all" (drives foreground/session bookkeeping); `isCounterEnabled` = `showCounter`, and is the ONLY thing that may draw or feed the interaction counter. Before the split, cache membership implied a counter, so a rule that only wanted a time-based kick (or only the time-remaining overlay) would have switched on a floating tap counter the user never asked for. Guarded by `InteractionHandlerTest."a package tracked only for a time-based kick gets no counter overlay"`. **v1.18.4 widened the question again**: a plain daily limit now puts a package in the cache too, so cache membership no longer implies the counter, the time-remaining overlay, OR an auto-kick — it implies only "something here needs the foreground clock". `CounterCacheEntry.configuresAutoKick` (`autoKickAfter != null || autoKickAfterMinutes != null`) is now the narrower, separate question "can anything here actually kick", and `NudgeAccessibilityService.hideUnwantedAwarenessOverlays` asks the entry directly what it wants rather than testing membership, for the same reason. See "Why a plain daily limit needed its own clock, v1.18.4".
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
- **`NudgeAccessibilityService.updateForegroundTimeTicker(pkg)`** — starts one 30s coroutine per foreground app, but ONLY when `CounterCacheEntry.needsForegroundTimeTick` (a minutes threshold, or — **v1.18.4** — any daily limit at all, readout or not; see "Why a plain daily limit needed its own clock, v1.18.4"); a counter-only package spins no timer. Idempotent per package, because `evaluateForegroundPackage` is re-entered on debounced events and the issue #7 content-change fallback — restarting the job each time would keep resetting the `delay` and the clock would never tick. Started **before** the emergency-pass / cooldown / passthrough early-returns (a user who just completed a delay is precisely who this is for); stopped in `clearOverlays`, `hideAllOverlays`, `onDestroy` and immediately after a kick.
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

## Why the time-based auto-kick was unreliable, v1.15.2

Device QA: a rule with a 2-minute time trigger, sat on for 2m20s, no kick. Logcat showed
`session baseline set … threshold=2min` **once** and then nothing, on the web path AND on the native
app path. It was reported as "the time auto-kick is dead", but it was never one bug, it was three,
and the third is why the first two survived so long.

**The app path was NOT a regression from the web-domain work** (verified at source level: those
commits touch none of `AutoKickTimeHandler`, `InteractionTracker`, `TimeKickEvaluator`,
`UsageRepository`, and their only hits in the service are doc comments). It has been fragile since
the trigger shipped in v1.10.0, and v1.10.0's device QA passed because it happened not to hit the
window where it breaks.

### 1. Transient system windows stopped the clock

`onAccessibilityEvent`'s `SYSTEM_PACKAGES` branch called `clearOverlays`, which called
`stopForegroundTimeTicker()`. `SYSTEM_PACKAGES` is the notification shade, permission dialogs, the
installer and the launcher, and the clock stopped for **all** of them. A heads-up notification
ended a running session's clock, and nothing restarted it until the next foreground
*re-evaluation*, which for a **browser never arrives from content changes at all**
(`handleWindowContentChanged` returns early on the browser branch). Minutes simply stopped accruing.

This is the *same grouped-constant trap* `SYSTEM_PACKAGES` already sprang on the passthrough grant
(`foreground-detection.md`): one membership test answering two different questions, "should the
awareness overlays go away" and "has the user stopped looking at this app". The launcher branch
already knows how to tell "went home" from "transient", so `clearOverlays` gained an explicit
`stopClocks` parameter and the system branch passes the answer it already computed.

### 2. One throwing tick ended the clock permanently, in silence

Both clocks were inline `while (isActive) { tick(); delay(30_000) }` loops on a `SupervisorJob`
scope. The body reaches a binder read and the WindowManager; `AutoKickTimeHandler` guards only its
own usage read, so anything else throwing left the loop **for good**, the throwing child died
alone, the scope survived, and nothing logged it or restarted it.

`service/ForegroundClock.kt` now owns the loop for both clocks: idempotent per key, immediate first
tick, **per-tick exception guard**, and start/stop/exit logged unconditionally with a reason.
`ForegroundClockTest` pins it, including a witness test that reproduces the old inline shape and
asserts it dies after one tick while the guarded one survives the identical failure.

### 3. Nothing was observable, which is why this took a device cycle to even localise

Four separate paths returned `false` with **no log**: `shouldKick`'s missing-cache-entry
`?: return false`, both gates in `tickForegroundTime`, and, the important one, 
`TimeKickEvaluator.WAIT`. WAIT is the branch a *healthy* clock spends its whole session in. So
"the clock is ticking and hasn't reached the threshold" and "the clock has been dead for ten
minutes" produced **identical logcat: nothing**, and QA correctly could not tell them apart.

This is the v1.12.0 picture-in-picture failure again, one subsystem over ("detection fired but
stayed silent" vs "detection never fired" being indistinguishable cost a whole release cycle). The
WAIT branch now logs `elapsed`, `threshold` and the raw `usage` reading, and the missing-entry case
says so. That makes the next device run conclusive in one pass: **no WAIT lines at all ⇒ the clock
is dead; WAIT lines whose `elapsed` never grows ⇒ the reading is the problem.**

### 4. …and the reading itself was the fourth copy of a loop that was supposed to be gone

`UsageRepository.getDailyForegroundTimeMs`, the clock behind *both* the auto-kick and daily budgets
, was still its own `queryEvents` walk. v1.15.1 collapsed three copies of that pairing loop into
`ForegroundSpanTracker` after the 17-hour-day incident, but this one lives outside
`ScreenTimeProvider`, so both the sweep and `ScreenTimeSourceContractTest` missed it. It carried both
defects that fix exists to remove: it filtered the stream **before** pairing (`if (event.packageName
!= packageName) continue`, so it could not see the event that ends this app's span, another app
coming forward) and it extended a still-open span to now **uncapped**. One dropped `ACTIVITY_PAUSED`
was therefore worth every minute since. On this code path that reads as a kick firing out of
nowhere, which matches the unexplained cooldown QA saw late in a long session. It now delegates to
`ScreenTimeProvider.getPerAppSessionStats`, so there is one interpretation of the event stream in the
app, with the capped-inference and one-app-at-a-time guarantees.

## Why a plain daily limit needed its own clock, v1.18.4

Measured on the bench: Calculator on a plain 1-minute daily limit (`mode=NONE`,
`showTimeRemaining=false`, no time-based auto-kick), 150 seconds of continuous foreground time,
**exactly ONE evaluation** — at t=0. The budget was never re-read, so a user who never switched away
could sit past it indefinitely; leaving and coming back tripped it immediately. `docs/BACKLOG.md` had
this open as a product-decision-owed item since 2026-09-29 ("route mid-session enforcement through the
tick path, or document re-entry as intended"). The product call landed the same day: "daily limits
should definitely be enforced even mid session."

**Why the gate excluded a plain limit.** `needsForegroundTimeTick`'s two arms were the features that
DISPLAY a running number — the time-based auto-kick and `showTimeRemaining && dailyLimitMinutes !=
null` — so the predicate read as "who needs the number refreshed", and a limit with neither an overlay
nor an auto-kick satisfied neither arm. The enforcement itself was never missing: `TimeRemainingHandler`
already hard-blocked at zero remaining, on the tick path. Nothing was driving the tick for this shape
of rule. The fix subsumes rather than adds a third arm — `dailyLimitMinutes != null` on its own,
because `showTimeRemaining && dailyLimitMinutes != null` can no longer be true without the new arm
also being true, and spelling it out twice would be a condition that never decides anything. The cache
loader's own filter (`loadCounterCacheEntries`'s `appEntries` predicate) needed the identical widening,
for the same reason `hasEntry` was widened above: without an entry, `updateForegroundTimeTicker` has
nothing to read for the package at all.

**`TimeRemainingHandler.maybeUpdate`'s two conditions had been merged into one, and that was the other
half of the bug.** The readout needs `showTimeRemaining`; the enforcement needs only a limit to exist.
Both used to sit behind `showTimeRemaining && dailyLimitMinutes != null`, which is why a plain limit
was invisible to the clock in two places at once (the cache arm above, and this check). They are now
two separate reads of `entry.dailyLimitMinutes`: one gates whether the "42m left" overlay is drawn or
cleared, the other gates the daily-usage read and the zero-remaining block, unconditionally. A
completed delay/hold/breathing passthrough grant is cleared **before** the block launches — the grant
only suppresses the event-driven evaluation path (`shouldSkipForegroundEvaluation`), and nothing on
the tick path consulted it, so without this a user would meet the block here and then walk straight
back into the app on the next event.

**The clock REPORTS, it does not enforce — and that is what fixed the missing row.** The daily-limit
`HARD_BLOCK` used to log no `UsageEvent` at all (the overlay-launch-paths gap in `docs/BACKLOG.md`),
and the reason was structural rather than an oversight: this path was a SECOND implementation of
"block this app". It first started `BlockOverlayActivity` itself (outside the launch gate, issue #31),
then built its own `launchBlockOverlay` call inside the service — and a second implementation is the
thing that drifts. `TimeRemainingHandler`'s callback now hands the FACT back and
`NudgeAccessibilityService.enforceExhaustedBudget` re-evaluates:

- **Re-derive, never trust the snapshot.** The trigger is read off `CounterCacheRefresher`, a
  10-second snapshot, so "the budget is spent" can be up to ten seconds stale. Acting on it directly
  is [#50](https://github.com/astraedus/nudge/issues/50)'s own shape from the other side: a user who
  has just RAISED their limit, or switched the rule off, would be blocked with the old one, from a
  timer, having done nothing. `EvaluateBlockUseCase` re-reads the rules, the schedule window, the
  enabled flag and the usage total, all current. (It reads the budget from the same
  `UsageRepository.getDailyForegroundTimeMs` the trigger does, so the two can only disagree about the
  LIMIT, never about the minutes.)
- **It may only ESCALATE to a HARD_BLOCK.** If the re-evaluation comes back DELAY/HOLD/BREATHING —
  i.e. the budget is not actually spent — it does nothing and says so
  (`daily limit NOT enforced … reason=rules_disagree`). Putting a countdown in front of someone who is
  already inside the app and has touched nothing would be a worse bug than the one this fixes; same
  fail-toward-nothing direction as the rest of the clock.
- **Everything else comes free**, which is the point: the launch gate, grayscale, the
  `claimConfrontation` arrival invariant and the `wasBlocked` row are `handleDecision`'s, unchanged.
  So the row is ONE per arrival however many times the clock re-fires, and the claim refuses a ROW
  and never a block — someone still sitting in an exhausted budget goes on meeting the limit screen,
  they are just not counted again for it (issue #36).
- There are now THREE `launchBlockOverlay` call sites, not four (`BlockOverlayLaunchContractTest`
  floors it at three): the rule block, the auto-kick cooldown, the web auto-kick cooldown. **Stat-semantics consequence, stated plainly**: from v1.18.4 on, daily-limit blocks
appear in the dashboard "Blocked" tile and the insight pages; history from before this version does
not contain them, so a week spanning the upgrade is not comparable to one entirely on either side of
it. The auto-kick cooldown's DELAY overlay is the other of the two paths that gap named, and it still
logs nothing — left open in `docs/BACKLOG.md`, deliberately, because fixing it changes stat semantics
again and is its own product call.

**`CooldownGate`'s authority narrowed, because the widened cache made its old authority wrong.** It
used to be "the counter cache holds an entry for this package" — a good enough proxy while everything
in the cache was an auto-kick or an awareness overlay. Once a daily limit alone earns an entry,
membership no longer answers "can anything here kick" — a user who turned auto-kick off while keeping
a daily limit would still have "an entry", and the armed cooldown would have gone on ejecting them from
an app nothing is configured to kick out of any more. `CounterCacheEntry.configuresAutoKick`
(`autoKickAfter != null || autoKickAfterMinutes != null`) is the narrower, and now only, evidence; both
call sites (the app path in `evaluateForegroundPackage` and the web path in `enforceWebCooldown`)
read it instead of bare membership.

**Awareness overlays needed the same correction.** `clearOverlays` already hid both overlays for a
package with *no* cache entry; nothing hid them for a *tracked* package that wants neither — reachable
before v1.18.4 only for a time-kick-only rule, and far more common now that a plain daily limit is
tracked too. Walking from an app with the counter showing into one with only a budget left the
previous app's counter floating on top of it. `hideUnwantedAwarenessOverlays` closes this by asking
the entry what it wants (`isCounterEnabled`, `showTimeRemaining`) rather than testing membership — the
same "ask the entry, don't infer from `hasEntry`" correction the `configuresAutoKick` split makes.

**A REBIND USED TO END THE CLOCK FOR THE REST OF THE SITTING, and the new device case found it.**
The clock is only ever started from `evaluateForegroundPackage`, i.e. from a window EVENT. A user
sitting still produces none, and a rebind destroys the service instance and cancels the clock with
it — so from that moment until they next switched apps there was no clock at all. Silent in every
sense: nothing logged, nothing on screen, the time-based auto-kick simply stopping. Measured while
building `daily-limit-midsession`, where one UI-tree dump from the harness was enough to cause it:

```
15:17:44.905  adbd … 'ui automator dump …'
15:17:45.634  app clock stopped key=…calculator reason=service_destroyed
15:17:46.806  accessibility service connected
              (no clock again for the remaining 100 seconds of the sitting)
```

`onServiceConnected` now calls `restartForegroundClockAfterRebind()`, which reads the LIVE window
(`rootInActiveWindow`, because a rebind is a new instance with no remembered `lastPackage`) and
restarts the clock if that package needs one — **after** the eager `forceRefresh`, since before it
the cache is empty and the restart would silently do nothing. The CLOCK only, never a full
re-evaluation: a rebind is not evidence the user did anything, which is exactly the call
`PassthroughManager.onObservationResumed` already makes, so this restores observation and lets the
ordinary tick decide, gated and counted like any other block. Pinned by
`ServiceLifecycleContractTest."a rebind restarts the foreground clock, after the cache is populated"`,
which asserts the ORDER, because a restart placed before the populate looks like a fix and is not.

This predates v1.18.4 — the time-based auto-kick and the time-remaining overlay have had it since
v1.10.0 — but a daily limit is a far more common rule shape, so the fix ships with the feature that
made it matter.

**Granularity, stated where a user can be told it.** Enforcement rides the clock that already exists,
so the block lands within one `FOREGROUND_TICK_MS` (30s) of the budget crossing zero — the same
overshoot the time-based auto-kick documents above, and cheaper than a tighter poll on a 3GB Pixel 3.
The rule editor and the app-config screen now say "the block lands as soon as the budget runs out,
even if you're still inside the app", which the pre-v1.18.4 behaviour did not support.

**Tests, per layer.** L1: `CounterCacheRefresherMergeTest` (the gate predicate — a plain limit ticks,
a limit ticks with or without the readout, the merge keeps it ticking, and the COUNTERFACTUAL that a
rule with no limit / no overlay / no auto-kick still spins no timer, which is the battery half),
`CooldownGateTest` and `CounterCacheRefresherMergeTest`'s `configuresAutoKick` rows,
`TimeRemainingHandlerTest` (a plain limit is enforced with no readout drawn; no limit is never
enforced; a completed delay grant cannot outlive the budget). L2/L3:
`InterventionCountReplayTest` — the tick path in the #36 row model: one row for a budget that runs out
mid-session, one row across 40 clock-driven re-launches while `launches` climbs past 40, two rows for
a genuine leave-and-return. Source level: `BlockOverlayLaunchContractTest` (the clock's callback
launches, claims and logs NOTHING itself; `enforceExhaustedBudget` re-evaluates and may only escalate
to a HARD_BLOCK; one `wasBlocked` writer in the service, still). L6:
`scripts/device-qa.sh daily-limit-midsession`, which sits in Calculator with a derived budget and
asserts the limit screen arrives without leaving the app, for exactly +1 on the Blocked tile.
