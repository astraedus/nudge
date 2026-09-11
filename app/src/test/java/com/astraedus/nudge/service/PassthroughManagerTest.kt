package com.astraedus.nudge.service

import com.astraedus.nudge.domain.sitting.SittingEvent
import com.astraedus.nudge.domain.sitting.SittingTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Test

class PassthroughManagerTest {

    private lateinit var manager: PassthroughManager

    @Before
    fun setUp() {
        manager = PassthroughManager()
    }

    @Test
    fun `grant sets package and feature`() {
        manager.grant("com.example.app", "REELS")

        assertEquals("com.example.app", manager.lastPackage)
        assertEquals("REELS", manager.lastFeature)
        assertTrue(manager.lastTime > 0L)
    }

    @Test
    fun `grant without feature sets null feature`() {
        manager.grant("com.example.app")

        assertEquals("com.example.app", manager.lastPackage)
        assertNull(manager.lastFeature)
    }

    @Test
    fun `isGranted returns true for matching package`() {
        manager.grant("com.example.alpha")

        assertTrue(manager.isGranted("com.example.alpha"))
    }

    @Test
    fun `isGranted returns false for different package`() {
        manager.grant("com.example.alpha")

        assertFalse(manager.isGranted("com.example.beta"))
    }

    @Test
    fun `isGranted returns false when no grant active`() {
        assertFalse(manager.isGranted("com.example.alpha"))
    }

    @Test
    fun `shouldSkipForegroundEvaluation delegates to isGranted`() {
        manager.grant("com.example.alpha")

        assertTrue(manager.shouldSkipForegroundEvaluation("com.example.alpha"))
        assertFalse(manager.shouldSkipForegroundEvaluation("com.example.beta"))
    }

    @Test
    fun `shouldSkipFeatureEvaluation requires both package and feature match`() {
        manager.grant("com.example.alpha", "REELS")

        assertTrue(manager.shouldSkipFeatureEvaluation("com.example.alpha", "REELS"))
        assertFalse(manager.shouldSkipFeatureEvaluation("com.example.alpha", "EXPLORE"))
        assertFalse(manager.shouldSkipFeatureEvaluation("com.example.beta", "REELS"))
    }

    @Test
    fun `shouldSkipFeatureEvaluation returns false when granted without feature`() {
        manager.grant("com.example.alpha")

        assertFalse(manager.shouldSkipFeatureEvaluation("com.example.alpha", "REELS"))
    }

    /**
     * These three replace the tests of `clearIfAppChanged`, which issue #28 deleted. The claims are
     * unchanged -- a real app switch drops everything, staying put drops nothing, and clearing when
     * there is nothing to clear is a no-op -- but the trigger moved from "a foreign package fired an
     * event" to "another app held the foreground past the return window", which is the fix.
     */
    @Test
    fun `another app held past the return window clears everything`() {
        manager.grant("com.example.alpha", "REELS")

        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.example.beta"), 0)
        manager.onForegroundSignal(
            ForegroundSignal.AppWindow("com.example.beta"),
            SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS
        )

        assertNull(manager.lastPackage)
        assertNull(manager.lastFeature)
        assertEquals(0L, manager.lastTime)
    }

    @Test
    fun `a brief excursion into another app clears nothing`() {
        manager.grant("com.example.alpha", "REELS")

        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.example.beta"), 0)
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.example.alpha"), 5_000)

        assertEquals("com.example.alpha", manager.lastPackage)
        assertEquals("REELS", manager.lastFeature)
    }

    @Test
    fun `staying in the granted app clears nothing`() {
        manager.grant("com.example.alpha", "REELS")

        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.example.alpha"), 1_000)

        assertEquals("com.example.alpha", manager.lastPackage)
        assertEquals("REELS", manager.lastFeature)
    }

    @Test
    fun `ending a sitting that granted nothing is a no-op`() {
        manager.onForegroundSignal(ForegroundSignal.Home("com.launcher"), 0)

        assertNull(manager.lastPackage)
    }

    @Test
    fun `clear resets all state`() {
        manager.grant("com.example.alpha", "SHORTS")

        manager.clear()

        assertNull(manager.lastPackage)
        assertNull(manager.lastFeature)
        assertEquals(0L, manager.lastTime)
        assertFalse(manager.isGranted("com.example.alpha"))
    }

    // --- The web axis ---
    //
    // A completed website block grants TWO things at once: the browser is the app the user is in,
    // and the domain is the site they earned entry to. They are granted and cleared together, which
    // is why they live in one manager rather than in a stray field on the accessibility service.

    @Test
    fun `a web grant records the browser and the domain together`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        assertEquals("com.android.chrome", manager.lastPackage)
        assertEquals("instagram.com", manager.lastDomain)
    }

    /**
     * An app block must never leave a domain behind. Before the two axes shared a manager, the
     * domain lived on the service and nothing about an app-level grant touched it.
     */
    @Test
    fun `an app grant does not inherit a stale domain`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        manager.grant("com.example.alpha")

        assertNull(manager.lastDomain)
    }

    @Test
    fun `clearWebGrant drops the domain and leaves the app grant alone`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        manager.clearWebGrant()

        assertNull(manager.lastDomain)
        assertEquals("com.android.chrome", manager.lastPackage)
        assertTrue(manager.isGranted("com.android.chrome"))
    }

    @Test
    fun `leaving the browser clears the domain with everything else`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        manager.onForegroundSignal(ForegroundSignal.Home("com.launcher"), 1_000)

        assertNull(manager.lastDomain)
        assertNull(manager.lastPackage)
    }

    @Test
    fun `clear resets the domain too`() {
        manager.grant("com.android.chrome", webDomain = "instagram.com")

        manager.clear()

        assertNull(manager.lastDomain)
    }
    // --- every sitting change is announced, wherever it came from (round-2 review) ---

    /**
     * A grant is earned in `BlockOverlayActivity`, not in the service, and `grant()` used to discard
     * the `SittingEvent` it produced. So completing app B's delay while the sitting still belonged
     * to app A moved the sitting silently: `InteractionCounter` kept A's scroll sources, A's primary
     * source blocked B's counter for the whole handover window, and A's caption sat over B's count.
     *
     * The fix is structural rather than a call added at one site: there is no longer a way to move
     * the sitting that does not notify.
     */
    @Test
    fun `earning a grant announces the sitting move`() {
        val seen = mutableListOf<SittingEvent>()
        manager.setSittingReaction { seen += it }
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.app.a"), 0)
        seen.clear()

        manager.grant("com.app.b")

        assertEquals(1, seen.size)
        val started = seen.single()
        assertTrue("a grant for a different app starts a new sitting", started is SittingEvent.Started)
        assertEquals("com.app.b", (started as SittingEvent.Started).packageName)
        assertEquals("com.app.a", started.ended?.packageName)
    }

    /** ...and the grant it just handed out must survive being announced. */
    @Test
    fun `announcing a grant does not revoke it`() {
        manager.setSittingReaction { }
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.app.a"), 0)

        manager.grant("com.app.b")

        assertTrue(manager.isGranted("com.app.b"))
    }

    @Test
    fun `a revocation is announced too`() {
        val seen = mutableListOf<SittingEvent>()
        manager.grant("com.app.a")
        manager.setSittingReaction { seen += it }

        manager.onForegroundSignal(ForegroundSignal.Home("com.launcher"), 1_000)

        assertEquals(1, seen.size)
        assertTrue(seen.single() is SittingEvent.Ended)
        assertFalse(manager.isGranted("com.app.a"))
    }

    /** Clearing the reaction must actually stop it firing -- the service does this on destroy. */
    @Test
    fun `clearing the reaction stops the announcements`() {
        var count = 0
        manager.setSittingReaction { count++ }
        manager.setSittingReaction(null)

        manager.grant("com.app.b")
        manager.onForegroundSignal(ForegroundSignal.Home("com.launcher"), 1_000)

        assertEquals(0, count)
    }

    // --- a service rebind is not the user leaving (device QA 2026-09-12) ---

    /**
     * FAIL 1. `onServiceConnected` used to call `resetSitting()`, which drops the grant, on the
     * reasoning that a bind starts observation. `docs/BACKLOG.md` records this service churning and
     * reconnecting under memory pressure, so that turned every rebind into a re-block of a user who
     * never left -- issue #28's own defect, reintroduced by its own fix.
     */
    @Test
    fun `a rebind keeps the grant and the sitting`() {
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.app.a"), 0)
        manager.grant("com.app.a")

        manager.onObservationResumed()

        assertTrue("the user did not go anywhere; a rebind is not evidence", manager.isGranted("com.app.a"))
        assertTrue(manager.shouldSkipForegroundEvaluation("com.app.a"))
    }

    /**
     * What a rebind DOES invalidate: the away clock, which was timing an interval whose end nobody
     * observed. After a rebind the clock restarts from the next signal rather than counting the gap.
     */
    @Test
    fun `a rebind discards the away clock so the gap is not counted as time away`() {
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.app.a"), 0)
        manager.grant("com.app.a")
        // The user steps into another app, and THEN the service rebinds.
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.other"), 1_000)

        manager.onObservationResumed()

        // Had the clock survived, this would be past the window and would revoke.
        manager.onForegroundSignal(
            ForegroundSignal.AppWindow("com.other"),
            1_000 + SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS
        )
        assertTrue(
            "the gap must not be counted as time away -- the clock restarts from the next signal",
            manager.isGranted("com.app.a")
        )
    }

    /** ...and a genuine absence AFTER the rebind still ends the sitting on its own terms. */
    @Test
    fun `a real app switch after a rebind still revokes`() {
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.app.a"), 0)
        manager.grant("com.app.a")
        manager.onObservationResumed()

        val t = 10_000L
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.other"), t)
        manager.onForegroundSignal(
            ForegroundSignal.AppWindow("com.other"),
            t + SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS
        )

        assertFalse(manager.isGranted("com.app.a"))
    }

}
