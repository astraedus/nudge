package com.astraedus.nudge.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.health.ProtectionSnapshot
import com.astraedus.nudge.domain.health.ProtectionWatchdog
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodically asks the question the app never used to ask: **am I still working?**
 *
 * Before this existed there was no watchdog, no alarm, no job of any kind in the codebase. The
 * foreground service was started from exactly one place (a device reboot), nothing restarted it
 * after a Play auto-update replaced the process, and nothing noticed when the accessibility
 * service died. Blocking stopped silently and stayed stopped until the user happened to reboot —
 * which is the whole of our one 3-star review, "It doesn't work sometimes", and of issue #23's
 * accessibility permission dying overnight.
 *
 * Read [ProtectionStatus] before changing what this checks. The signal that matters is the GAP
 * between the enabled-services setting and the system's bound-services list; a service whose
 * process was killed stays in the former forever and never returns to the latter, so a watchdog
 * built on the setting alone would sit here reporting "all good" through the exact failure it
 * exists to catch.
 *
 * The policy lives in [ProtectionWatchdog], which is pure and unit-tested. This class only gathers
 * the values and carries out the verdict.
 */
class ProtectionWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WatchdogEntryPoint {
        fun nudgePreferences(): NudgePreferences
        fun nudgeLogger(): NudgeLogger
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WatchdogEntryPoint::class.java
        )
        val preferences = entryPoint.nudgePreferences()
        val logger = entryPoint.nudgeLogger()

        val snapshot = ProtectionSnapshot(
            globalEnabled = preferences.isGlobalEnabled.first(),
            // Two reads, deliberately. The settings string is INTENT and survives a crash; the
            // bound-services list is REALITY and does not. The gap between them is the failure.
            accessibilityGranted = ProtectionStatus.isAccessibilityServiceGranted(applicationContext),
            accessibilityConnected = ProtectionStatus.isAccessibilityServiceConnected(applicationContext),
            monitorServiceRunning = NudgeMonitorService.isRunning,
            wasDegradedLastCheck = preferences.protectionDegraded.first(),
            lastNotifiedAtMs = preferences.protectionAlertShownAt.first(),
            nowMs = System.currentTimeMillis()
        )
        val decision = ProtectionWatchdog.decide(snapshot)

        if (decision.startMonitorService) {
            val started = NudgeMonitorService.start(applicationContext)
            if (!started) {
                logger.w(
                    "Watchdog: the system refused a background foreground-service start",
                    tag = TAG
                )
            }
        }

        if (decision.dismissNotification) ProtectionAlertNotifier.dismiss(applicationContext)
        decision.notifyOf?.let { fault ->
            logger.w("Watchdog: protection degraded ($fault), alerting the user", tag = TAG)
            ProtectionAlertNotifier.notify(applicationContext, fault)
        }

        preferences.recordProtectionCheck(
            degraded = decision.degradedNow,
            // Only advance the cooldown clock when an alert actually went out. Writing `now` on
            // every run would keep the window permanently open and mute the next real alert.
            alertShownAtMs = if (decision.notifyOf != null) snapshot.nowMs else null
        )

        // Never Result.retry(): the next periodic run is 15 minutes away and this check is only
        // ever about "right now". A retry backlog would just alert about a state that has passed.
        return Result.success()
    }

    companion object {
        private const val TAG = "ProtectionWatchdog"
        private const val WORK_NAME = "nudge_protection_watchdog"

        /**
         * Enqueues the periodic check. Called from `NudgeApp.onCreate`, so it re-arms itself on
         * every process start — after a boot, after an update, and after any OEM kill that
         * WorkManager itself recovers from. [ExistingPeriodicWorkPolicy.KEEP] makes that idempotent:
         * an already-scheduled worker keeps its own schedule instead of having its next run pushed
         * 15 minutes out every time the user opens the app.
         *
         * 15 minutes is WorkManager's floor for periodic work, and it is the right tolerance: the
         * failure is "blocking silently degrades for hours", not anything sub-minute.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProtectionWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
