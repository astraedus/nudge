# Design spec: Sleep schedule (overnight block, released by the first real use)

Status: **draft, for review. Nothing is built.** Follow-up to [#171](https://github.com/astraedus/nudge/issues/171), on top of the first-unlock schedule merged in #155 (`24ffffe`) and the #107/#109 direction.

## 1. The problem

#155's anchor is the first unlock at or after `day_start_minute`. A time check at 5:00 is that unlock: the window opens, 30 minutes burn while the user is asleep, and an 8:00 wake has no Morning Lock. The only workaround is a later day start, which is a single fixed hour: it cannot fit someone who wakes at 4:00 and someone who wakes at 9:00, and moving it also moves the logical weekday.

The use case is narrower than "block the phone overnight":

- The target is the **dopamine apps** (Instagram, Facebook, the browser).
- A small **allowed list** (Bible, calendar, maps) is reasonable to check at 3:00 and will not stop the user going back to sleep.
- The user may go to bed at 11:30, or refuse to, which is their business; the night block is the definite sleep time they asked for.

Non-goals: a bedtime reminder, a per-app sleep schedule, any change to the first-unlock kind, and a user-editable global always-allowed list. The essentials stay fixed (spec 5.4).

## 2. The model, in one paragraph

Sleep is a fourth "When" kind, a single interval that starts at a clock time and is released by the first real use:

```
nightStart = the most recent sleep start on a chosen day, at or before now
nightEnd   = nightStart + (sleep end - sleep start, crossing midnight)
firstUse   = the first app open at or after nightEnd that THIS rule would block
active     = now < nightEnd
          || (firstUse exists && now < firstUse + N)
```

- **Night block**, `[nightStart, nightEnd)`. Always blocks, whatever the user does inside it. Using an app at 3:00 does not shorten it.
- **Morning delay**, from `nightEnd` to `N` minutes after `firstUse`. The app that triggers it is blocked on that same event; if there is no use, the block ends at `nightEnd` and the next blocked app open starts a fresh `N`.
- **Allowed apps and essentials** are usable for the whole window, night included. They never trigger the delay either.
- **Days** select the evening the night starts.

| The user does | Result |
|---|---|
| Glances at 5:00 (AOD, lock screen, clock), back to sleep | Nothing triggers. First blocked app at 8:00 is blocked until 8:30. |
| Opens the Bible at 5:00 | Allowed, no trigger. First blocked app at 8:00 is blocked until 8:30. |
| Opens Instagram at 5:05 | Triggered and blocked until 5:35, then usable. At 8:00, no fresh delay. |
| Opens Instagram at 3:00 | Night block holds until 5:00. It is not a trigger, because it is before `nightEnd`. |
| Never uses a blocked app | Blocked until 5:00, then free until the first blocked attempt, which gets its `N`. |
| Up all night | Blocked 11:00 to 5:00 regardless. |
| Saturday morning after a Friday-only rule | Blocked until Saturday 5:00 plus the delay. |

The difference from a pure rolling window is the fixed `nightEnd`. Without it, a 3:00 app use would end the night at 3:30 and leave the phone open until morning. The fixed end is the "definite sleep time"; only the morning delay is anchored to use.

## 3. "First blocked use"

The trigger is one app window (never an unlock), and only if its package is:

1. covered by **this** rule: the app itself, a group it belongs to, or "All apps" minus the rule's allowed list, and
2. not essential (`EssentialApps`: phone, messages, clock, launcher, keyboard, Settings, accessibility, autofill, Nudge), and
3. not in this rule's own allowed apps or allowed groups.

Only this rule's coverage matters. Another rule's decision on the same app does not, so the trigger cannot move when an unrelated rule changes. This is decision D2 below.

A lock-screen or AOD glance, a notification, the home screen, an IME, Settings and the Clock app never trigger: none is a covered app window. An allowed app (Bible, Spotify) does not trigger by design.

## 4. Days, clocks, travel

- **Days** are the evenings the night starts, ISO 1..7. A Friday-only rule blocks Friday 23:00 to Saturday 5:00 and the Saturday morning delay. It does not block Saturday 23:00.
- **Independent of `day_start_minute`.** Sleep does not read the logical-day setting and its editors do not show the "New day starts at" row. The window crosses midnight by construction, so there is no logical-day split to reason about.
- **DST.** Start and end are wall-clock minutes in the current zone; `LocalDateTime.atZone` resolves a spring-forward gap forward and a fall-back overlap to the earlier offset. A 23:00–05:00 night is therefore 6 or 7 real hours on a transition night, and the delay stays absolute.
- **Travel.** Recomputed in the zone the device reports now, the same rule the day start follows. A zone change can end the night early (allows) or extend it (blocks longer); both are tested with explicit `ZoneId`s.
- **Clock jumps.** A forward jump ends the night and the delay early; a backward jump extends both. That is the opposite direction to the first-unlock stamp, which discards a future stamp. The difference is deliberate: the night is a clock window, so it follows the clock, and a backward jump can only block longer within a window that still ends at the essentials-only floor. Bounded harm, but stated as a risk in section 12.

## 5. Data model and migration

DB 16 to 17, additive:

| Table | Column | Type | Meaning |
|---|---|---|---|
| `block_rules` | `sleepStartMinute` | `INTEGER NULL` | Non-null makes this a Sleep rule |
| `block_rules` | `sleepEndMinute` | `INTEGER NULL` | Must be set with the start |
| `block_rules` | `firstUnlockMinutes` | existing | Reused as `N`, the morning delay |

- **Kind inference**: `sleepStartMinute != null` means Sleep (and `firstUnlockMinutes` is its delay); otherwise `firstUnlockMinutes != null` means first unlock; otherwise times mean days and times; otherwise all the time.
- **Validation**: both start and end set, 0..1439, `start > end` (an overnight window), delay 1..240. The repository rejects a half-set Sleep row, like the one-target invariant.
- **`armedAt` is not read by Sleep.** A night block is clock-based: saving a Sleep rule at 23:30 blocks the rest of that night. The "Starts at your next first unlock" line is not shown for this kind.
- **First-use state is in memory**, per rule, in the service (`Map<ruleId, Long>`): set when the trigger fires, cleared when a new `nightStart` passes or the process restarts. No migration, no preference. Trade-off (D3): a process death inside the delay re-arms on the next blocked attempt, so the user can be blocked for up to `N` extra. Acceptable for v1; persisting it is a follow-up if it bites.

## 6. Engine and service

- **`domain/engine/SleepWindow.kt`** (new, pure): `isActive(nowMs, sleepStartMinute, sleepEndMinute, delayMinutes, days, firstUseAt, zone)`, plus `nightStart` and `nightEnd` helpers. Explicit `ZoneId` in every test, like `FirstUnlockWindow`.
- **`ScheduleEvaluator`** gains the branch when `sleepStartMinute != null`; `ActiveRule` and `BlockRuleData` carry the two new fields.
- **The trigger lives in the service**, not in the pure window: on a `ForegroundSignal.AppWindow`, if the rule is a Sleep rule in its morning phase, the package is covered by it, and no first use exists for this cycle, record `firstUseAt = now` and re-evaluate the front app on Main. That is the same shape as #155's `reevaluateForegroundForFirstUnlock`, generalised to "rule state moved"; the app is blocked in the event that triggered it.
- **No timers.** The night end and the delay end are clock comparisons checked on events, like the first-unlock window. Nothing needs to wake the device.
- **Cost.** One map lookup per app window for Sleep rules, in-memory coverage and essentials, no database reads. The hot path is untouched until a Sleep rule is enabled, and the trigger fires once a night.

## 7. UI

A fourth option under "When": **Sleep**.

```
○ Sleep
    From   [11:00 PM]   To   [5:00 AM]
    Then   [15 min] [30 min] [1 h] [Custom] after the first blocked app
    Days   [M][T][W][T][F][S][S]
```

- The day-start row is hidden for this kind, because the logical day is not used.
- Summary: `11:00 PM–5:00 AM, then 30 min after first use`, with the day prefix when set: `Mon–Fri 11:00 PM–5:00 AM, then 30 min after first use`.
- Group and All apps editors in v1. App config's Scheduled Override already has a two-segment row and a third would be cramped; deferred (D1), with the same fields added later if wanted.
- Copy is literal: what it does, when, no sleep-hygiene advice.

## 8. Strict Mode

- **`RuleWeakening`** gains a Sleep axis: a later start, an earlier end, a shorter delay, or fewer days is weakening. A change of kind involving Sleep is weakening, the same conservative reading as the first-unlock kind.
- **Arming.** Not used by Sleep, so a save mid-night applies immediately; that is the point of a bedtime. The first-unlock arming rules are untouched.
- **The global day start.** `SetDayStartMinuteUseCase` currently treats any enabled first-unlock rule as a reason to challenge a day-start change. Sleep does not read the day start, so the fact it passes must exclude Sleep rules; a sleep-only phone can move the day start freely.

## 9. Export and import

- A Sleep rule rides in **`extendedRules`**, whatever its target. An older Nudge reading it from `rules` would import it as always-on, which is the wrong direction for a bedtime.
- The extended entry carries `sleepStartMinute`, `sleepEndMinute` and `firstUnlockMinutes` (the delay), plus the usual target and allowed lists.
- The parser accepts a Sleep entry with zero or one target (All apps, app, or group) and validates the three numbers; an invalid one is skipped with a reason through the existing counters. `rules` ignores the Sleep keys, as it ignores `allApps` and `firstUnlockMinutes`.
- The older-reader test drops `extendedRules` and asserts every other rule survives and no Sleep rule does.
- No new settings. `first_unlock_at` stays device-local, and the in-memory first use is never exported.

## 10. Tests

- **L1 `SleepWindowTest`**: night boundaries, a chosen-day start, crossing midnight, no use all night, first use at 5:05, first use at 8:00, back to sleep, a 3:00 use does not trigger, DST spring and fall with explicit zones, travel mid-night both directions, clock jumps both ways.
- **L1 `SleepTriggerTest`**: covered app triggers; allowed app, allowed group, essential and system surface do not; another rule's decision is irrelevant.
- **L3 form and ViewModel**: round trip for the three fields and days, the summary text, Strict Mode decisions, the day-start fact excluding Sleep.
- **L4**: migration 16 to 17 with its lists updated, export round trip, older-reader drop, validation of a half-set or out-of-range Sleep row.
- **L5 source contracts**: one trigger site, the receiver unchanged, the `rules` parser ignoring the Sleep keys.
- **L6 device QA** on the moto: the table in section 2, plus the weekend case and a process kill inside the delay.

## 11. Build order

1. **Engine and data**: migration, fields, `SleepWindow`, evaluator branch, trigger, Strict Mode axes, tests. Inert without UI, and shippable.
2. **Editors and backup**: the When option, summary, export in `extendedRules`, docs.
3. **Device QA** on the moto, then the #171 case matrix.

Two PRs, or one if the first is small enough after review; the split keeps the UI out of the engine review.

## 12. Risks and open questions

- **Process death inside the delay** re-arms it, over-blocking by up to `N` (D3). Worth persisting if it shows up in use.
- **Multiple Sleep rules** keep independent first-use state; a rule on an app and a rule on All apps do not share a trigger.
- **Travel and clock jumps** can extend the night. Bounded by the essentials floor and stated above; not solved.
- **The user who never sleeps** is blocked 23:00 to 5:00 by design. That is the feature, not a bug.
- **App config** coverage is deferred (D1); if Sleep belongs on a single app, it is a small follow-up on the same fields.
- **Nuke** still wins, as today. Sleep adds no exception.

## 13. Decisions for the owner

- **D1** Sleep appears in the group and All apps editors first; App config later.
- **D2** The trigger is this rule's own coverage only, not the engine's full decision.
- **D3** First-use state is in memory, per rule, lost on process death.
- **D4** `firstUnlockMinutes` is reused as the morning delay; no new column for `N`.
- **D5** No arming for Sleep; a save mid-night applies immediately.
- **D6** DB 16 to 17, additive.

## 14. Alternatives considered

- **Two rules, night plus morning, with an allowed-wins release.** Rejected: v1 allows one All apps rule, and a rule that lifts another is the one shape the spec avoids.
- **A pure rolling window without a fixed end.** Rejected: a 3:00 use would end the night at 3:30 and leave the phone open until morning.
- **Making every unlock the anchor (a global change to #155).** Rejected: it alters a merged, user-facing promise; Sleep is additive.
- **A longer day start.** Rejected: one fixed hour cannot fit 4:00 and 9:00 wakers, and it shifts the logical weekday.
