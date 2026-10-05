package com.astraedus.nudge.service

import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.bounce.BounceAlert
import com.astraedus.nudge.domain.bounce.BounceDetector
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.logging.NudgeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BounceCheckIn], the adapter between the accessibility service and [BounceDetector]:
 * the cached switch, which [ForegroundSignal]s count as "opened an app", and that a fired alert
 * reaches the notifier exactly once.
 */
class BounceCheckInTest {

    private var now = 5_000_000L
    private val posted = mutableListOf<BounceAlert>()

    private val ownPackage = BuildConfig.APPLICATION_ID
    private val instagram = "com.instagram.android"
    private val others = listOf("com.discord", "com.reddit.frontpage", "com.twitter.android")

    private fun checkIn(enabled: Boolean = true) = BounceCheckIn(
        detector = BounceDetector(excludedPackages = BounceDetector.neverCountedPackages(ownPackage)),
        notify = { posted += it },
        clock = { now }
    ).also { it.setEnabled(enabled) }

    /** The owner's sequence through the real signal types the service feeds. */
    private fun bounce(c: BounceCheckIn) {
        c.onForegroundSignal(ForegroundSignal.AppWindow(instagram))
        c.onWall(instagram)
        c.onForegroundSignal(ForegroundSignal.OwnUi(ownPackage)) // the overlay itself
        c.onForegroundSignal(ForegroundSignal.Home("com.google.android.apps.nexuslauncher"))
        others.forEach { pkg ->
            now += 20_000L
            c.onForegroundSignal(ForegroundSignal.AppWindow(pkg))
        }
        now += 20_000L
        c.onForegroundSignal(ForegroundSignal.AppWindow(instagram))
        c.onWall(instagram)
    }

    @Test
    fun `the owner's bounce posts one check-in with real numbers`() {
        val c = checkIn()
        bounce(c)
        assertEquals(1, posted.size)
        assertEquals(4, posted.single().appCount)
        assertEquals(2, posted.single().minutes) // 80s from the first wall
    }

    @Test
    fun `switched off, nothing is fed and nothing is posted`() {
        val c = checkIn(enabled = false)
        bounce(c)
        bounce(c)
        assertTrue(posted.isEmpty())
        assertFalse("the detector was never armed", c.isTracking)
    }

    @Test
    fun `the default is off until the preference says otherwise`() {
        val c = BounceCheckIn(BounceDetector(), notify = { posted += it }, clock = { now })
        assertFalse(c.enabled)
        bounce(c)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `switching off mid-streak drops it, and switching back on starts clean`() {
        val c = checkIn()
        c.onWall(instagram)
        others.forEach { c.onForegroundSignal(ForegroundSignal.AppWindow(it)) }
        assertTrue(c.isTracking)

        c.setEnabled(false)
        assertFalse(c.isTracking)
        c.setEnabled(true)

        // The old streak would have fired on this wall; it is gone.
        c.onWall(instagram)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `only real app windows count as opening an app`() {
        val c = checkIn()
        c.onWall(instagram)
        // Every non-app signal, each with a fresh package so a miscount would be visible.
        listOf(
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.OwnUi(ownPackage),
            ForegroundSignal.AwarenessOverlay(ownPackage),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground("com.spotify.music"),
            ForegroundSignal.Home("com.google.android.apps.nexuslauncher")
        ).forEach(c::onForegroundSignal)
        // Two genuine apps: instagram + 2 = 3, one short of the threshold.
        c.onForegroundSignal(ForegroundSignal.AppWindow("com.discord"))
        c.onForegroundSignal(ForegroundSignal.AppWindow("com.reddit.frontpage"))
        c.onWall(instagram)
        assertTrue("non-app signals must not have counted toward the 4", posted.isEmpty())
    }

    @Test
    fun `the cooldown holds across the adapter`() {
        val c = checkIn()
        bounce(c)
        now += 60_000L
        bounce(c)
        assertEquals(1, posted.size)
        now += BounceDetector.COOLDOWN_MS
        bounce(c)
        assertEquals(2, posted.size)
    }

    @Test
    fun `a master-toggle reset forgets a streak but keeps the switch`() {
        val c = checkIn()
        c.onWall(instagram)
        c.reset()
        assertFalse(c.isTracking)
        assertTrue(c.enabled)
    }

    /** A kick is a wall: the executor tells its owner which key it kicked. */
    @Test
    fun `the auto-kick reports the kicked package`() {
        val kicked = mutableListOf<String>()
        val executor = AutoKickExecutor(
            interactionTracker = InteractionTracker(),
            counterOverlayManager = NoOpCounterOverlay,
            counterCache = CounterCacheRefresher(),
            logger = NudgeLog.NoOp,
            onKicked = { kicked += it },
            goHome = {}
        )
        executor.kick(instagram, reason = "test")
        assertEquals(listOf(instagram), kicked)
    }

    private object NoOpCounterOverlay : CounterOverlayManagerApi {
        override fun isVisible() = false
        override fun show(label: String) = Unit
        override fun updateCount(sessionCount: Int, dailyTotal: Int) = Unit
        override fun updateLabel(label: String) = Unit
        override fun hide() = Unit
    }
}
