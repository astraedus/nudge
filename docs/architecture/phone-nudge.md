# Phone nudge (#123)

A whole-phone pause after N minutes of use in one sitting, across every app. Read before touching
`domain/nudge/`, the `phoneNudge` lines in `NudgeAccessibilityService`, `PhoneNudgeActivity`, or the
Settings row.

## Why

The per-app nudges do not see the failure this exists for: picking up the phone to do nothing,
drifting between unblocked apps, and noticing ninety minutes later. The usage reminder and the time
auto-kick are per app; the bounce check-in needs Nudge walls. Time spent hopping between apps
accumulates nowhere.

## The model

All of it lives in `domain/nudge/PhoneNudge.kt`, pure Kotlin.

| Term | Meaning |
|---|---|
| **Session** | Starts at the first `ACTION_USER_PRESENT` (unlock), ends when the screen goes off for longer than the grace window. |
| **Grace** | `GRACE_MS` = 2 min, the same window `SittingTracker` uses. A quick blank (checking the time) resumes the session; a longer one ends it and the next unlock starts a fresh one. |
| **Counted time** | All screen-on time in the session, every app, no exclusions. |
| **Threshold** | `thresholdMinutes` (default 20, 5..180). A pause is due at 20, 40, 60, ... minutes of session time. |
| **Escalation** | The pause doubles from `firstPauseSeconds` (default 5, 3..30) and caps at 30 s: 5, 10, 20, 30, 30. |
| **Reset** | `reset()` on the master toggle going off, and on the feature being switched off. |

The timer is a `Handler` re-armed for each threshold, cancelled on screen off. Not an alarm, not
WorkManager, not a wakelock: the pause only matters while the screen is on, and a Handler dies with
the process, which is the right direction for a nudge.

## What it is not

Not a block. It writes no `UsageEvent`, has no passthrough, no launch gate and no Strict Mode gate in
either direction. It is friction, not enforcement. It is skipped while a block overlay is already up
(stacking two full-screen windows helps nobody).

## Where it is wired

| Feed | Site | Why there |
|---|---|---|
| Unlock / screen on / screen off | `screenStateReceiver` in `NudgeAccessibilityService` | Runtime-registered; `ACTION_USER_PRESENT` and `ACTION_SCREEN_ON` cannot be manifest-declared on API 26+. |
| Timer | `phoneNudgeHandler` in the service | Re-armed by the state machine, cancelled on screen off. |
| Launch | `showPhoneNudgePause` in the service | The one place an Activity is started from a timer; proven on Android 16, and the reason the spike exists. |
| Settings | `PhoneNudgeRow` | Not gated by Strict Mode; off by default. |
| Backup | `exportableSettings()` | `phoneNudgeEnabled`, `phoneNudgeThresholdMinutes`, `phoneNudgePauseSeconds`. Importing is never weakening. |

## The pause screen

`PhoneNudgeActivity`: full screen, "$minutes minutes." and "What were you going to do?", a countdown,
"I'm done" (goes Home) and "Keep going" (enabled only when the countdown ends). Back is swallowed;
the buttons are the way out. That gating is the whole feature: a pause that can be dismissed on
reflex is a notification with extra steps.

## Failure modes

| Situation | Behaviour |
|---|---|
| Process death | The session is forgotten. No pause until the next unlock. |
| Screen off inside the grace | Session continues; the blank does not count. |
| Screen off beyond the grace | Session ends; the next unlock starts a new one. |
| Block overlay up when the timer fires | Skipped, logged as `phone nudge skipped reason=overlay_active`. |
| Feature switched off mid-session | Session and escalation cleared, timer cancelled. |
| Phone in a floating window | The pause activity is full screen, same as every other overlay. |

## Tests

- `PhoneNudgeTest` (L1): threshold timing, escalation to the cap, the grace window both ways, a new
  session after a long blank, off means off, threshold changes re-arm, reset, bounds.
- `SettingsExportTest` / `ImportedSettingsWriteContractTest`: the three settings round-trip and are
  written under a null guard.
- L6 (device): the timer-launch spike on Android 16 (`spike: startActivity returned without
  throwing`, activity foregrounded). Android 14 check on an API 34 emulator.
