package com.astraedus.nudge.domain.bounce

import com.astraedus.nudge.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BounceDetector], the "Bro. wtf." check-in's state machine.
 *
 * Every rule here is a rule about TIME and SEQUENCE (a window that slides, a cooldown, a wall that
 * only counts once the user has left), none of which a device session can pin down without a
 * stopwatch and a lot of patience. So the thresholds are read off [BounceDetector]'s own constants
 * rather than retyped, and time is just a number we move.
 */
class BounceDetectorTest {

    private val window = BounceDetector.WINDOW_MS
    private val cooldown = BounceDetector.COOLDOWN_MS
    private val minApps = BounceDetector.MIN_APPS
    private val minWalls = BounceDetector.MIN_WALLS

    private var now = 1_000_000L

    private fun detector(excluded: Set<String> = emptySet()) =
        BounceDetector(excludedPackages = excluded)

    /** Distinct, obviously-real app packages, as many as a test needs. */
    private fun app(i: Int) = "com.example.app$i"

    private val instagram = "com.instagram.android"

    /**
     * The owner's own sequence, at exactly the thresholds: wall on Instagram, then enough other
     * apps to reach [minApps] counting Instagram, then back into Instagram for the second wall.
     * Returns whatever the LAST call returned.
     */
    private fun bounceToThreshold(d: BounceDetector, stepMs: Long = 10_000L): BounceAlert? {
        assertNull(d.onWall(instagram, now))
        repeat(minApps - 1) { i ->
            now += stepMs
            assertNull("app ${i + 1} must not fire yet", d.onAppOpened(app(i), now))
        }
        now += stepMs
        assertNull(d.onAppOpened(instagram, now))
        return d.onWall(instagram, now)
    }

    @Test
    fun `the thresholds are the ones the feature was specified with`() {
        // A guard on the spec, not on arithmetic: change these and the copy in the docs, the
        // CHANGELOG and the store notes is wrong.
        assertEquals(5 * 60_000L, window)
        assertEquals(2, minWalls)
        assertEquals(4, minApps)
        assertEquals(30 * 60_000L, cooldown)
    }

    // ── no fire ──

    @Test
    fun `a single wall never fires, however many apps follow`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        repeat(20) { i ->
            now += 1_000L
            assertNull(d.onAppOpened(app(i), now))
        }
        assertTrue("still tracking, just not firing", d.isArmed)
    }

    @Test
    fun `apps opened before any wall are not tracked at all`() {
        val d = detector()
        repeat(10) { i -> assertNull(d.onAppOpened(app(i), now)) }
        assertFalse(d.isArmed)
        // ...and do not leak into the streak a later wall starts.
        assertNull(d.onWall(instagram, now))
        now += 1_000L
        assertNull(d.onWall(app(0), now))
        assertTrue("two walls, but only two apps since arming", d.isArmed)
    }

    @Test
    fun `two walls with too few distinct apps does not fire`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        now += 1_000L
        assertNull(d.onAppOpened(app(1), now))
        now += 1_000L
        assertNull(d.onAppOpened(instagram, now))
        assertNull("2 walls, 2 apps: under the app threshold", d.onWall(instagram, now))
    }

    // ── fire ──

    @Test
    fun `fires at exactly the threshold with real numbers`() {
        val d = detector()
        val alert = bounceToThreshold(d, stepMs = 10_000L)
        assertNotNull(alert)
        assertEquals(minApps, alert!!.appCount)
        // minApps steps of 10s = 40s from the first wall, rounded UP to a whole minute.
        assertEquals(1, alert.minutes)
        assertFalse("firing ends the streak", d.isArmed)
    }

    @Test
    fun `fires on the app that crosses the threshold when the second wall came first`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        now += 1_000L
        assertNull(d.onAppOpened(app(1), now))
        now += 1_000L
        assertNull("a different app walled: that is a second wall", d.onWall(app(1), now))
        now += 1_000L
        assertNull(d.onAppOpened(app(2), now))
        now += 1_000L
        val alert = d.onAppOpened(app(3), now)
        assertNotNull("the 4th distinct app crosses the threshold", alert)
        assertEquals(4, alert!!.appCount)
    }

    @Test
    fun `the minutes are measured from the first wall, rounded up`() {
        val d = detector()
        val alert = bounceToThreshold(d, stepMs = 61_000L) // 4 x 61s = 244s -> 5 minutes
        assertEquals(5, alert!!.minutes)
    }

    // ── what a wall is ──

    @Test
    fun `the same overlay re-launched for an app the user never left is ONE wall`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        // Issue #36's re-launch mechanisms: the overlay goes up again with the user still there.
        repeat(5) {
            now += 1_000L
            assertNull(d.onAppOpened(instagram, now))
            assertNull(d.onWall(instagram, now))
        }
        // Then the apps arrive. Without a real second wall it must not fire.
        repeat(minApps) { i ->
            now += 1_000L
            assertNull("re-launches are not bounces (app $i)", d.onAppOpened(app(i), now))
        }
    }

    /**
     * Isolated with a one-app threshold, so the ONLY thing that can make the second wall count is
     * the trip home (no other app is ever opened). The counterfactual runs on the same config.
     */
    @Test
    fun `going home and straight back to the same wall counts as a second wall`() {
        val wallsOnly = BounceDetector.Config(minApps = 1)

        val withHome = BounceDetector(wallsOnly)
        assertNull(withHome.onWall(instagram, now))
        now += 1_000L
        withHome.onWentHome(now)
        assertNotNull("left via Home, came straight back: a new wall", withHome.onWall(instagram, now))

        val withoutHome = BounceDetector(wallsOnly)
        assertNull(withoutHome.onWall(instagram, now))
        now += 1_000L
        assertNull("never left: the same wall again", withoutHome.onWall(instagram, now))
    }

    @Test
    fun `home adds nothing to the app count`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        repeat(10) {
            now += 1_000L
            d.onWentHome(now)
        }
        now += 1_000L
        assertNull("2 walls, 1 app", d.onWall(instagram, now))
    }

    // ── what an app is ──

    @Test
    fun `the same app twice counts once`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        repeat(10) {
            now += 1_000L
            assertNull(d.onAppOpened(app(1), now))
            assertNull(d.onAppOpened(app(2), now))
        }
        now += 1_000L
        assertNull("3 distinct apps (instagram, app1, app2) is under the threshold", d.onWall(instagram, now))
    }

    @Test
    fun `excluded packages never count, and never count as leaving`() {
        val excluded = BounceDetector.neverCountedPackages(BuildConfig.APPLICATION_ID)
        val d = detector(excluded)
        assertNull(d.onWall(instagram, now))
        excluded.forEach { pkg ->
            now += 1_000L
            assertNull(d.onAppOpened(pkg, now))
        }
        now += 1_000L
        assertNull(
            "a permission dialog over Instagram is not leaving Instagram, so this is the SAME wall",
            d.onWall(instagram, now)
        )
        repeat(minApps - 2) { i ->
            now += 1_000L
            assertNull(d.onAppOpened(app(i), now))
        }
        // instagram + (minApps - 2) others = minApps - 1 real apps, 1 wall: nothing.
        now += 1_000L
        assertNull(d.onAppOpened(instagram, now))
        assertNull("still one app short", d.onWall(instagram, now))
    }

    @Test
    fun `the default exclusions name Nudge itself by the real application id`() {
        val excluded = BounceDetector.neverCountedPackages(BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.APPLICATION_ID in excluded)
        assertTrue("com.google.android.permissioncontroller" in excluded)
        assertTrue("com.android.permissioncontroller" in excluded)
        assertTrue("com.android.systemui" in excluded)
    }

    // ── the window ──

    @Test
    fun `a streak lapses when the window passes with no new wall, and holds no state after`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        repeat(minApps - 1) { i ->
            now += 1_000L
            assertNull(d.onAppOpened(app(i), now))
        }
        now += window + 1
        assertNull("lapsed: the app is ignored", d.onAppOpened(app(99), now))
        assertFalse("disarmed", d.isArmed)
        // A wall now is the FIRST wall of a new streak, so it cannot fire on the old apps.
        assertNull(d.onWall(instagram, now))
        assertTrue(d.isArmed)
    }

    @Test
    fun `a wall exactly at the window edge is still inside it`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        repeat(minApps - 1) { i -> assertNull(d.onAppOpened(app(i), now)) }
        now += window
        assertNull(d.onAppOpened(instagram, now))
        assertNotNull("age == window is inside", d.onWall(instagram, now))
    }

    @Test
    fun `the window slides from the most recent wall`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        // A wall every 4 minutes keeps the streak alive well past 5 minutes from the first.
        now += 4 * 60_000L
        assertNull(d.onAppOpened(app(1), now))
        assertNull(d.onWall(app(1), now))
        now += 4 * 60_000L
        assertNull(d.onAppOpened(app(2), now))
        // 8 minutes after the first wall, 4 after the last: still armed.
        assertTrue(d.isArmed)
        now += 1_000L
        val alert = d.onAppOpened(app(3), now)
        assertNotNull(alert)
        assertEquals(4, alert!!.appCount)
        assertEquals("measured from the first wall", 9, alert.minutes)
    }

    @Test
    fun `a backwards clock lapses the streak rather than pinning it open`() {
        val d = detector()
        assertNull(d.onWall(instagram, now))
        assertNull(d.onAppOpened(app(1), now - 1))
        assertFalse(d.isArmed)
    }

    // ── the cooldown ──

    @Test
    fun `after firing, a second bounce inside the cooldown is silent and tracks nothing`() {
        val d = detector()
        assertNotNull(bounceToThreshold(d))
        now += 1_000L
        assertNull(bounceToThreshold(d))
        assertFalse("nothing is tracked while cooling down", d.isArmed)
    }

    @Test
    fun `after the cooldown the detector starts fresh and can fire again`() {
        val d = detector()
        assertNotNull(bounceToThreshold(d))
        val firedAt = now
        now = firedAt + cooldown
        val again = bounceToThreshold(d)
        assertNotNull("the cooldown has passed: a fresh streak may fire", again)
        assertEquals(minApps, again!!.appCount)
    }

    @Test
    fun `reset clears a streak and a cooldown`() {
        val d = detector()
        assertNotNull(bounceToThreshold(d))
        d.reset()
        now += 1_000L
        assertNotNull("no cooldown after a reset", bounceToThreshold(d))

        d.reset()
        assertNull(d.onWall(instagram, now))
        d.reset()
        assertFalse(d.isArmed)
    }

    // ── bounds ──

    @Test
    fun `the tracked set is bounded and N never exceeds the bound`() {
        val d = BounceDetector(BounceDetector.Config(maxTrackedApps = 5))
        assertNull(d.onWall(instagram, now))
        // One wall only, so nothing fires while 100 apps go by; the set stops growing at 5.
        repeat(100) { i -> d.onAppOpened(app(i), now) }
        now += 1_000L
        d.onAppOpened(app(200), now)
        val alert = d.onWall(app(200), now)
        assertNotNull(alert)
        assertEquals(5, alert!!.appCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a config that can never fire is rejected`() {
        BounceDetector.Config(minApps = 10, maxTrackedApps = 5)
    }

    // ── copy ──

    @Test
    fun `the copy carries the real numbers and the owner's title`() {
        val alert = BounceAlert(appCount = 6, minutes = 4)
        assertEquals("Bro. wtf.", alert.title)
        assertEquals("You've bounced through 6 apps in 4 minutes. Wanna take a break?", alert.body)
    }

    @Test
    fun `one minute is singular`() {
        assertEquals(
            "You've bounced through 4 apps in 1 minute. Wanna take a break?",
            BounceAlert(appCount = 4, minutes = 1).body
        )
    }

    @Test
    fun `minutes round up and never read zero`() {
        assertEquals(1, BounceAlert.minutesFor(0L))
        assertEquals(1, BounceAlert.minutesFor(1L))
        assertEquals(1, BounceAlert.minutesFor(60_000L))
        assertEquals(2, BounceAlert.minutesFor(60_001L))
        assertEquals(5, BounceAlert.minutesFor(5 * 60_000L))
        assertEquals(1, BounceAlert.minutesFor(-5L))
    }
}
