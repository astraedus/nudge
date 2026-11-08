# Plan: schedule boundaries (#169, #170)

Status: built (lane T, 2026-11-08). Docs: `docs/architecture/rules-and-features.md` "Schedule boundaries", `docs/architecture/block-overlay-lifecycle.md` "A block that ends under its screen".

## The two bugs

- **#169.** An app already in front stays usable after a schedule covering it starts. Nothing
  re-evaluates the foreground at a boundary, and a user sitting still in one activity generates no
  window event.
- **#170.** A block screen stays up after its block ended: at a schedule's end, and for a spent daily
  limit past midnight. A spent limit on an all-the-time rule has `until == null`, so there is no end
  to watch. Also, "Use for 2 minutes" spends the once-a-day pass even when the block is already over.

## Mechanism

1. **Pure time math (L1).**
   - `ScheduleWalk` walks ABSOLUTE minutes and reads the local wall clock at each one through the
     zone's own offset transitions. A DST gap or overlap therefore answers exactly what
     `isActiveAt` would answer at that instant.
   - `ScheduleEvaluator.nextStartAt` sits beside `windowEndAt`, and `windowEndAt` now uses the same
     walk. That fixes its hour error across a DST change: the old walk counted wall minutes and
     added them as absolute ones.
   - `ScheduleBoundaries.next` is the next instant over all enabled rules at which any rule's answer
     can change. That covers a schedule start, a schedule end, a first-unlock window's end, and the
     next local midnight when any rule carries a daily limit. A first-unlock START is an unlock
     event, already handled by `reevaluateForegroundForFirstUnlock`, so it is not a timer.
2. **One timer (`ScheduleBoundaryWatch`).**
   - A main-looper Handler posts ONE task at the next boundary. When there is no boundary it posts
     nothing (#63). There is no per-minute tick.
   - It re-arms when the rule snapshot changes, when Nudge is switched on or off, when the
     first-unlock stamp moves, on TIME_SET / TIMEZONE_CHANGED / DATE_CHANGED, on screen on, and
     after every fire.
   - **Doze.** A Handler counts uptime, so it does not advance in deep sleep. The watch keeps the
     WALL-clock target. Screen on re-arms it from the wall clock. A boundary that passed while the
     screen was off, or while the keyguard was up, is kept pending and re-checked at unlock
     (USER_PRESENT, or screen on with no keyguard).
   - Why not AlarmManager: an exact alarm needs SCHEDULE_EXACT_ALARM on 31+. An inexact alarm can
     be late by many minutes. An alarm also wakes the CPU in Doze for a decision that only matters
     once the screen is on, which is #63's battery shape. The phone nudge's timer made the same
     call.
3. **The re-check (#169).** At a boundary the service reuses `reevaluateForegroundForNuke`'s shape.
   It reads the active window, skips our own package and an overlay that is up, spends the
   debounce, and calls `evaluateForegroundPackage`. Every earned grant (`grantLetsThrough`)
   therefore still applies first: decision 2, groups spec :534-537. The block arrives exactly as a
   fresh open would show it.
4. **The block screen at its end (#170).**
   - `BlockDecision.Block.endsAt` is `until`. For a spent daily limit it is also capped at the next
     local midnight, even when the rule has no window (`budgetResetAt`). The `until` display is
     unchanged.
   - The service passes `endsAt` to the overlay as `EXTRA_ENDS_AT`.
   - The overlay waits for it under `repeatOnLifecycle(STARTED)`. That timer is lifecycle-gated
     (#8): a stopped overlay has already finished itself.
   - At the end, `OverlayLifecycle.onBlockEnded` returns `[MarkOverlayInactive, Finish]` with no
     grant. The finish pops the app the user had opened back to the front, and its window event is
     evaluated fresh. If another rule covers the app, that block shows through the one launch site.
     If nothing does, the user is back in the app.
   - The end is refused after a walk-away (finishing would pop the app the user just declined,
     #26), after a hand-off (#71), and for a stale delivery.
5. **The daily pass (#170).** `OverlayLifecycle.onEmergencyPassTapped(inForce)` uses the pure
   `BlockInForce.isInForce(endsAt, now)` check. A block that is over closes like the end above and
   never spends the pass.

## Shared with limits (#146, Lane G)

Only the next local midnight, as the end of a spent daily limit (`DayRollover.nextLocalMidnight`, also
used by `BlockEngine.budgetUntilOf`). No group-limit logic. `CounterCacheRefresher.appEntryFor` is
untouched.

## Not covered

- In-app feature rules (Shorts/Reels) at a boundary: the front is re-evaluated as a whole app. The
  feature detectors catch the feature on the next content event, as today.
- A rule edited or switched off while its block screen is up: the screen still ends at its clock
  end, as today.
- A wall-clock change while a block screen is up: the overlay's wait is uptime-based. The screen
  goes on to its end and the next window event corrects it.

## Tests

L1 for the walk, next start, boundaries, `endsAt` and in-force. L3 for `OverlayLifecycle` and the
watch, plus the trigger path from the watch's fire through to the use-case decision. Source contracts
cover the wiring. L6 adds two `scripts/device-qa.sh` cases.
