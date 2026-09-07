# Service lifecycle and the protection watchdog

Covers what keeps Nudge's enforcement alive, what notices when it dies, and how the user finds out.
**Read before touching `NudgeMonitorService`, `BootReceiver`, `ProtectionWatchdogWorker`,
`ProtectionCheck`, `ProtectionStatus`, `AccessibilityConnectionSignal`, `NudgeApp`, `MainActivity`,
or the permission rows on the Settings screen.**

## The defect this subsystem was built out of

Our first Play review was 3 stars, *"It doesn't work sometimes"* (Redmi Note 14, Android 16), and
[issue #23](https://github.com/astraedus/nudge/issues/23) reported the accessibility service dying
overnight **with battery restrictions already disabled**. The 2026-09-06 resilience audit found the
mechanism, and it needed no OEM involvement at all:

- `NudgeMonitorService.start()` had **exactly one call site in the whole codebase**: `BootReceiver`,
  on `BOOT_COMPLETED`. `MainActivity` never started it. The master toggle never started it.
  Completing onboarding never started it. `stop()` had zero call sites.
- The manifest declared **one** receiver action, `BOOT_COMPLETED`. There was no
  `ACTION_MY_PACKAGE_REPLACED`.
- **Nothing anywhere ever asked whether the app was still working.** No `WorkManager`, no
  `AlarmManager`, no `JobScheduler` — zero hits across `app/src`.
- The Settings screen read all three permissions with `remember { mutableStateOf(...) }`: one shot
  at first composition, never again.

So: Play auto-updates Nudge overnight → the in-place update makes Android disable the accessibility
service → no foreground service is running to notice → nothing restarts either → and the Settings
screen keeps displaying a green tick over a dead service. Blocking silently stops until the user
reboots. Then it works. Then it stops again at the next update. We shipped six releases between
19 and 31 August 2026, so our own release cadence was firing this at users.

MIUI does not have to do anything exotic to produce that review. It only has to win a fight we
never turned up to. What MIUI adds is frequency, plus a force-stop that defeats `START_STICKY`
outright, plus (per the OEM research) revoking the accessibility grant *as a consequence of* killing
the process — which is why it stays dead until the user re-toggles it by hand.

## The three parts

### 1. Real start paths for the foreground service

`NudgeMonitorService` holds process priority. It does not monitor anything (the accessibility
binding is what enforces), so its value is entirely in existing — and it now exists whenever
monitoring should be live:

| Moment | Where |
|---|---|
| App launch with monitoring on | `MainActivity.keepMonitorServiceInSync()` |
| Master toggle switched on **or off** | same observer — the flag is the thing being watched |
| Onboarding completing | same observer — it writes `onboardingComplete` without leaving the Activity |
| Device reboot | `BootReceiver`, `ACTION_BOOT_COMPLETED` |
| App update | `BootReceiver`, `ACTION_MY_PACKAGE_REPLACED` |
| A dead service found by the watchdog | `ProtectionWatchdogWorker` |

The first three are **one observer**, not three call sites, because all three are a change in the
same pair of flags (`isGlobalEnabled && isOnboardingComplete`) while `MainActivity` is on screen.
Gating on onboarding too means a first-run user is never shown an ongoing notification claiming
Nudge is monitoring before they have granted it anything to monitor with.

Two invariants, both pinned by `ServiceLifecycleContractTest`:

- **The receiver guard is a membership test.** It used to be
  `if (intent.action != Intent.ACTION_BOOT_COMPLETED) return`. An inequality against ONE action
  means adding a second action to the manifest compiles, ships, and silently does nothing.
- **`NudgeMonitorService.start()` returns a Boolean and swallows `IllegalStateException`.** Android
  12+ forbids starting a foreground service from the background, and the watchdog does exactly
  that. Nudge normally qualifies for the `SYSTEM_ALERT_WINDOW` exemption, but onboarding lets that
  permission be skipped, and then `startForegroundService` throws
  `ForegroundServiceStartNotAllowedException`. An uncaught throw in the one component whose job is
  noticing failure would be its own silent death.

`NudgeMonitorService.isRunning` is a `@Volatile` static set in `onStartCommand` and cleared in
`onDestroy`. A static is the *honest* signal here precisely because it dies with the process: the
failure being watched for is the OS killing us, and a killed process comes back with it false.
(`getRunningServices()` has been restricted since API 26; a heartbeat timestamp would be this flag
with extra I/O.)

### 2. The watchdog

`ProtectionWatchdogWorker` is a `WorkManager` periodic worker on the 15-minute floor, enqueued
`KEEP` from `NudgeApp.onCreate()` — the one callback that runs on **every** process start, so it
re-arms itself after a boot, an update, and any kill WorkManager itself recovers from. `KEEP` and
not `REPLACE`: replacing the request on every launch would push the next run 15 minutes out each
time, so the user who opens Nudge most would be checked least.

**Not `AlarmManager`.** An exact alarm needs `SCHEDULE_EXACT_ALARM` (a Play-review surface) and is
rate-limited on Android 14+, for a check whose tolerance is a quarter of an hour.

The signal is **two reads, not one** (`ProtectionStatus`). Membership in
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` is the user's INTENT; membership in the system's
bound list (`AccessibilityManager.getEnabledAccessibilityServiceList`) is REALITY, and the gap
between them is the failure. Verified against AOSP master: when an accessibility service's process
is killed, `binderDied()` puts the component in `mCrashedServices`, `updateServicesLocked()`
`continue`s past it forever, and it is left in the settings string. A watchdog built on that string
alone would sit reading "enabled, all good" through the entire failure it was written to catch. Only
a force-stop or a user toggle strips the component.

Between them the two reads still cover every root cause (a memory kill, MIUI's revoke-on-kill, an
update-disable, a user switching it off), so no OEM-specific detection code is needed, only
OEM-specific *messaging* later.

The check body lives in `ProtectionCheck`, not in the worker, because **WorkManager cannot be made
to run the worker on demand** (see "How to test this" below). The worker is the schedule,
`ProtectionCheck` is the check, `ProtectionWatchdog` is the policy.

The policy lives in `domain/health/ProtectionWatchdog`, a **pure function**, because the failure it
exists for (a phone quietly switching Nudge off overnight) is not reproducible on a device.
`ProtectionCheck` gathers the values and carries out the verdict; it holds no policy of its own, and
`ServiceLifecycleContractTest` asserts it does not grow one.

| State | What happens |
|---|---|
| Master toggle off | Silence, and any stale alert is dismissed. **Never nag a user who opted out.** |
| Everything alive | Silence, alert dismissed |
| Accessibility disabled (not in the settings string) | Notify on the **first** sighting — we cannot re-grant it, so waiting a cycle buys nothing and costs 15 more minutes of unblocked scrolling |
| Accessibility granted but not bound | Notify on the **second consecutive** sighting, with its own copy ("turn it off and back on"). One confirming cycle separates a genuinely crashed service from one legitimately mid-bind: `mBindingServices` is not `mBoundServices`, so a check landing seconds after a boot or an update sees the same thing |
| Foreground service dead, accessibility alive | **Restart it silently.** Blocking still works, so "protection has stopped" would be a lie |
| Foreground service still dead next cycle | Notify — the restart did not hold, which is a phone actively shutting Nudge down, and that the user *can* act on |

A **12-hour cooldown** sits over both alerts. A phone that keeps killing us would otherwise produce
an alert every 15 minutes for as long as it stays broken, and a notification the user learns to
swipe away is worth less than no notification at all. A clock moved backwards resets the cooldown
rather than muting until wall-clock catches up.

It has to be a **push notification, not an in-app banner.** An app blocker's whole point is to be
invisible until it is needed, so the user has no organic reason to open Nudge — a banner would have
left issue #23's reporter blind all night and into the next day. `POST_NOTIFICATIONS` was already
declared but had never been *requested*: it is a runtime grant from Android 13, so without the
request in `MainActivity` both this alert and the ongoing monitor notification are dropped on the
floor on exactly the modern devices this protects. The alert uses its own channel, so muting the
permanent silent one does not mute this.

Tapping it lands in Nudge's own Settings screen, **not** straight in the system accessibility list:
that screen carries the Play-mandated prominent-disclosure dialog, and this app has been rejected
once on that gate already (`docs/play-store.md`).

### 3. Settings shows live truth

The permission rows track reality through three refresh paths, because the accessibility answer has
two halves that change at different moments and the other two permissions have no watchable key at
all:

1. A `ContentObserver` on `ProtectionStatus.ACCESSIBILITY_SERVICES_URI`, which fires the instant the
   enabled-services setting changes (a user toggle, a force-stop) even while this screen is on top.
2. `AccessibilityConnectionSignal`, raised from the accessibility service's own `onServiceConnected`
   and `onDestroy`.
3. An `ON_RESUME` recheck of all three permissions, the only path available to overlay and
   usage-stats, which are `AppOpsManager` modes with nothing to observe.

**Why (2) exists.** The settings string is written when the toggle flips; the system binds the
service *afterwards*, asynchronously (`updateServicesLocked` then `bindServiceAsUser`, the component
sitting in `mBindingServices` until `onServiceConnected` lands). So the observer in (1) fires during
that gap and reads granted-but-not-connected, which is byte-for-byte the crashed state. That is a
momentary wrong tick only if something reads again, and while the screen stays resumed, nothing did:
`ON_RESUME` never fires for a re-enable from a split window, from `adb shell settings put`, or from
a quick-settings tile. The stale reading **latched**, and device QA watched the red cross and the
"turn it off and back on" copy sit there for 20+ seconds over a service `dumpsys accessibility` had
shown bound within 3. It would not have healed on its own.

The fix is the event, not a timer. `onServiceConnected` IS the bind completing: the earliest correct
moment to look again, no interval to tune, no wakeups when nothing is happening. The signal only
says "look again" - the answer still comes from `ProtectionStatus`, which asks the framework. (A
static flag would be the mistake `NudgeMonitorService.isRunning` is documented as deliberately not
making for the watchdog. It is usable here only because the UI asks a different question, "has
something changed", of a process that is by definition alive to ask it.)

`LivePermissionStateContractTest` pins all of it, because a one-shot read is a defect in *where* the
code is, not in any value a JVM test can inspect.

The screen also no longer rolls its own accessibility read. It used to ask
`enabledServices.contains(context.packageName)` — a substring test that says yes for any component
of ours and for any package whose name merely contains ours. Both it and the watchdog now go
through `ProtectionStatus`, so they cannot disagree. Note the two package names in play:
`applicationId` is `dev.astraedus.nudge` and `namespace` is `com.astraedus.nudge`, so any matcher
assuming the class is a child of the package is wrong here.

## How to test this

The watchdog's detection half is verifiable by hand; its **notification half was not**, and shipped
once without ever having been seen to fire. `adb shell cmd jobscheduler run -f dev.astraedus.nudge
<jobId>` prints "Running job [FORCED]" and a `jobFinished` about 12ms later, but `doWork()` never
executes: no `WM-WorkerWrapper: Worker result` line is ever emitted (reproduced three times on the
Pixel 3). WorkManager will not run a periodic worker early. So the only way to watch the alert was
to be holding the phone through two consecutive natural cycles while the accessibility service
happened to be dead, and the one divergence device QA managed to reproduce self-healed first.

Hence `WatchdogDebugReceiver`: a **debug-build-only** broadcast that runs one check synchronously.
It calls `ProtectionCheck.run` and nothing else, so it exercises the exact code the 15-minute
schedule runs. It lives in `app/src/debug/`, class and manifest entry both, so it does not exist in
a release APK at all; `WatchdogDebugTriggerContractTest` fails if either half leaks into `src/main`,
or if the receiver ever grows a signal read or a decision of its own.

### Running one check

```bash
adb shell am broadcast \
  -a dev.astraedus.nudge.debug.RUN_WATCHDOG \
  -n dev.astraedus.nudge/com.astraedus.nudge.service.WatchdogDebugReceiver
```

The explicit `-n` component is required, not decoration: a custom action is an *implicit* broadcast,
and manifest-declared receivers have not received those since API 26. Note the two package names
(`applicationId` is `dev.astraedus.nudge`, `namespace` is `com.astraedus.nudge`) - the component is
one of each.

The verdict comes back in the broadcast result, so you read the DECISION rather than inferring it
from whether a notification appeared:

```
Broadcast completed: result=0, data="global=true granted=true connected=false monitorRunning=true
wasDegraded=true | notify=ACCESSIBILITY_CRASHED dismiss=false startService=false degradedNow=true"
```

Same line on logcat under tag `ProtectionWatchdog`.

Two extras stage persisted inputs that a real earlier cycle would have written. They are fixtures,
not a second code path - both go through the same `recordProtectionCheck` the check itself uses:

| Extra | Effect |
|---|---|
| `--ez reset true` | Clears the degraded flag and the 12-hour alert cooldown. Without it, a state can only be re-tested twice a day. |
| `--ez degraded true` | Marks the previous check as degraded, satisfying the confirming-cycle rule in one broadcast. Sending the broadcast twice is more faithful and is preferred; this is for a fault that is only briefly reproducible. |

### Forcing each state

The master toggle is Nudge's own switch on the Home screen. `X` below is the accessibility component
`dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService`.

| State | How to force it | Expected verdict |
|---|---|---|
| **Healthy** | Master toggle on, accessibility on. Confirm with `adb shell dumpsys accessibility \| grep -E "Enabled services\|Bound services"` - our component must be in BOTH. | `notify=none dismiss=true degradedNow=false` |
| **Monitoring off** | Master toggle off in the app. | `global=false notify=none dismiss=true` - never nag a user who opted out. |
| **Granted but not connected** (`ACCESSIBILITY_CRASHED`) | `adb shell am crash dev.astraedus.nudge` (works because a debug build is debuggable). The process dies, AOSP adds the component to `mCrashedServices` and never rebinds it, and it stays in the settings string. `dumpsys accessibility` then shows it under `Enabled services` with `Bound services:{}` - that exact divergence is the shipping bug. | First broadcast: `connected=false notify=none degradedNow=true`. **Second** broadcast: `notify=ACCESSIBILITY_CRASHED`. |
| **User-disabled** (`ACCESSIBILITY_DISABLED`) | Turn the toggle off in system Settings, or `adb shell settings put secure enabled_accessibility_services ""` - **record the old value first** (`settings get secure enabled_accessibility_services`), that key holds every service on the device, not just ours. | `granted=false notify=ACCESSIBILITY_DISABLED` on the FIRST broadcast - we cannot re-grant it, so waiting a cycle only costs the user 15 more minutes. |
| **Foreground service dead** (`MONITOR_SERVICE_DEAD`) | Same `am crash`; `NudgeMonitorService.isRunning` is a static, so it comes back false in the new process. Reachable only with accessibility healthy, since the fault order is granted, then connected, then service. | First: `startService=true`. Second, if the restart did not hold: `notify=MONITOR_SERVICE_DEAD`. |

A fresh run of a two-cycle state is therefore: broadcast with `--ez reset true`, then broadcast plain.

### Asserting the notification

```bash
adb shell dumpsys notification --noredact | grep -B2 -A12 nudge_protection_alerts
```

What to check:

- **Posted**: a record with `pkg=dev.astraedus.nudge`, `id=2`, `channel=nudge_protection_alerts`.
  Id 1 is the permanent monitor notification and is not this. The channel is separate on purpose:
  a user who mutes the silent ongoing one must not thereby mute this.
- **Copy matches the fault**: `ACCESSIBILITY_CRASHED` says turn it off and back on, and must NEVER
  show the "grant the permission" copy - that user's switch already reads on, and telling them to
  turn on what is already on is how a safety notification gets muted.
- **Not posted**: no such record. Nothing about a healthy check or a disabled master toggle may
  produce one.
- **Dismissed**: run a healthy check while an alert is showing; the record disappears (the decision
  line will read `dismiss=true`).

If a check returns `notify=<FAULT>` and no record appears, the decision is fine and the POST is
being dropped - check `POST_NOTIFICATIONS` (`adb shell dumpsys package dev.astraedus.nudge | grep
POST_NOTIFICATIONS`). That distinction is exactly why the verdict is returned in the broadcast
result and not inferred from the shade.

## Known limits (deliberate, not oversights)

- **Doze defers the check.** WorkManager periodic work runs in maintenance windows, so on a phone
  in deep idle overnight the alert may arrive in the morning rather than at 2am. Still infinitely
  better than never, and the alternative is an exact alarm with a Play-policy cost.
- **A killed process is now fully detected, but only after one confirming cycle.** The
  bound-services read sees it immediately; the confirming cycle is what separates a crashed service
  from one legitimately mid-bind, and costs up to 15 minutes. (This bullet used to claim Android
  re-binds a killed accessibility service and that the state was therefore only half-detectable.
  AOSP does not: `mCrashedServices` is never retried. That retracted claim is why the two-read
  design exists.) No official API distinguishes "OEM killed it" from "user turned it off", so the
  fault copy is inferred from which of the two lists lost us.
- **`isRunning` is in-process only.** That is the point (see above), but it means the first check
  after any process restart always reports the service dead, which is why a dead service is
  restarted silently and only notified about if it is *still* dead on the following run.

## Deliberately NOT built here

Each is a separate lane, and each has a real cost that has not been paid yet:

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — genuine Play-policy risk, and issue #23 is direct
  evidence it would not have been sufficient anyway (that reporter had already disabled battery
  restrictions).
- Manufacturer-aware onboarding and autostart deep links (`com.miui.securitycenter`, Samsung's
  "never sleeping apps", …).
- The passthrough that survives screen-off, the background-activity-launch overlay fallback, the
  untested `isOverlayActive`/content-change seam, and the local diagnostics log. All are in
  `docs/BACKLOG.md` and in the resilience audit's fix order.
