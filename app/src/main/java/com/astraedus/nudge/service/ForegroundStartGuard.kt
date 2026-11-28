package com.astraedus.nudge.service

/**
 * Runs a service's `startForeground` call and reports whether the platform allowed it.
 *
 * ## Why this exists (Play vitals, 1.31.3 / versionCode 82, 2026-11-26)
 * An Infinix NOTE 30 Pro on Android 14 crashed five times in `NudgeMonitorService.onStartCommand`
 * with `ForegroundServiceStartNotAllowedException` thrown out of `Service.startForeground`. Nothing
 * caught it, so it surfaced as a `RuntimeException` from `ActivityThread.handleServiceArgs` and
 * killed the whole process, and with it the accessibility service that does the blocking.
 *
 * [NudgeMonitorService.start] already catches the refusal from `startForegroundService`, but the
 * platform checks a second time, when the service itself calls `startForeground`
 * (`ActiveServices.setServiceForegroundInnerLocked`, which throws when `mAllowStartForeground` is
 * `REASON_DENIED`). That second check is the one that fired here.
 *
 * ## What the caller must do with `false`
 * The same AOSP method clears `fgRequired` and removes `SERVICE_FOREGROUND_TIMEOUT_MSG` BEFORE it
 * checks the restriction and throws (android12-release and android14-release alike). So a denied
 * call leaves no pending "did not call startForeground in time" deadline behind: the service is
 * simply a started, non-foreground service. The caller stops it and does not ask to be made
 * sticky; the next legitimate start (app launch, boot, the master toggle, the watchdog) tries again.
 *
 * ## Why `IllegalStateException`
 * `ForegroundServiceStartNotAllowedException` (API 31+) extends `ServiceStartNotAllowedException`,
 * which extends `IllegalStateException`. Catching the parent works on every API level this app
 * supports (minSdk 26, where the subclass does not exist) and matches what [NudgeMonitorService.start]
 * already catches. Any other exception still propagates: a bug elsewhere must stay loud.
 *
 * A plain function, not service code, so the JVM tests can drive it without a platform.
 *
 * @return null when [startForeground] completed, or the refusal it threw.
 */
internal inline fun tryStartForeground(startForeground: () -> Unit): IllegalStateException? =
    try {
        startForeground()
        null
    } catch (denied: IllegalStateException) {
        denied
    }
