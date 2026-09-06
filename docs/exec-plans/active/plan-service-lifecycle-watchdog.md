# Plan, make service death impossible to miss (audit F1 + F2)

Spec: `~/ops/routes/nudge/research/service-resilience-audit-2026-09-06.md` (F1, F2) and
`~/ops/routes/nudge/research/oem-background-kill-2026-09-06.md` (§2.1, mitigation 1).

## The defect being closed
`NudgeMonitorService.start()` had exactly one call site (`BootReceiver.kt:38`) and the receiver
only filtered `BOOT_COMPLETED`, so after any Play auto-update the process is replaced, Android
disables the a11y service on an in-place update, and nothing restarts or notices either. Settings
read all three permissions with `remember { mutableStateOf(...) }`, one shot at first
composition, so it showed a green tick over a dead service.

## Three parts, one concern (deliberately not split)

### 1. Real start paths for the foreground service
- `MainActivity` observes `isGlobalEnabled && isOnboardingComplete` with `repeatOnLifecycle` and
  starts/stops the FGS. ONE observer covers all three required paths: app launch, master toggle
  on/off, onboarding completion (the user never leaves MainActivity, so completing onboarding
  flips the same flow).
- `BootReceiver` action guard widened from `!= ACTION_BOOT_COMPLETED` to a membership test over
  `{BOOT_COMPLETED, MY_PACKAGE_REPLACED}`; manifest gains the second action.
- Starting an already-running service is a no-op (`startForegroundService` -> `onStartCommand`
  again). Every start is wrapped: `ForegroundServiceStartNotAllowedException` (an
  `IllegalStateException`) is thrown for Android-12+ background starts, and the watchdog starts
  from the background.

### 2. Watchdog: detect silent death, tell the user by PUSH notification
- `WorkManager` periodic worker, 15-minute floor, enqueued `KEEP` from `NudgeApp.onCreate()` so it
  exists after every process start, boot and update.
- Signal: membership in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (the one signal reliable
  across every OEM and every root cause) + an in-process liveness flag on `NudgeMonitorService`.
- Decision is a PURE function (`domain/health/ProtectionWatchdog`) so it is JVM-testable:
  - monitoring off -> silent, dismiss any stale alert. Never nag a user who opted out.
  - healthy -> dismiss.
  - a11y disabled -> we cannot fix it ourselves; notify on first sighting.
  - FGS dead, a11y alive -> blocking still works; restart it SILENTLY. Notify only if it is still
    dead on the next check, i.e. the restart did not hold (that is an OEM kill worth reporting).
  - 12h notification cooldown either way (the OEM research explicitly warns about firing every
    15 minutes while still broken).

### 3. Settings shows live truth
- `ContentObserver` on `Settings.Secure.getUriFor(ENABLED_ACCESSIBILITY_SERVICES)` + an ON_RESUME
  recheck of all three permissions.

## Out of scope (noted, not built)
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play risk, already rejected once on the a11y gate),
manufacturer-specific onboarding/autostart deep links, passthrough screen-off expiry (F5),
background-activity-launch overlay fallback (F3), diagnostics log (F8), the untested
`isOverlayActive` seam (F6).

## Tests (JVM only, this repo has no `androidTest` source set at all)
- `EnabledAccessibilityServicesTest`, the membership parser.
- `ProtectionWatchdogTest`, the whole decision matrix incl. enabled-but-dead -> notify and
  user-disabled -> silent.
- `ServiceLifecycleContractTest`, source-level: the receiver handles both actions, the manifest
  declares both, `NudgeMonitorService.start` has more than the one boot call site, the watchdog is
  enqueued, and Settings holds no one-shot `remember { mutableStateOf(` permission read.
