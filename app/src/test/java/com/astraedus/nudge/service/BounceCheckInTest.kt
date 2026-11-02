package com.astraedus.nudge.service

import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.bounce.BounceAlert
import com.astraedus.nudge.domain.bounce.BounceDetector
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.web.WebSessionKey
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
        assertEquals(4, posted.single().count)
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

    /** A blocked site is a wall, and the site travels with it. */
    @Test
    fun `three blocked sites in one browser post one check-in`() {
        val c = checkIn()
        val chrome = "com.android.chrome"
        listOf("example.com", "example.org", "example.net").forEach { site ->
            now += 20_000L
            c.onWall(chrome, site)
        }
        assertEquals(1, posted.size)
        assertEquals(3, posted.single().count)
        assertEquals(BounceAlert.Unit.SITES, posted.single().unit)
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

    /**
     * The service hands every gate verdict to [BounceCheckIn.onBlockLaunched]; only a NEW launch is
     * a wall. A refused launch put nothing in front of the user, and a re-show of a buried overlay
     * is a wall already reported (#143). (This replaced a source scan of the service's branch.)
     */
    @Test
    fun `only a new launch is a wall, never a refusal or a re-show`() {
        BlockLaunchGate.Decision.values()
            .filter { it != BlockLaunchGate.Decision.LAUNCH }
            .forEach { refused ->
                val c = checkIn()
                bounceWith(c) { c.onBlockLaunched(refused, instagram) }
                assertTrue("$refused must not count as a wall", posted.isEmpty())
            }
        val c = checkIn()
        bounceWith(c) { c.onBlockLaunched(BlockLaunchGate.Decision.LAUNCH, instagram) }
        assertEquals("LAUNCH is the one verdict that is a wall", 1, posted.size)
    }

    /** #106: the site must reach the detector, or three blocked sites are one browser wall. */
    @Test
    fun `a launch for a blocked site carries the site`() {
        val c = checkIn()
        val chrome = "com.android.chrome"
        listOf("example.com", "example.org", "example.net").forEach { site ->
            now += 20_000L
            c.onBlockLaunched(BlockLaunchGate.Decision.LAUNCH, chrome, site)
        }
        assertEquals(BounceAlert.Unit.SITES, posted.single().unit)
    }

    /** A web kick is keyed `web:<domain>`: reported as the browser the user was in, plus the site. */
    @Test
    fun `a web kick is reported as the browser and the site`() {
        val c = checkIn()
        val chrome = "com.android.chrome"
        listOf("example.com", "example.org", "example.net").forEach { site ->
            now += 20_000L
            c.onKicked(WebSessionKey.PREFIX + site, browserPackage = chrome)
        }
        assertEquals(BounceAlert.Unit.SITES, posted.single().unit)
    }

    @Test
    fun `an app kick is reported as that app`() {
        val c = checkIn()
        bounceWith(c) { c.onKicked(instagram, browserPackage = "com.android.chrome") }
        assertEquals(1, posted.size)
        assertEquals(BounceAlert.Unit.APPS, posted.single().unit)
    }

    @Test
    fun `a web kick with no known browser has nothing to attribute and is dropped`() {
        val c = checkIn()
        c.onKicked(WebSessionKey.PREFIX + "example.com", browserPackage = null)
        assertFalse(c.isTracking)
    }

    /** [bounce] with the wall reported by [wall] instead of a direct `onWall`. */
    private fun bounceWith(c: BounceCheckIn, wall: () -> Unit) {
        c.onForegroundSignal(ForegroundSignal.AppWindow(instagram))
        wall()
        c.onForegroundSignal(ForegroundSignal.OwnUi(ownPackage))
        c.onForegroundSignal(ForegroundSignal.Home("com.google.android.apps.nexuslauncher"))
        others.forEach { pkg ->
            now += 20_000L
            c.onForegroundSignal(ForegroundSignal.AppWindow(pkg))
        }
        now += 20_000L
        c.onForegroundSignal(ForegroundSignal.AppWindow(instagram))
        wall()
    }

    private object NoOpCounterOverlay : CounterOverlayManagerApi {
        override fun isVisible() = false
        override fun show(label: String) = Unit
        override fun updateCount(sessionCount: Int, dailyTotal: Int) = Unit
        override fun updateLabel(label: String) = Unit
        override fun hide() = Unit
    }
}
