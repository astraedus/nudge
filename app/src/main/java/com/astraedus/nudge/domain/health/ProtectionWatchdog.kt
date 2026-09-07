package com.astraedus.nudge.domain.health

/**
 * What has gone wrong, when something has. Each one has a DIFFERENT user recovery, which is the
 * whole reason they are separate values rather than one "protection is off" flag.
 */
enum class ProtectionFault {
    /**
     * Our service is not listed in `ENABLED_ACCESSIBILITY_SERVICES`. The user turned it off, or the
     * app was force-stopped (per AOSP the one event that actively strips the component). Recovery:
     * turn it on.
     */
    ACCESSIBILITY_DISABLED,

    /**
     * Our service is still LISTED as enabled but is not bound — the system killed its process and,
     * per AOSP, will never rebind it. Blocking is permanently dead while the toggle still reads
     * "on", in the system's own Accessibility screen as well as ours.
     *
     * Recovery is specifically **off and on again** (or a reboot): AOSP's `mCrashedServices` set is
     * cleared by a user toggle, an app update, an uninstall or a force-stop, and nothing else — its
     * own comment says the set exists so "users may toggle the on/off switch to retry". Telling
     * this user to "turn it on" would be useless: from where they are standing it already is on.
     */
    ACCESSIBILITY_CRASHED,

    /**
     * The foreground service is not running. Blocking still works (the accessibility binding is
     * what enforces), so this is not "protection has stopped" — it is the loss of the process
     * priority that keeps that binding alive, which matters most overnight, when the binding's own
     * `BIND_FOREGROUND_SERVICE_WHILE_AWAKE` protection has lapsed. We can restart it ourselves, so
     * it is only worth a notification once a restart has demonstrably failed to hold.
     */
    MONITOR_SERVICE_DEAD
}

/** Everything the watchdog is allowed to look at, gathered by the caller. */
data class ProtectionSnapshot(
    /** The master toggle. False means the user deliberately turned monitoring off. */
    val globalEnabled: Boolean,
    /** INTENT: our component is in `ENABLED_ACCESSIBILITY_SERVICES`. Survives a crash. */
    val accessibilityGranted: Boolean,
    /** REALITY: our component is in the system's BOUND services list. Does not survive a crash. */
    val accessibilityConnected: Boolean,
    val monitorServiceRunning: Boolean,
    /** Was the previous check already degraded? Persisted, so it survives a process kill. */
    val wasDegradedLastCheck: Boolean,
    /** Epoch millis of the last alert we posted; 0 when we have never posted one. */
    val lastNotifiedAtMs: Long,
    val nowMs: Long
)

/** The whole of what the worker is allowed to do, decided in one place. */
data class WatchdogDecision(
    val startMonitorService: Boolean,
    /** Non-null means post this alert now. Null means stay quiet. */
    val notifyOf: ProtectionFault?,
    val dismissNotification: Boolean,
    /** Persisted for the next run's [ProtectionSnapshot.wasDegradedLastCheck]. */
    val degradedNow: Boolean
)

/**
 * Decides, from one snapshot, whether protection has silently died and whether to say so.
 *
 * Pure on purpose. The failure this exists for — a phone quietly killing Nudge at 3am and the OS
 * never rebinding it — is not reproducible in a test on a device, so the decision has to be a
 * function of values a JVM test can hand it. `ProtectionWatchdogWorker` gathers the values and
 * carries out the verdict; it holds no policy of its own.
 *
 * Three rules the copy depends on:
 *
 * - **Never nag a user who opted out.** A false "Nudge has stopped blocking" for someone who turned
 *   the master toggle off themselves is the fastest way to teach people to swipe our notifications
 *   away, which would cost us the one channel that reaches them when it is real.
 * - **Say only what is true.** A dead foreground service is not stopped blocking, so it does not
 *   claim to be, and it gets fixed silently first.
 * - **Name the right recovery.** "Enabled but dead" and "not enabled" look identical to the user
 *   and need opposite instructions, so they stay separate faults all the way to the copy.
 */
object ProtectionWatchdog {

    /**
     * Minimum gap between two alerts. The OEM research is explicit about this: a phone that keeps
     * killing us would otherwise produce an alert every 15 minutes for as long as it stays broken,
     * and a notification the user learns to dismiss is worth less than no notification at all.
     */
    const val NOTIFICATION_COOLDOWN_MS: Long = 12L * 60L * 60L * 1000L

    fun decide(snapshot: ProtectionSnapshot): WatchdogDecision {
        // The user turned monitoring off. Nothing here is a fault, and a stale alert left in the
        // shade from before they turned it off would read as one.
        if (!snapshot.globalEnabled) return quiet()

        val fault = when {
            !snapshot.accessibilityGranted -> ProtectionFault.ACCESSIBILITY_DISABLED
            !snapshot.accessibilityConnected -> ProtectionFault.ACCESSIBILITY_CRASHED
            !snapshot.monitorServiceRunning -> ProtectionFault.MONITOR_SERVICE_DEAD
            else -> null
        } ?: return quiet()

        // Worth doing whichever fault fired: a dead foreground service is both a fault in its own
        // right and the process priority the accessibility binding wants back overnight.
        val startMonitorService = !snapshot.monitorServiceRunning

        val worthSaying = when (fault) {
            // Unambiguous — AOSP only drops the component on a force-stop or a user toggle — and we
            // cannot re-grant it, so waiting a cycle buys nothing and costs the user 15 more
            // minutes of unblocked scrolling.
            ProtectionFault.ACCESSIBILITY_DISABLED -> true

            // Granted-but-not-bound is normally permanent (AOSP never retries a crashed service),
            // but it is ALSO what a service legitimately mid-bind looks like: `mBindingServices`
            // is not `mBoundServices`, so a check that lands in the seconds after a boot or an app
            // update sees the same thing. One confirming cycle separates the two at a cost of 15
            // minutes on a failure that otherwise lasts all night — worth it, because a false
            // "your phone broke Nudge" is exactly the alert that gets the channel muted.
            ProtectionFault.ACCESSIBILITY_CRASHED -> snapshot.wasDegradedLastCheck

            // We restarted it last cycle and it is dead again, so the restart did not hold —
            // that is a phone actively shutting us down, which the user can act on.
            ProtectionFault.MONITOR_SERVICE_DEAD -> snapshot.wasDegradedLastCheck
        }

        return WatchdogDecision(
            startMonitorService = startMonitorService,
            notifyOf = if (worthSaying && cooledDown(snapshot)) fault else null,
            dismissNotification = false,
            degradedNow = true
        )
    }

    private fun quiet() = WatchdogDecision(
        startMonitorService = false,
        notifyOf = null,
        dismissNotification = true,
        degradedNow = false
    )

    private fun cooledDown(snapshot: ProtectionSnapshot): Boolean {
        if (snapshot.lastNotifiedAtMs <= 0L) return true
        // A clock moved backwards (timezone change, NTP correction, the user setting the date)
        // must not mute the alert until wall-clock catches up. Treat it as a reset.
        if (snapshot.nowMs < snapshot.lastNotifiedAtMs) return true
        return snapshot.nowMs - snapshot.lastNotifiedAtMs >= NOTIFICATION_COOLDOWN_MS
    }
}
