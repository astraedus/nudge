# Morning Lock — design

Status: **proposal, not approved, nothing built.** Issue: [#107](https://github.com/astraedus/nudge/issues/107).
Written overnight for a single review pass. Every "my call" is a default chosen to keep the document
complete; each is cheap to overturn and is listed again at the end. A fresh builder read this document
and the code it cites; its eight open questions are folded in below, with what I settled and what is
still yours.

## Intent

For the first 30 minutes after the first unlock of the day, a chosen app (or a small set) opens and
everything else shows a block screen. The phone, messages and emergency calling stay usable.

The owner's words: *"Sitting in bed and opening a feed is the thing I want to stop. I do not need the
phone locked. I need Instagram, YouTube and the browser unavailable for half an hour, while the phone
and one chosen app still work."*

This is the same loop the "Bro. wtf." check-in names, caught at the one moment it is predictable
instead of after the fact.

## Goals

1. Deny-by-default for the window: anything not explicitly allowed is blocked, including apps with no
   rule at all. This is the whole point and the one thing the current rule engine cannot express.
2. Anchor on the **first unlock of the local day**, once per day. No wake detection, no sleep
   tracking, no schedule.
3. Survive the ways a phone actually behaves overnight: process death, reboot, OEM task killers, a
   device with no keyguard, a clock change, DST.
4. Essentials always usable: phone/dialer/in-call/telecom/emergency, **the default messaging app**,
   launcher, keyboards, System UI, Settings, installers, permission controllers, Nudge itself.
5. Zero new permissions, no internet, all data local. Nothing about this feature may need a network
   or a new runtime grant.
6. No DataStore read on the synchronous service path. One cached state, collected off-main, exactly
   like the Nuke cache; the evaluation coroutine keeps Nuke's shape (see "Cost").

## Non-goals

- Waking the user up, bedtime reminders, sleep tracking.
- A per-weekday schedule, multiple windows per day, or a window that starts at a fixed clock time.
  (The first-unlock anchor is the feature; a fixed-time variant is a different feature and belongs on
  a rule.)
- Blocking websites during the window by itself. Web blocking still comes from the content filter and
  web rules; Morning Lock blocks the **browser app**, which is what the owner asked for.
- A second escape hatch. See "The escape".

## Constraints the code already imposes

- **The accessibility service is the only interception point.** `NudgeAccessibilityService` classifies
  each event once (`EventClassifier`), and every block is decided in `EvaluateBlockUseCase` and shown
  by the one `launchBlockOverlay`. The block screen is a full Activity, and Android 12+ refuses
  background Activity starts from anywhere but a bound accessibility service
  (`NudgeMonitorService` is explicitly forbidden from starting one, pinned by
  `MonitorServiceContractTest`). The window's blocking must therefore be evaluated per foreground
  event, not driven by a timer.
- **`ACTION_USER_PRESENT` cannot be manifest-declared since API 26.** It must be runtime-registered.
  The service already does exactly this for `ACTION_SCREEN_OFF`
  (`NudgeAccessibilityService.kt:677-680`, registered at `:1174`), with a comment explaining why it
  cannot live in the manifest. Morning Lock's receiver belongs beside it.
- **A dead process receives no broadcast.** After an overnight kill, reboot or OEM sweep, no
  `USER_PRESENT` arrives at all. The window therefore cannot be "start a timer on unlock"; it has to be
  derived from persisted state and checked on the next foreground event, or the feature silently does
  nothing on exactly the mornings it exists for.
- **Timers are the wrong tool anyway.** WorkManager has a 15-minute floor, exact alarms need
  `SCHEDULE_EXACT_ALARM` (not in the manifest), and a `Handler`/alarm re-introduces the wakelock
  class of bug. `now < windowEnd` on an event already being handled is exact, free and testable.
- **`BlockEngine` is default-allow.** A package with no matching rule returns `Allow`
  (`BlockEngine.kt:73-76`), and it only ever sees rules resolved for that package. "Block everything
  except X" cannot be expressed as a rule; it needs a first-step in the evaluation, exactly as Nuke
  has (`EvaluateBlockUseCase.kt:55-66`).
- **Nuke is deliberately not a `BlockMode`.** It is its own DataStore key group, state, policy,
  enforcement interface, screen and Home card, because a sixth `BlockMode` would have forced edits to
  the exporter's valid-mode list, the insights calculator, the rule editor, the unified app config
  screen, the overlay's `when`, and every stat. Morning Lock should follow that shape, not the mode
  enum.
- `minSdk = 26`, `targetSdk = 36`: `KeyguardManager.isDeviceLocked` (API 22) and
  `ACTION_USER_PRESENT` exist everywhere supported, so the only API-level work is registration
  mechanics, not branching.

### The browser is not on the app path

This is the one place a copy of Nuke's shape is not enough. `evaluateForegroundPackage` sends every
**browser** to `evaluateWebDomain` instead of `EvaluateBlockUseCase.invoke`
(`NudgeAccessibilityService.kt:1967`), and `handleWindowContentChanged` does the same (`:2358`). A
first-step in the use case therefore cannot block Chrome — the owner's headline example — at all.

Morning Lock needs the same service-level predicate Nuke has: `isNukedNow` is checked in four places
(`:817`, `:1941`, `:2353`, `:1967`), and Morning Lock must be checked at the same points, before the
browser branch. Concretely:

- a block for the browser is attributed to **the browser package** (an app block, `UsageEvent` on
  Chrome, overlay shows the app-block content), not to a domain;
- the web path also checks it, so switching tabs on an already-open browser cannot slip through;
- the fingerprint mode is `"MORNING_LOCK"` on both paths.

### The essentials floor is not `SYSTEM_PACKAGES`

`SYSTEM_PACKAGES` (`:274-283`) has System UI, launchers, Settings, installers and permission
controllers, but **not** the dialer, in-call UI, `com.android.phone`, telecom or emergency. Those live
only in `NukePolicy.STATIC_FLOOR` and `NukeSafetyFloor.deviceFloor()`. A deny-by-default mode that does
not consult the floor will block 911.

It also does not contain any messaging package, and the owner's words require messages to stay usable.
So Morning Lock gets **its own floor**, composed rather than forked:

```
MorningLockSafetyFloor.deviceFloor() =
    NukeSafetyFloor.deviceFloor()                 // dialer, in-call, telecom, emergency, launchers,
                                                  // keyboards, settings, installers, own package
  + Telephony.Sms.getDefaultSmsPackage(context)   // the default SMS role, resolved at runtime
  + STATIC_MESSAGING                            // com.google.android.apps.messaging,
                                                  // com.samsung.android.messaging,
                                                  // com.android.messaging (AOSP)
```

Nuke's own floor is left alone: Nuke is a deliberate self-lock, and widening it would change an
existing feature's promise. The two floors share the dynamic half and diverge on the SMS line. That is
one new file plus one test, not a rewrite of the Nuke floor.

## The model

```
MorningLockState(
  enabled: Boolean,
  packages: Set<String>,          // apps allowed to open during the window
  firstUnlockEpochMs: Long?,      // first unlock of the local day, recorded whether or not enabled
  firstUnlockDayStartMs: Long?    // TimeTracker.startOfToday() of that unlock
)
```

- **The first-unlock stamp is always recorded**, even while the feature is off: one `USER_PRESENT` per
  day and one DataStore write. `enabled` only decides whether the window *blocks*. This is what stops
  "turn it on at 3pm, next app open starts a 30-minute deny-by-default window" — the most surprising
  possible first-run. Turning it on mid-day does nothing until tomorrow's first unlock. (Cost: one
  write per day; the receiver is registered anyway for `SCREEN_OFF`.)
- **Window** = `[firstUnlockEpochMs, firstUnlockEpochMs + WINDOW_MS)` on that day. No timer; the check
  runs on foreground events and on the unlock broadcast.
- **Once per day:** if `firstUnlockDayStartMs != TimeTracker.startOfToday()`, the stamp is yesterday's
  and is ignored (and cleared lazily on the next unlock/event).
- **Anchor** is set by the first of:
  1. `ACTION_USER_PRESENT` while the service is bound (the normal case);
  2. the first `ForegroundSignal.AppWindow` of the day while `KeyguardManager.isDeviceLocked` is
     false (the fallback: covers a dead process at unlock time, and devices with no keyguard, which
     may never send `USER_PRESENT`).
- **Wall clock, not `elapsedRealtime`:** the anchor must survive a reboot, and `elapsedRealtime` resets
  at boot. Guard against a clock that moved backwards: a stamp in the future (beyond a small
  tolerance) is discarded and re-anchored; a clock that jumps forward ends the window early. Both
  fail toward *less* blocking, which is the right direction for a nudge-shaped feature whose window is
  only 30 minutes.
- **Window length** is `WINDOW_MS = 30 * 60_000`, a constant in the policy, overridable through the
  policy's config for tests. (Configurable length is an open question, not v1.)

## Evaluation order

```
nuked?                                   -> NUKE_DECISION            (unchanged, wins everything)
morning lock active && allowed(pkg)?     -> Allow, short-circuit     (see below)
morning lock active && !allowed(pkg)?    -> MORNING_LOCK_DECISION
rules...                                                             (unchanged)
```

- **After Nuke**, because Nuke is the stronger promise and its block screen must win.
- **Before rules**, because the window's block is the reason it exists; a DELAY rule on Instagram must
  not turn the window into a 30-second countdown.
- **An allowed app ignores its own rules for the window.** If the user picks Instagram as the one app
  and has an old hard-block rule (or a 5-minute budget) on it, "the one app that works" has to work.
  This is an explicit early `Allow`, the one new decision shape in the design. It is a product call:
  the alternative is "allowed means not blocked *by Morning Lock*, rules still apply", which is
  additive and needs no new shape but makes the window useless for exactly the app people pick.
- `BlockDecision.Block` gains a `morningLock: Boolean` flag, mirroring `nuke`, so the fingerprint mode
  (`"MORNING_LOCK"`) and the overlay content are unambiguous and a pending rule block cannot swallow
  it (`BlockLaunchGate.decisionFingerprint`).
- `BlockOverlayActivity` renders a `MorningLockBlockContent` beside `NukeBlockContent`: no daily pass,
  no emergency pass, the time left in the window, and the allowed app(s) named.

## Grants do not survive the window

Nuke skips every grant explicitly (`:1941-1942`, documented at `:1811-1820`): the 2-minute emergency
pass, an armed auto-kick cooldown, and a completed-delay passthrough. Morning Lock does the same, and
that is load-bearing: a pass earned at 6:59 must not open Instagram at 7:00, or "deny-by-default" is
false in the first minutes of the window — the only minutes anyone tests.

`grantAliveFor` (`:816-818`) gets the same treatment so the counter/time-remaining overlays do not
behave oddly during a window.

## The escape

Morning Lock has **no emergency pass and no daily pass**, like Nuke. The escape is the Settings
switch: turning Morning Lock off ends the window. Under Strict Mode that switch takes the unlock
challenge (`MorningLockPolicy.isWeakening` + the existing `StrictModeGate` shape), otherwise it is one
tap.

Rationale: a 30-minute window with a pre-chosen allowed list is not a trap; the failure that matters is
a *silent* way out that the user forgets they took. Making the escape the Settings switch means the
only way to browse Instagram at 7am is to go and turn the feature off, which is a deliberate act and
is visible in the app's state. A second escape (a pass) would be the thing the owner then uses every
morning.

The block screen itself offers only "Go Back".

## The redirect app

`RedirectAppRepository.kt:49` feeds Nuke's list into `RedirectAppPolicy.excluded` so the bubble never
offers an app that will block. Morning Lock does the same **and hides the bubble for the window**,
including when the redirect app is the browser.

My earlier instinct was to auto-allow the redirect app as the constructive way out. The builder check
killed it: a user whose redirect app is Chrome or YouTube would get exactly the apps the issue names as
must-block. Hiding the bubble is one line in the same place, keeps the promise, and leaves "Go Back" as
the only action — which is the same screen Nuke shows.

## The allowed list

- Picker reuses the Nuke screen's shape (`NukeScreen`/`NukeViewModel`): installed apps, a checkbox per
  app, the Morning Lock floor filtered out (those are allowed anyway, so listing them is noise).
- Empty list is legal and means "nothing but essentials opens". The screen says so plainly rather than
  blocking the save.
- Apps installed after the list was chosen are **not** allowed (deny-by-default is the promise).
- Device-local and not carried by `exportableSettings()` (the app's only backup channel;
  `allowBackup="false"`, so there is no whole-device backup path either). A backup that restored an
  allow-list onto a different phone could silently open apps the user did not choose there.

## What the user sees

- Block screen: "Morning Lock", the allowed app named ("Only <app> until 07:32"), time remaining, "Go
  Back". No pass button, no redirect bubble.
- Home: a card beside Nuke showing the state ("Morning Lock · 07:00–07:30" or "Off") and the allowed
  list; tapping opens the screen.
- Settings: the picker, the enable switch (Strict-Mode gated to turn off), and one line of copy that
  says what the window does and that the off switch is the way out.
- The window's blocks count as hard blocks in stats (they are HARD_BLOCK), so `InsightsCalculator`,
  "Blocked most" and the bounce check-in all see them. That is honest — 20 blocked opens at 7am is
  exactly the loop the check-in was built for — but it does mean "interventions" rises on mornings
  the feature works. A `morningLock` dimension in insights is a follow-up, not this change.

## Failure modes and what happens

| Situation | Behaviour |
|---|---|
| Service dead at unlock, started later | Fallback stamps the first app window of the day; the window starts then. A few minutes late, not missing. |
| Reboot mid-window | Epoch stamp survives; the window continues (the phone was unlocked once today). |
| No keyguard (swipe/None) | `USER_PRESENT` may never fire; the fallback path stamps. |
| Clock moved backwards | Stamp in the future is discarded; next unlock re-stamps. |
| Clock moved forward | Window ends early. Fail-open, deliberately. |
| DST / timezone change | `TimeTracker.startOfToday()` is the day key and is DST-safe; a timezone change can only end the window early or start a new day's. |
| Feature enabled at 3pm | Nothing happens until tomorrow's first unlock (the stamp is always recorded). |
| Allowed app uninstalled | Nothing extra opens; essentials still work. |
| Accessibility service disabled | Nothing is blocked, as with every other feature; `ProtectionStatus` already reports it. |
| User turns the feature off at 7am | Allowed; gated by Strict Mode. This is the escape. |
| Phone in a floating window / split screen | Same as every block today: `coverIfFloating` and the multi-window refusal (#71) apply through `launchBlockOverlay`. |
| Emergency pass / cooldown / passthrough live at 7:00 | Ignored for the window (see "Grants"). |

## Cost

- Off: the receiver is registered (already true for `SCREEN_OFF`), one `USER_PRESENT` per day, one
  DataStore write. Foreground events read one cached boolean.
- On, outside the window: one cached read.
- On, inside the window: one set lookup plus the floor check (cached like `NukeSafetyFloor`'s 5-minute
  cache).
- The use case still reads preferences per evaluation, exactly as `NukeEnforcementSource` does
  (`NukeSafetyFloor.kt:102-106`). "No DataStore read on the event path" therefore means the
  synchronous service path, and the source-level contract test pins that — not the use case.

## Approaches considered

**A. Sibling mode (recommended).** New `domain/morninglock/` state + policy, a first-step in
`EvaluateBlockUseCase` plus the browser-path check, a flag on `BlockDecision.Block`, a content
composable, a screen/ViewModel/nav route, a Home card, DataStore keys, export exclusion, gates. This is
the Nuke shape, and Nuke's own doc explains why that shape was chosen over a `BlockMode`.

**B. A group rule with inverted polarity** (`allowGroup` on `BlockRule` + schedule + unlock anchor).
Cheaper UI, but the engine is default-allow and only sees rules resolved for the foreground package.
An inverted rule still needs a deny-by-default branch in the evaluator, so the "cheap" version ends up
with the same first-step plus new rule semantics, an exporter field, a weakening axis, and a rule
editor that can now express two polarities. Strictly more surface than A for the same behaviour.

**C. A second Nuke list with a 30-minute timer.** Reuses Nuke wholesale, but conflates "no way around
it" (Nuke's promise, QR key, 64-char emergency exit) with a time window, and would need its own
timer — the exact thing that cannot work across process death. Rejected.

## What would be built (outline, for the plan after approval)

1. `domain/morninglock/MorningLockState.kt`, `MorningLockPolicy.kt` (pure: window math, day rollover,
   floor, weakening), `MorningLockEnforcement.kt` (interface, like `NukeEnforcement`).
2. `service/MorningLockSafetyFloor.kt` (the composed floor above; `defaultSmsPackage` next to the
   existing `defaultDialerPackage`).
3. `NudgePreferences`: `MORNING_LOCK_*` keys + one `morningLockState` flow; setters keeping the
   invariant "enabled without a key is impossible" (mirror of `setNukeActive`).
4. `EvaluateBlockUseCase`: constructor param (no default, as Nuke's is), the allowed-app `Allow`
   short-circuit, the block step.
5. `BlockEngine`/`BlockDecision`: `MORNING_LOCK_DECISION`, `morningLock` flag.
6. `NudgeAccessibilityService`: cached state + collector, `USER_PRESENT` receiver beside
   `SCREEN_OFF`, fallback stamp in `applyForegroundSignal`, the browser-path check (four sites, like
   `isNukedNow`), grant bypass, re-evaluate on arm, fingerprint mode, `EXTRA_MORNING_LOCK`.
7. `BlockOverlayActivity` + `MorningLockBlockContent`; `RedirectAppRepository` exclusion.
8. UI: `MorningLockScreen`/`ViewModel`, nav route, Home card, picker with the Morning Lock floor.
9. Gates: `MorningLockPolicy.isWeakening` (adding an allowed app weakens, so **adding** is gated here —
   the mirror of the blocklist), `StrictModeGate` on the off switch.
10. Docs: `docs/architecture/morning-lock.md`, CLAUDE.md index row, CHANGELOG.
11. Tests, by layer:
    - L1 policy: once-per-day, day rollover, window edges, clock guards, empty list, floor, weakening
      direction (adding an allowed app is weakening; removing is free).
    - L1 use case: Morning Lock beats a rule; allowed app short-circuits its rules; floor passes; off
      is a no-op; Nuke still wins.
    - Source-level contract: the receiver is runtime-registered (not in the manifest), the browser
      path checks the window, grants are bypassed, the service never starts an Activity outside
      `launchBlockOverlay`, no DataStore read on the synchronous path.
    - L6 device: lock → unlock → blocked app → allowed app → 30-minute end; no-keyguard fallback;
      reboot mid-window; service killed before unlock; enable mid-day does not arm.

## What I need from you (the owner)

Each has my recommendation; a one-word answer is enough.

1. **Mode vs group rule.** Recommend A (sibling mode), for the reasons in "Approaches". Confirm?
2. **No escape vs one pass.** Recommend no pass; the off switch is the escape and Strict Mode gates
   it. Confirm, or say "one pass per window".
3. **Allowed app ignores its own rules during the window** (so a picked Instagram actually works past
   its old budget/rule). Recommend yes. The alternative is additive-only.
4. **Messages are essentials**, so the Morning Lock floor adds the default SMS role and common
   messaging packages (Nuke's floor untouched). Recommend yes; confirm you do not want messages blocked.
5. **Redirect bubble hidden during the window** (rather than auto-allowing the redirect app, which
   could be the browser). Recommend hidden.
6. **Window length:** fixed 30 minutes in v1, constant in the policy. Or 15/30/60 picker now?
7. **Every day, including weekends?** Recommend every day in v1; no schedule.
8. **Stats:** Morning Lock blocks count as ordinary hard blocks (and arm the bounce check-in). Recommend
   accepting that in v1; a separate insight dimension is a follow-up.

## What is not decided here

- Whether the window should also block *websites* when the browser is allowed (it is not, in this
  design: the browser is blocked as an app, which is what #107 asked for).
- Whether the Home card shows the window's remaining time (a nice touch, not required).
- Whether "first unlock" should mean first unlock after 04:00 rather than first unlock of the calendar
  day (someone who unlocks at 00:30 gets their window then). Recommend calendar day in v1; the
  alternative needs a configurable day boundary and is a separate product call.
- A first-run spike on the owner's device: prove `ACTION_USER_PRESENT` reaches a runtime-registered
  receiver in the service on Android 16, and that the fallback stamps on a device with the keyguard
  dismissed. Cheap (a log-only branch), and the first step of the plan.
