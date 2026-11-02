# Phone nudge (#123)

A whole-phone pause after N minutes of use in one sitting, across every app. Read before touching
`domain/nudge/`, the `phoneNudge` lines in `NudgeAccessibilityService`, `PhoneNudgeActivity`, or the
Settings row.

## Why

The per-app nudges cannot see the failure this exists for: picking up the phone to do nothing,
drifting between unblocked apps, and noticing ninety minutes later. The usage reminder and the time
auto-kick are per app; the bounce check-in needs Nudge walls. Time spent hopping between apps
accumulates nowhere.

## The model

All of it lives in `domain/nudge/PhoneNudge.kt`, pure Kotlin.

| Term | Meaning |
|---|---|
| **Session** | Starts at an unlock. Ends when the screen goes off for longer than the grace window, or when Nudge is switched off. |
| **Grace** | `GRACE_MS` = 2 min, the same window `SittingTracker` uses. A quick blank resumes the session; a longer one starts a fresh one at the next unlock. |
| **Idle** | `IDLE_MS` = 2 min without a user-driven accessibility event (click, long click, select, scroll, text change). The session is kept but its clock stops, so a screen left on over ADB, or a video nobody touches, cannot accumulate a sitting. The next interaction resumes it. |
| **Counted time** | Screen-on time while the user is interacting, every app, no exclusions. |
| **Threshold** | `thresholdMinutes` (default 20, 5..180). One pause per threshold of **counted** time. |
| **Anchor** | The next pause is due one threshold after the LAST pause, not on a fixed grid. Lowering the threshold therefore fires at most one catch-up pause, never a burst. |
| **Escalation** | The pause doubles from `firstPauseSeconds` (default 5, 5..30) and caps at 30 s: 5, 10, 20, 30, 30. |
| **Real minutes** | The pause screen shows the actual counted minutes, not the nominal threshold. |
| **Reset** | `reset()` on the feature or the master toggle going off. |

## When it may interrupt

`PhoneNudgePolicy.canInterrupt` is a pure decision over `Facts`; the service fills them only when the
timer fires, so binder reads stay off the event path. A pause is **deferred** (retried in
`DEFER_MS` = 30 s and NOT counted as shown) when:

- `AudioManager` mode is `IN_CALL`, `IN_COMMUNICATION` or `RINGTONE` (covers WhatsApp and Meet too);
- a navigation app is in front: `ApplicationInfo.CATEGORY_MAPS`, or a known package (Maps, Waze,
  Android Auto, OsmAnd, Organic Maps, Sygic, TomTom, HERE);
- the phone is in car mode (`UiModeManager`);
- one of our own screens is in front, or a block overlay is up;
- the device is locked or the screen is not interactive;
- a camera is in use (`CameraManager.AvailabilityCallback`; no CAMERA permission needed).

Deferral is why a pause skipped because of a block overlay no longer advances the escalation.

## Where it is wired

| Feed | Site | Why there |
|---|---|---|
| Screen off | `screenOffReceiver` (existing) | Runtime-registered; also banks the session clock and cancels the idle timer. |
| Unlock / screen on | `screenOnReceiver` (new) | `ACTION_USER_PRESENT`, and `ACTION_SCREEN_ON` only when unlocked. A locked screen never starts or resumes a session. |
| First app window | `applyForegroundSignal` | Falls back for a dead process or a phone with no keyguard. Only asks the keyguard when there is no session. |
| Interaction | `onAccessibilityEvent` | The five user-driven event types above re-arm the idle timer and resume an idle session. |
| Timer | `phoneNudgeHandler` | Re-armed by the state machine, cancelled on screen off, idle and teardown. |
| Idle timer | `phoneNudgeIdleHandler` | Armed on unlock and each interaction; fires `onIdle`. |
| Launch | `showPhoneNudgePause` | The one place an Activity is started from a timer. Proven on Android 16; the Android 14 check is in `docs/BACKLOG.md`. |
| Settings | `PhoneNudgeRow` | Off by default, not gated by Strict Mode. |
| Backup | `exportableSettings()` | `phoneNudgeEnabled`, `phoneNudgeThresholdMinutes`, `phoneNudgePauseSeconds`. Importing is never weakening. |

The master toggle is part of the config: `enabled = phoneNudgeEnabled && globalEnabled`, so switching
Nudge off stops the session as well as the blocks.

## The pause screen

`PhoneNudgeActivity`: full screen, "$minutes minutes." and "What were you going to do?", a countdown,
"I'm done, go Home" and "Keep going" (enabled only when the countdown ends). Back is swallowed. It
finishes in `onStop`, and `onNewIntent` recreates the instance, so a pause left behind cannot be
reused with stale minutes and a finished countdown.

## Failure modes

| Situation | Behaviour |
|---|---|
| Process death | The session is forgotten. No pause until the next unlock. |
| Screen off inside the grace | Session continues; the blank does not count. |
| Screen off beyond the grace | A fresh session starts at the next unlock. |
| No interaction for 2 min | The clock stops; no pause fires while idle. |
| Call, navigation, car mode, camera, own UI | Deferred 30 s, retried, not counted as shown. |
| Block overlay up at the timer | Deferred, and the escalation does not advance. |
| Feature or Nudge switched off | Session, idle timer and pause timer cleared. |
| Phone in a floating window | The pause activity is full screen, same as every other overlay. |

## Tests

- `PhoneNudgeTest` (L1): threshold timing, real minutes, escalation to the cap, deferral without
  advancing, idle stopping and resuming the clock, the grace window both ways, a fresh session after
  a long blank, the anchor after a threshold change, off means off, reset, bounds.
- `PhoneNudgePolicyTest` (L1): each deferral fact on its own, navigation by category or known package.
- `PhoneNudgeRowCopyTest`: the escalation line under the picker.
- `SettingsExportTest` / `ImportedSettingsWriteContractTest`: the three settings round-trip, ranges
  validated on import.
- L6 (device): the timer launch on Android 16; Android 12 by the maintainer; Android 14 owed
  (`docs/BACKLOG.md`).
