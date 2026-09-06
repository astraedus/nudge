# Service lifecycle and the protection watchdog

Covers what keeps Nudge's enforcement alive, what notices when it dies, and how the user finds out.
**Read before touching `NudgeMonitorService`, `BootReceiver`, `ProtectionWatchdogWorker`,
`ProtectionStatus`, `NudgeApp`, `MainActivity`, or the permission rows on the Settings screen.**

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

The signal is membership in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
(`ProtectionStatus` / `EnabledAccessibilityServices`). It is the one signal reliable across every
OEM **and every root cause** — a memory kill, MIUI's revoke-on-kill, an update-disable, and a user
switching it off themselves all converge on our component leaving that list — so no OEM-specific
detection code is needed, only OEM-specific *messaging* later.

The policy lives in `domain/health/ProtectionWatchdog`, a **pure function**, because the failure it
exists for (a phone quietly switching Nudge off overnight) is not reproducible on a device. The
worker gathers values and carries out the verdict; it holds no policy of its own, and
`ServiceLifecycleContractTest` asserts it does not grow one.

| State | What happens |
|---|---|
| Master toggle off | Silence, and any stale alert is dismissed. **Never nag a user who opted out.** |
| Everything alive | Silence, alert dismissed |
| Accessibility disabled | Notify on the **first** sighting — we cannot re-grant it, so waiting a cycle buys nothing and costs 15 more minutes of unblocked scrolling |
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

The permission rows track reality through a `ContentObserver` on
`ProtectionStatus.ACCESSIBILITY_SERVICES_URI` plus an `ON_RESUME` recheck of all three permissions
(overlay and usage-stats have no watchable `Settings.Secure` key). `LivePermissionStateContractTest`
pins the shape, because a one-shot read is a defect in *where* the code is, not in any value a JVM
test can inspect.

The screen also no longer rolls its own accessibility read. It used to ask
`enabledServices.contains(context.packageName)` — a substring test that says yes for any component
of ours and for any package whose name merely contains ours. Both it and the watchdog now go
through `ProtectionStatus`, so they cannot disagree. Note the two package names in play:
`applicationId` is `dev.astraedus.nudge` and `namespace` is `com.astraedus.nudge`, so any matcher
assuming the class is a child of the package is wrong here.

## Known limits (deliberate, not oversights)

- **Doze defers the check.** WorkManager periodic work runs in maintenance windows, so on a phone
  in deep idle overnight the alert may arrive in the morning rather than at 2am. Still infinitely
  better than never, and the alternative is an exact alarm with a Play-policy cost.
- **A process kill that leaves the setting intact is only half-detected.** Android re-binds an
  enabled accessibility service when the process restarts, so a "is it in the list" check cannot
  see a transient unbind. The foreground-service liveness flag covers the process-death half. No
  official API distinguishes "OEM killed it" from "user turned it off" — see the OEM research.
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
