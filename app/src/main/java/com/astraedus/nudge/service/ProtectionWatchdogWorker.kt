package com.astraedus.nudge.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
 * This class is now only the SCHEDULE. The check itself lives in [ProtectionCheck] and the policy
 * in `ProtectionWatchdog`, which is pure and unit-tested. That split is not tidiness: WorkManager
 * cannot be made to run this worker on demand (`cmd jobscheduler run -f` finishes the job in ~12ms
 * without `doWork()` ever executing), so the check had to become reachable from somewhere else
 * before anyone could watch the alert fire even once.
 */
class ProtectionWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * The whole of the periodic run. [ProtectionCheck.run] holds the gather-decide-act body so the
     * debug trigger can exercise the exact same code, see that file for why a trigger that runs a
     * parallel reimplementation would be worse than having no trigger.
     */
    override suspend fun doWork(): Result {
        ProtectionCheck.run(applicationContext)

        // Never Result.retry(): the next periodic run is 15 minutes away and this check is only
        // ever about "right now". A retry backlog would just alert about a state that has passed.
        return Result.success()
    }

    companion object {
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
