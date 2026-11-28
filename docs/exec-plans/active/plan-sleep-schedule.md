# Design spec: Sleep schedule (overnight block, released by the first real use)

Status: **draft, for review. Nothing is built.** Follow-up to [#171](https://github.com/astraedus/nudge/issues/171), on top of the first-unlock schedule from #155 and the boundary work in #169/#170 ([#177](https://github.com/astraedus/nudge/pull/177)). Section references to spec 4.3 and 5.4 are to [the groups plan](groups-categories-allowlist.md).

## 1. The problem

#155's anchor is the first unlock at or after `day_start_minute`. A time check at 5:00 is that unlock: the window opens, 30 minutes burn while the user is asleep, and an 8:00 wake has no Morning Lock. The only workaround is a later day start, which is a single fixed hour: it cannot fit someone who wakes at 4:00 and someone who wakes at 9:00, and moving it also moves the logical weekday.

The use case is narrower than "block the phone overnight":

- The target is the **dopamine apps** (Instagram, Facebook, the browser).
- A small **allowed list** (Bible, calendar, maps) is reasonable to check at 3:00 and will not stop the user going back to sleep.
- The user may go to bed at 11:30, or refuse to. The night block is the definite sleep time they asked for.

Non-goals: a bedtime reminder, a per-app sleep schedule, any change to the first-unlock kind, and a user-editable global always-allowed list. The essentials stay fixed (spec 5.4).

## 2. The model

Sleep is a fourth "When" kind: one interval that starts at a clock time and is released by the first real use.

```
nightStart = the latest selected evening's start at or before now
nightEnd   = the first occurrence of sleepEndMinute strictly after nightStart, atZone
firstUse   = the persisted first time at or after nightEnd that THIS rule decided a covered app in front
active     = now < nightEnd
          || (firstUse exists && firstUse < noonOnDayOf(nightEnd) && now < firstUse + N)
```

- **Night block**, `[nightStart, nightEnd)`. Blocks by default, whenever the night starts.
- **Morning delay**, from `nightEnd` to `firstUse + N`, claimable only until local noon on the day `nightEnd` falls. A first blocked open after noon is too late to be a morning delay and gets none.
- **Allowed apps and essentials** are usable for the whole window, night included. They never trigger the delay.
- **Days** select the evening the night belongs to.

`endsAt` (the block screen's "Until") is `nightEnd` during the night, `firstUse + N` during the delay, and null when inactive. The boundary timer and the overlay both read it, so the screen cannot outlive the block (#170).

| The user does | Result |
|---|---|
| Glances at 5:00 (AOD, lock screen, clock), back to sleep | Nothing triggers. First blocked app at 8:00 is blocked until 8:30. |
| Opens the Bible at 5:00 | Allowed, no trigger. First blocked app at 8:00 is blocked until 8:30. |
| Opens Instagram at 5:05 | Triggered and blocked until 5:35, then usable. At 8:00, no fresh delay. |
| Was already in Instagram at 4:58 | At 5:00 the boundary re-check records the first use and the screen stays, its "Until" moving to 5:30. |
| Opens Instagram at 3:00 | Night block holds until 5:00. It is not a trigger, because it is before `nightEnd`. |
| Never uses a blocked app | Blocked until 5:00, then the first blocked attempt gets its `N`. |
| Up all night | Blocked 23:00 to 5:00 by default. The 4.3 passes apply unchanged (see below). |
| Saturday morning after a Friday-only rule | Blocked until Saturday 5:00 plus the delay. |
| First blocked app at 14:00 | No delay: local noon has passed. The next night is the next chance. |
| A third-party alarm rings at 4:30 or 7:00 | A window while the keyguard is showing is not a use and is not decided for Sleep (section 3). |

**The 4.3 passes apply in both phases.** A completed delay's passthrough, a sitting that earned a pass at 22:50, and the daily 2-minute pass all keep working exactly as spec 4.3 says, including across 23:00 and at 3:00. Ending a pass at `nightStart` would be F4, a separate question for the owner, not something this schedule changes.

The fixed `nightEnd` is what makes it work. Without it, a 3:00 app use would end the night at 3:30 and leave the phone open until morning.

## 3. "First blocked use"

The trigger is not only a new app window. It is the first time at or after `nightEnd` that **this rule decides a covered app in front**, which includes the boundary re-check at 5:00. That is what carries a 4:58 attempt over the boundary: at 5:00 the re-check sees Instagram in front, records the first use, and the screen stays for the delay instead of closing into the app.

The package must be:

1. covered by **this** rule: the app itself, a group it belongs to, or "All apps" minus the rule's allowed list, and
2. not essential (`EssentialApps`: phone, messages, clock, launcher, keyboard, Settings, accessibility, autofill, Nudge), and
3. not in this rule's own allowed apps or allowed groups.

Only this rule's coverage matters. Another rule's decision on the same app does not, so the trigger cannot move when an unrelated rule changes (D2).

A lock-screen or AOD glance, a notification, the home screen, an IME, Settings and the Clock app never trigger: none is a covered app window. An allowed app (Bible, Spotify) does not trigger by design.

**Keyguard windows.** A window shown while the keyguard is showing (an alarm over the lock screen, an incoming call) is not in front of the user, so Sleep neither triggers on it nor blocks it. The system Clock is essential. For a third-party alarm app, the Sleep editor carries one line: "If you use a third-party alarm, allow it below." That is the honest fix; making every alarm app essential is a different decision.

## 4. Days, clocks, travel

- **Days** are the evenings the night belongs to, ISO 1..7. A Friday-only rule covers the Friday night and its Saturday morning delay; it does not cover Saturday night.
- **A start after midnight still belongs to the previous evening.** With `sleepStart < sleepEnd` (00:30 to 8:00), the night begins on the calendar day *after* the selected evening: ticking Friday means 00:30 Saturday to 8:00 Saturday. With `sleepStart > sleepEnd` (23:00 to 5:00) it begins on the selected evening itself. `nightEnd` is always the first occurrence of `sleepEndMinute` strictly after `nightStart`, through `atZone`, never a duration added to the start.
- **Validation**: start and end 0..1439, start != end, and the night is at most 14 hours (`nightEnd - nightStart`). A 22:30 to 01:00 night is 2.5 hours and valid; a 02:30 to 01:00 night passes a naive `start > end` check but is 22.5 hours and is rejected.
- **Independent of `day_start_minute`.** Sleep does not read the logical-day setting and its editors do not show the "New day starts at" row. The window crosses midnight by construction.
- **DST.** Start and end are wall-clock minutes in the current zone; `atZone` resolves a spring-forward gap forward and a fall-back overlap to the earlier offset. A 23:00 to 5:00 night is therefore 5 or 7 real hours on a transition night, and the delay stays absolute.
- **Travel.** Recomputed in the zone the device reports now, like the day start. A zone change can end the night early (allows) or extend it (blocks longer); both are tested with explicit `ZoneId`s.
- **Clock jumps.** A forward jump ends the night and the delay early; a backward jump extends both. That is the opposite direction to the first-unlock stamp, which discards a future stamp. The difference is deliberate: the night is a clock window, so it follows the clock, and a backward jump can only block longer within a window that still ends at the essentials-only floor.

## 5. Data model, migration, persisted first use

DB 16 to 17, additive:

| Table | Column | Type | Meaning |
|---|---|---|---|
| `block_rules` | `sleepStartMinute` | `INTEGER NULL` | Non-null makes this a Sleep rule |
| `block_rules` | `sleepEndMinute` | `INTEGER NULL` | Must be set with the start |
| `block_rules` | `sleepDelayMinutes` | `INTEGER NULL` | `N`, the morning delay |

- **Kind inference**: `sleepStartMinute != null` means Sleep. `firstUnlockMinutes != null` stays first unlock, exactly as today. No existing reader changes meaning, and in particular `RuleArming` still arms only a first-unlock rule, so turning a Sleep rule into a Morning Lock at 8:05 arms it correctly.
- **Validation**: the three values as in section 4; the repository rejects a half-set Sleep row, like the one-target invariant.
- **`armedAt` is not read by Sleep.** A night block is clock-based: saving a Sleep rule at 23:30 blocks the rest of that night. The "Starts at your next first unlock" line is not shown for this kind.
- **`firstUseAt` is persisted per rule**, one DataStore key holding rule id to epoch ms (a small CSV or JSON map, written inside one `edit {}`). A value before the current `nightEnd` is stale, and a value in the future is discarded, the same two rules as the first-unlock stamp. It is device-local and never exported. In memory this was wrong: any process restart before the next night re-armed a whole new delay, and the persisted value has the same stale and future rules as the first-unlock stamp.
- No other preferences. The day start remains unrelated.

## 6. Engine and service

- **`domain/engine/SleepWindow.kt`** (new, pure): `isActive`, `endsAt`, `nightStart`, `nightEnd` and `nextBoundary`. Explicit `ZoneId` in every test, like `FirstUnlockWindow`.
- **`ScheduleEvaluator`** gains the branch when `sleepStartMinute != null`, and `ScheduleBoundaries` gains Sleep's two boundaries: `nextStartAt` is the next `nightStart` (a clock start, unlike first-unlock) and `windowEndAt` is `endsAt`. `SleepWindow.nextBoundary` returns `nightEnd` during the night, `firstUse + N` during the delay, and the next `nightStart` when inactive. So a rule needs no sleep-specific timer; it feeds the one next-boundary timer from #169/#170.
- **Re-arming.** When the first use is recorded, the service calls `boundaryWatch.rearm` so the delay's end becomes the next boundary, the same way a first-unlock stamp change re-arms today.
- **The trigger lives in the service**, on both an app window and the boundary re-check, before the decision: if the rule is a Sleep rule past `nightEnd` before local noon, the package is covered by it, the keyguard is not showing and no first use exists for this cycle, record `firstUseAt = now`, persist it, re-arm, then evaluate. The app is blocked in the event that triggered it.
- **No separate timer.** The night end, the delay end and the next night start are the same clock boundaries every schedule already uses; when the rule needs no clock (not sleeping), it posts none, #63's rule.
- **Cost.** One map lookup and one coverage check per app window for Sleep rules, in-memory coverage and essentials, no database reads except the persisted write on the trigger itself.

## 7. UI

A fourth option under "When": **Sleep**.

```
○ Sleep
    From   [11:00 PM]   To   [5:00 AM]
    Then   [15 min] [30 min] [1 h] [Custom] after the first blocked app
    Days   [M][T][W][T][F][S][S]
```

- The day-start row is hidden for this kind, because the logical day is not used.
- A short hint under Days: "The night starts on these days."
- Saving mid-night shows "Starts now, until 5:00 AM." in place of the first-unlock arming line.
- Summary: `11:00 PM–5:00 AM, then 30 min after first use`, with the day prefix when set: `Mon–Fri 11:00 PM–5:00 AM, then 30 min after first use`.
- Group and All apps editors in v1. App config's Scheduled Override already has a two-segment row and a third would be cramped; deferred (D1), with the same fields added later if wanted.
- Copy is literal: what it does, when, no sleep-hygiene advice.

## 8. Strict Mode

- **`RuleWeakening`** gains a Sleep axis: a later start, an earlier end, a shorter delay, or fewer days is weakening. Later and earlier are compared in one frame, minutes since the start of the selected evening, so 23:00 is 1380 and 00:30 is 1470 and an after-midnight start compares correctly.
- **A change of kind involving Sleep** is weakening, the same conservative reading as the first-unlock kind.
- **Arming.** Not used by Sleep, so a save mid-night applies immediately; that is the point of a bedtime. The first-unlock arming rules are untouched, and the separate delay column keeps `RuleArming`'s "newly first-unlock" test correct.
- **The global day start.** Sleep does not read it. Because `firstUnlockMinutes` now means first unlock only, `SetDayStartMinuteUseCase` needs no exclusion: a sleep-only phone moves the day start freely.

## 9. Export and import

- A Sleep rule rides in its own top-level **`sleepRules`** array, never in `rules` or `extendedRules`.
- Why: 1.28.0 already reads `extendedRules`, and its `parseExtendedRule` accepts an app or group entry carrying `firstUnlockMinutes`, ignoring keys it does not know. A Sleep rule written there would restore as "30 min after first unlock", and an All apps entry without the delay would restore as an All apps block all the time. A top-level key an older importer does not know is dropped by every shipped version, the same mechanism as `history` and `settings`.
- Each entry has the usual target (package, group or All apps), mode, enabled and allowed lists, plus `sleepStartMinute`, `sleepEndMinute` and `sleepDelayMinutes`. Never `firstUnlockMinutes`, and never `armedAt`.
- Envelope stays version 1. `sleepRules` is written only when there is one.
- **Tests, both readers**: a pre-1.28 reader that drops `extendedRules` must restore every other rule and no Sleep rule; and 1.28.0's `parseExtendedRule` run over the new file must produce no Sleep rule from it (the key is unknown to it, and nothing about Sleep sits in `rules` or `extendedRules`). Plus the round trip and per-entry validation.
- No new settings. `first_unlock_at` and the persisted first use stay device-local.

## 10. Tests

- **L1 `SleepWindowTest`**: night boundaries; a selected-evening start; an after-midnight start; the 14-hour validation; no use all night; first use at 5:05 and at 8:00; back to sleep; a 3:00 use does not trigger; a 4:58 app carried over the boundary; the noon claim cut-off; a stale and a future persisted first use; DST spring and fall (5 and 7 real hours); travel mid-night both directions; clock jumps both ways; `nextBoundary` across midnight and across a DST transition.
- **L1 `SleepTriggerTest`**: covered app triggers; allowed app, allowed group, essential and system surface do not; a keyguard window neither triggers nor blocks; another rule's decision is irrelevant.
- **L3 form and ViewModel**: round trip for the three fields and days, the summary, the two hints, Strict Mode decisions, the day-start fact unaffected.
- **L4**: migration 16 to 17 with its lists; export round trip; both older-reader tests; validation of a half-set or out-of-range Sleep row; the persisted first-use read-modify-write.
- **L5 source contracts**: one trigger site, one first-use write, the receiver unchanged, the `rules` and `extendedRules` parsers ignoring the Sleep keys.
- **L6 device QA** on the moto: every row in section 2, plus an app already open across 23:00, a block screen up across 5:00, a block screen up across the delay's end, and a process kill inside the delay.

## 11. Build order

Depends on #169/#170 (#177), which is on main.

1. **Engine and data**: migration, fields, `SleepWindow`, evaluator and boundary wiring, trigger and persisted first use, Strict Mode axes, tests. Inert without UI, and shippable.
2. **Editors and backup**: the When option, hints, summary, `sleepRules` export, docs.
3. **Device QA**, then the #171 case matrix.

Two PRs, or one if the first is small after review; the split keeps the UI out of the engine review.

## 12. Risks and open questions

- **The noon claim** is a product choice (D7): it stops a Friday-only rule handing out a delay on Tuesday, and stops a 22:30 first use from running into the night. Noon may be too late for a very early riser or too early for a late one.
- **Multiple Sleep rules** keep independent first-use state; a rule on an app and a rule on All apps do not share a trigger.
- **Travel and clock jumps** can extend the night. Bounded by the essentials floor; not solved.
- **The user who never sleeps** is blocked 23:00 to 5:00 by design.
- **App config** coverage is deferred (D1); a small follow-up on the same fields.
- **Third-party alarms** are the user's to allow; the editor says so.
- **Nuke** still wins, as today. Sleep adds no exception.

## 13. Decisions for the owner

- **D1** Sleep appears in the group and All apps editors first; App config later.
- **D2** The trigger is this rule's own coverage only, not the engine's full decision.
- **D3** First use is persisted per rule in one DataStore key, stale before the current night end and future values discarded.
- **D4** Its own `sleepDelayMinutes` column; `firstUnlockMinutes` keeps meaning first unlock.
- **D5** No arming for Sleep; a save mid-night applies immediately.
- **D6** DB 16 to 17, additive; three columns.
- **D7** The morning delay is claimable only until local noon on the day `nightEnd` falls.
- **D8** Sleep rules export in their own `sleepRules` array.

## 14. Alternatives considered

- **Two rules, night plus morning, with an allowed-wins release.** Rejected on principle, not on the count: the v1 editor creates one All apps rule, two imported rows can both be active (spec 4.2's matrix), but a rule that lifts another is the shape the spec avoids.
- **A pure rolling window without a fixed end.** Rejected: a 3:00 use would end the night at 3:30 and leave the phone open until morning.
- **Making every unlock the anchor (a global change to #155).** Rejected: it alters a merged, user-facing promise; Sleep is additive.
- **A longer day start.** Rejected: one fixed hour cannot fit 4:00 and 9:00 wakers, and it shifts the logical weekday.
- **Reusing `firstUnlockMinutes` for the delay.** Rejected: ten readers on main treat it as the first-unlock marker, and `RuleArming` would silently stop arming a rule turned from Sleep into a Morning Lock.
- **Sleep in `extendedRules`.** Rejected: 1.28.0 reads that array and would restore the rule with the wrong schedule.
