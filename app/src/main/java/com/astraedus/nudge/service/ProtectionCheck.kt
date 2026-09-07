package com.astraedus.nudge.service

import android.content.Context
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.health.ProtectionSnapshot
import com.astraedus.nudge.domain.health.ProtectionWatchdog
import com.astraedus.nudge.domain.health.WatchdogDecision
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * One protection check, start to finish: gather the signals, ask [ProtectionWatchdog] for a
 * verdict, carry the verdict out, persist what the next check needs.
 *
 * **This exists as its own object so that the periodic worker and the debug trigger run literally
 * the same code.** [ProtectionWatchdogWorker] is unreachable on demand, `cmd jobscheduler run -f`
 * reports the job as run in ~12ms without WorkManager ever invoking `doWork()` (reproduced three
 * times on the Pixel 3), so before this split the only way to see the alert fire was to be holding
 * the phone during two consecutive natural cycles while the service happened to be dead. We were
 * about to ship a user-facing safety notification nobody had ever watched fire.
 *
 * The obvious shortcut, a debug receiver that re-derives the check itself, would have been worse
 * than no trigger at all: a test path that exercises different code than production runs proves
 * nothing about production. `WatchdogDebugTriggerContractTest` pins the receiver to this function
 * and forbids it from reading a signal or making a decision of its own.
 *
 * No policy lives here either. The rules ("never nag a user who opted out", "a dead foreground
 * service is not stopped blocking", the confirming cycle, the 12-hour cooldown) are all in
 * [ProtectionWatchdog], which is pure and unit-tested, because the failure they exist for, a phone
 * quietly killing Nudge at 3am, is not reproducible on a device at all.
 */
object ProtectionCheck {

    private const val TAG = "ProtectionWatchdog"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProtectionCheckEntryPoint {
        fun nudgePreferences(): NudgePreferences
        fun nudgeLogger(): NudgeLogger
    }

    /**
     * What one check saw and did. Returned rather than kept private so the debug trigger can hand
     * QA the whole verdict in the `am broadcast` result, instead of QA inferring it from whether a
     * notification appeared, which cannot distinguish "decided to stay quiet" from "decided to
     * alert and the post was dropped".
     */
    data class Outcome(
        val snapshot: ProtectionSnapshot,
        val decision: WatchdogDecision,
        /** True when the platform refused the background foreground-service start. */
        val monitorServiceStartRefused: Boolean
    ) {
        fun summary(): String = buildString {
            append("global=").append(snapshot.globalEnabled)
            append(" granted=").append(snapshot.accessibilityGranted)
            append(" connected=").append(snapshot.accessibilityConnected)
            append(" monitorRunning=").append(snapshot.monitorServiceRunning)
            append(" wasDegraded=").append(snapshot.wasDegradedLastCheck)
            append(" | notify=").append(decision.notifyOf?.name ?: "none")
            append(" dismiss=").append(decision.dismissNotification)
            append(" startService=").append(decision.startMonitorService)
            append(" degradedNow=").append(decision.degradedNow)
            if (monitorServiceStartRefused) append(" (service start REFUSED by platform)")
        }
    }

    /** The [NudgePreferences] the check reads and writes, for callers that need to stage state. */
    fun preferences(context: Context): NudgePreferences = entryPoint(context).nudgePreferences()

    suspend fun run(context: Context): Outcome {
        val appContext = context.applicationContext
        val entryPoint = entryPoint(appContext)
        val preferences = entryPoint.nudgePreferences()
        val logger = entryPoint.nudgeLogger()

        val snapshot = ProtectionSnapshot(
            globalEnabled = preferences.isGlobalEnabled.first(),
            // Two reads, deliberately. The settings string is INTENT and survives a crash; the
            // bound-services list is REALITY and does not. The gap between them is the failure.
            accessibilityGranted = ProtectionStatus.isAccessibilityServiceGranted(appContext),
            accessibilityConnected = ProtectionStatus.isAccessibilityServiceConnected(appContext),
            monitorServiceRunning = NudgeMonitorService.isRunning,
            wasDegradedLastCheck = preferences.protectionDegraded.first(),
            lastNotifiedAtMs = preferences.protectionAlertShownAt.first(),
            nowMs = System.currentTimeMillis()
        )
        val decision = ProtectionWatchdog.decide(snapshot)

        var startRefused = false
        if (decision.startMonitorService) {
            val started = NudgeMonitorService.start(appContext)
            if (!started) {
                startRefused = true
                logger.w(
                    "Watchdog: the system refused a background foreground-service start",
                    tag = TAG
                )
            }
        }

        if (decision.dismissNotification) ProtectionAlertNotifier.dismiss(appContext)
        decision.notifyOf?.let { fault ->
            logger.w("Watchdog: protection degraded ($fault), alerting the user", tag = TAG)
            ProtectionAlertNotifier.notify(appContext, fault)
        }

        preferences.recordProtectionCheck(
            degraded = decision.degradedNow,
            // Only advance the cooldown clock when an alert actually went out. Writing `now` on
            // every run would keep the window permanently open and mute the next real alert.
            alertShownAtMs = if (decision.notifyOf != null) snapshot.nowMs else null
        )

        return Outcome(snapshot, decision, startRefused)
    }

    private fun entryPoint(context: Context): ProtectionCheckEntryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProtectionCheckEntryPoint::class.java
        )
}
