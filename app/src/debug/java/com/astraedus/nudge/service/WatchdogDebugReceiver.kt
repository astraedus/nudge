package com.astraedus.nudge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs one protection check right now, on demand, from `adb shell am broadcast`. **Debug builds
 * only**, this file is in the `debug` source set and its manifest entry is in
 * `src/debug/AndroidManifest.xml`, so neither exists in a release APK.
 *
 * ## Why this had to exist
 *
 * [ProtectionWatchdogWorker] runs on WorkManager's 15-minute periodic schedule, and WorkManager
 * cannot be made to run it early. `adb shell cmd jobscheduler run -f <pkg> <jobId>` reports
 * "Running job [FORCED]" and a `jobFinished` ~12ms later, but `doWork()` never executes, no
 * `WM-WorkerWrapper: Worker result` line is ever emitted (reproduced three times on the Pixel 3).
 * So the only way to observe the protection alert was to be holding the phone across two
 * consecutive natural cycles while the accessibility service happened to be dead, and the one
 * reproduced divergence self-healed before the second cycle landed. We were about to ship a
 * user-facing safety notification that had never been seen to fire.
 *
 * ## Why it calls [ProtectionCheck.run] and nothing else
 *
 * A trigger that re-derives the check would exercise different code than production runs, which is
 * worse than no trigger: it would return green while the shipping path stayed broken. Everything
 * below the extras handling is one call. `WatchdogDebugTriggerContractTest` fails if this file
 * grows a `ProtectionWatchdog.decide`, a `ProtectionStatus` read, or a notifier call of its own.
 *
 * The two extras only STAGE persisted inputs that a real earlier cycle would have written, they
 * are fixtures, not a second code path:
 *
 * - `--ez reset true` clears the degraded flag and the alert cooldown, so a state can be tested
 *   repeatedly instead of once every 12 hours (see `ProtectionWatchdog.NOTIFICATION_COOLDOWN_MS`).
 * - `--ez degraded true` marks the previous check as degraded, which is what the confirming-cycle
 *   rule reads. Sending the broadcast twice does the same thing more faithfully and is the
 *   preferred form; this extra exists for when the fault itself is only briefly reproducible.
 *
 * The verdict comes back in the broadcast's result data, so QA reads the DECISION rather than
 * inferring it from whether a notification appeared, those differ when a `POST_NOTIFICATIONS`
 * grant is missing, which is a failure mode worth telling apart from "decided to stay quiet".
 */
class WatchdogDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        // The check suspends on DataStore reads, so it cannot run inline in onReceive's 10-second
        // main-thread window. goAsync() keeps the broadcast (and the result data) alive across it.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val result = try {
                stagePersistedState(appContext, intent)
                ProtectionCheck.run(appContext).summary()
            } catch (t: Throwable) {
                // Report the failure rather than dying silently: an exception here is itself a
                // finding about the production path, since it is the same code.
                "ERROR ${t.javaClass.simpleName}: ${t.message}"
            }

            Log.i(TAG, result)
            try {
                pending.setResultData(result)
            } catch (_: RuntimeException) {
                // Not an ordered broadcast (a sender other than `am broadcast`). The log line above
                // is the fallback; there is nothing to recover here.
            }
            pending.finish()
        }
    }

    private suspend fun stagePersistedState(context: Context, intent: Intent) {
        val reset = intent.getBooleanExtra(EXTRA_RESET, false)
        val hasDegraded = intent.hasExtra(EXTRA_DEGRADED)
        if (!reset && !hasDegraded) return

        val preferences = ProtectionCheck.preferences(context)
        if (reset) {
            // 0 is "never alerted", which is how the cooldown check reads a fresh install.
            preferences.recordProtectionCheck(degraded = false, alertShownAtMs = 0L)
        }
        if (hasDegraded) {
            preferences.recordProtectionCheck(
                degraded = intent.getBooleanExtra(EXTRA_DEGRADED, false),
                alertShownAtMs = null
            )
        }
    }

    private companion object {
        const val TAG = "ProtectionWatchdog"
        const val EXTRA_RESET = "reset"
        const val EXTRA_DEGRADED = "degraded"
    }
}
