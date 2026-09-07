package com.astraedus.nudge.domain.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision that turns a silent failure into something the user hears about.
 *
 * The failure this exists for is not reproducible: a phone quietly killing Nudge at 3am, after
 * which AOSP never rebinds the accessibility service and never stops reporting it as enabled. So
 * the policy is a pure function over a snapshot, and this is where it is pinned. Three properties
 * matter more than any individual case:
 *
 *  - a user who turned monitoring OFF is never notified about anything (the fastest way to lose
 *    this channel is to teach people our alerts are noise),
 *  - an alert is only ever claimed for something that is actually true — a dead foreground
 *    service is not "blocking has stopped", so it does not say so and gets fixed silently first,
 *  - and **granted is not connected**. The first design of this watchdog keyed on the settings
 *    string alone and would have reported "all good" through the entire failure, because AOSP
 *    leaves a crashed service listed as enabled forever. `accessibilityGranted` is intent;
 *    `accessibilityConnected` is reality; the gap between them is the bug.
 */
class ProtectionWatchdogTest {

    private val now = 1_700_000_000_000L

    private fun snapshot(
        globalEnabled: Boolean = true,
        accessibilityGranted: Boolean = true,
        accessibilityConnected: Boolean = true,
        monitorServiceRunning: Boolean = true,
        wasDegradedLastCheck: Boolean = false,
        lastNotifiedAtMs: Long = 0L,
        nowMs: Long = now
    ) = ProtectionSnapshot(
        globalEnabled = globalEnabled,
        accessibilityGranted = accessibilityGranted,
        accessibilityConnected = accessibilityConnected,
        monitorServiceRunning = monitorServiceRunning,
        wasDegradedLastCheck = wasDegradedLastCheck,
        lastNotifiedAtMs = lastNotifiedAtMs,
        nowMs = nowMs
    )

    // --- Healthy and opted-out: silence -------------------------------------------------------

    @Test
    fun `everything alive says nothing and clears any stale alert`() {
        val decision = ProtectionWatchdog.decide(snapshot())

        assertNull(decision.notifyOf)
        assertFalse(decision.startMonitorService)
        assertTrue(decision.dismissNotification)
        assertFalse(decision.degradedNow)
    }

    /**
     * The one case that must never notify. The master toggle being off means the user chose this;
     * telling them their blocking has stopped would be both wrong and nagging.
     */
    @Test
    fun `monitoring turned off by the user is silent even with everything down`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(
                globalEnabled = false,
                accessibilityGranted = false,
                monitorServiceRunning = false,
                wasDegradedLastCheck = true
            )
        )

        assertNull(decision.notifyOf)
        assertFalse(decision.startMonitorService)
        assertFalse(decision.degradedNow)
    }

    /** And it clears an alert posted before they turned it off, which would otherwise linger. */
    @Test
    fun `turning monitoring off dismisses an alert raised while it was on`() {
        assertTrue(
            ProtectionWatchdog.decide(
                snapshot(globalEnabled = false, wasDegradedLastCheck = true)
            ).dismissNotification
        )
    }

    // --- Accessibility disabled: the real failure ---------------------------------------------

    /**
     * The headline case, and the shape of GitHub issue #23: the user has monitoring on and their
     * phone has switched the accessibility service off overnight. Blocking is genuinely dead and
     * nothing we can write re-grants it, so this speaks on the FIRST sighting — waiting a cycle
     * buys nothing and costs 15 more minutes of unblocked scrolling.
     */
    @Test
    fun `accessibility disabled while monitoring is on notifies immediately`() {
        val decision = ProtectionWatchdog.decide(snapshot(accessibilityGranted = false))

        assertEquals(ProtectionFault.ACCESSIBILITY_DISABLED, decision.notifyOf)
        assertTrue(decision.degradedNow)
        assertFalse(decision.dismissNotification)
    }

    @Test
    fun `accessibility disabled and service dead reports the accessibility fault and restarts the service`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(accessibilityGranted = false, monitorServiceRunning = false)
        )

        assertEquals(
            "the fault the user can act on wins the notification",
            ProtectionFault.ACCESSIBILITY_DISABLED,
            decision.notifyOf
        )
        assertTrue(
            "the foreground service is still worth having back for process priority",
            decision.startMonitorService
        )
    }

    // --- Enabled but dead: the case a settings-string watchdog cannot see ----------------------

    /**
     * **The headline failure, and the one the first version of this watchdog would have missed
     * entirely.** Verified against AOSP master: when an accessibility service's process is killed,
     * `binderDied()` puts the component in `mCrashedServices`, `updateServicesLocked()` explicitly
     * `continue`s past it forever, and it is **left in `ENABLED_ACCESSIBILITY_SERVICES`**. So the
     * setting says "enabled", the system's own toggle says "on", and blocking is permanently dead.
     *
     * A snapshot with `granted = true, connected = false` is that state, and it must be reported as
     * its OWN fault, because its recovery (off and on again) is the opposite of the other one's.
     */
    @Test
    fun `granted but not connected is reported as a crash, not as disabled`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(
                accessibilityGranted = true,
                accessibilityConnected = false,
                wasDegradedLastCheck = true
            )
        )

        assertEquals(ProtectionFault.ACCESSIBILITY_CRASHED, decision.notifyOf)
        assertTrue(decision.degradedNow)
    }

    /**
     * Granted-but-not-bound is ALSO what a service legitimately mid-bind looks like — AOSP's
     * `mBindingServices` is not `mBoundServices` — so a check landing in the seconds after a boot
     * or an app update would see it too. One confirming cycle separates a real crash from a bind in
     * flight, at a cost of 15 minutes on a failure that otherwise lasts all night.
     */
    @Test
    fun `a first sighting of granted-but-not-connected waits one cycle before speaking`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(accessibilityConnected = false, wasDegradedLastCheck = false)
        )

        assertNull("could still be a bind in flight", decision.notifyOf)
        assertTrue("but it is recorded, so the next run can confirm it", decision.degradedNow)
    }

    /** A service that finished binding between two checks is not a fault at all. */
    @Test
    fun `a bind that completes before the next check clears silently`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(accessibilityConnected = true, wasDegradedLastCheck = true)
        )

        assertNull(decision.notifyOf)
        assertTrue(decision.dismissNotification)
        assertFalse(decision.degradedNow)
    }

    /**
     * Not granted at all outranks not connected: a service the user has switched off is also not
     * bound, and "turn it back on" is the right instruction there. Ordering the `when` the other
     * way would tell every user who disabled Nudge to toggle something that is already off.
     */
    @Test
    fun `a disabled service reports disabled even though it is also not connected`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(
                accessibilityGranted = false,
                accessibilityConnected = false,
                wasDegradedLastCheck = true
            )
        )

        assertEquals(ProtectionFault.ACCESSIBILITY_DISABLED, decision.notifyOf)
    }

    /**
     * The crash outranks a dead foreground service: both are true after an LMK kill (the process
     * took our own service with it), and only one of them means blocking has stopped.
     */
    @Test
    fun `a crash outranks the dead foreground service that came with it`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(
                accessibilityConnected = false,
                monitorServiceRunning = false,
                wasDegradedLastCheck = true
            )
        )

        assertEquals(ProtectionFault.ACCESSIBILITY_CRASHED, decision.notifyOf)
        assertTrue(
            "and the foreground service still comes back — its priority is what stops the next kill",
            decision.startMonitorService
        )
    }

    /**
     * Every fault needs its own copy, because every fault has a different recovery. A test that
     * merely counted them would not catch a new fault being folded into an existing branch.
     */
    @Test
    fun `each fault is distinguishable so the notification can name the right recovery`() {
        val disabled = ProtectionWatchdog.decide(
            snapshot(accessibilityGranted = false)
        ).notifyOf
        val crashed = ProtectionWatchdog.decide(
            snapshot(accessibilityConnected = false, wasDegradedLastCheck = true)
        ).notifyOf
        val serviceDead = ProtectionWatchdog.decide(
            snapshot(monitorServiceRunning = false, wasDegradedLastCheck = true)
        ).notifyOf

        assertEquals(
            "all three faults must be reachable and distinct",
            setOf(
                ProtectionFault.ACCESSIBILITY_DISABLED,
                ProtectionFault.ACCESSIBILITY_CRASHED,
                ProtectionFault.MONITOR_SERVICE_DEAD
            ),
            setOf(disabled, crashed, serviceDead)
        )
    }

    // --- Foreground service dead: fix first, speak only if the fix does not hold ---------------

    /**
     * Blocking is enforced by the accessibility binding, not by this service, so a dead foreground
     * service is not "protection has stopped". We can restart it ourselves, and a notification the
     * user can do nothing about is exactly the noise that gets our channel muted.
     */
    @Test
    fun `a newly dead foreground service is restarted silently`() {
        val decision = ProtectionWatchdog.decide(snapshot(monitorServiceRunning = false))

        assertTrue(decision.startMonitorService)
        assertNull(decision.notifyOf)
        assertTrue("but it is recorded, so the next run knows the restart was tried", decision.degradedNow)
    }

    /**
     * Still dead one cycle after we restarted it: the restart did not hold, which is a phone
     * actively shutting Nudge down. That the user CAN act on (autostart, battery settings), so now
     * we say so.
     */
    @Test
    fun `a foreground service still dead after a restart attempt notifies`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(monitorServiceRunning = false, wasDegradedLastCheck = true)
        )

        assertEquals(ProtectionFault.MONITOR_SERVICE_DEAD, decision.notifyOf)
        assertTrue(decision.startMonitorService)
    }

    @Test
    fun `recovery after a restart clears the alert and the degraded flag`() {
        val decision = ProtectionWatchdog.decide(snapshot(wasDegradedLastCheck = true))

        assertNull(decision.notifyOf)
        assertTrue(decision.dismissNotification)
        assertFalse(decision.degradedNow)
    }

    // --- Cooldown -----------------------------------------------------------------------------

    /**
     * A phone that keeps killing us would otherwise produce an alert every 15 minutes for as long
     * as it stays broken. A notification the user learns to swipe away is worth less than none.
     */
    @Test
    fun `a second alert inside the cooldown window stays quiet`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(
                accessibilityGranted = false,
                lastNotifiedAtMs = now - ProtectionWatchdog.NOTIFICATION_COOLDOWN_MS + 1
            )
        )

        assertNull(decision.notifyOf)
        assertTrue("still degraded, just not said again", decision.degradedNow)
    }

    @Test
    fun `the alert repeats once the cooldown has elapsed`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(
                accessibilityGranted = false,
                lastNotifiedAtMs = now - ProtectionWatchdog.NOTIFICATION_COOLDOWN_MS
            )
        )

        assertEquals(ProtectionFault.ACCESSIBILITY_DISABLED, decision.notifyOf)
    }

    /**
     * A clock moved backwards — timezone change, NTP correction, the user setting the date — must
     * not mute the alert until wall-clock time catches up, which could be days.
     */
    @Test
    fun `a clock moved backwards resets the cooldown instead of muting`() {
        val decision = ProtectionWatchdog.decide(
            snapshot(accessibilityGranted = false, lastNotifiedAtMs = now + 86_400_000L)
        )

        assertEquals(ProtectionFault.ACCESSIBILITY_DISABLED, decision.notifyOf)
    }

    @Test
    fun `the cooldown is long enough to be a daily-ish cap, not a per-run one`() {
        assertTrue(
            "the watchdog runs every 15 minutes; a short cooldown would be no cooldown",
            ProtectionWatchdog.NOTIFICATION_COOLDOWN_MS >= 6L * 60L * 60L * 1000L
        )
    }
}
