package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The once-per-arrival policy in [FollowingSteer], driven as ordinary JVM sequences.
 *
 * The clock is a parameter on every call, so the timeout case is an assertion rather than a sleep and
 * the whole suite runs in milliseconds. Each test below is one of the policy lines in the class KDoc;
 * the ones that matter are the four "does NOT re-steer" cases, because this is the first feature that
 * makes Nudge act *inside* another app and a retry loop tapping at a host app's chrome is the worst
 * available failure.
 */
class FollowingSteerTest {

    private val timeout = 1_500L

    private fun steer() = FollowingSteer(menuTimeoutMs = timeout)

    /** Shorthand: in the host app, menu not visible. */
    private fun FollowingSteer.at(surface: HostSurface, nowMs: Long, menuVisible: Boolean = false) =
        onObservation(surface = surface, inHostApp = true, menuVisible = menuVisible, nowMs = nowMs)

    // -----------------------------------------------------------------------------------------
    // The happy path
    // -----------------------------------------------------------------------------------------

    @Test
    fun `arriving at the home feed opens the menu`() {
        assertEquals(SteerAction.OpenMenu, steer().at(HostSurface.HOME_FEED, 0))
    }

    @Test
    fun `the menu appearing clicks following`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        assertEquals(SteerAction.ClickFollowing, s.at(HostSurface.HOME_FEED, 200, menuVisible = true))
    }

    @Test
    fun `the full sequence steers exactly once`() {
        val s = steer()
        val actions = listOf(
            s.at(HostSurface.HOME_FEED, 0),
            s.at(HostSurface.HOME_FEED, 200, menuVisible = true),
            s.at(HostSurface.FOLLOWING_FEED, 400),
            s.at(HostSurface.FOLLOWING_FEED, 600)
        )
        assertEquals(
            listOf(SteerAction.OpenMenu, SteerAction.ClickFollowing, SteerAction.None, SteerAction.None),
            actions
        )
    }

    /** While the attempt is in flight and the menu has not appeared yet, do nothing — do not re-click. */
    @Test
    fun `a pending attempt does not re-click the entry point`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, 100))
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, 500))
        assertTrue(s.isMenuPending)
    }

    // -----------------------------------------------------------------------------------------
    // The four "does not re-steer" cases
    // -----------------------------------------------------------------------------------------

    /** Scrolling the feed fires observation after observation. None of them may steer again. */
    @Test
    fun `no re-steer while scrolling the home feed after the attempt`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        assertEquals(SteerAction.ClickFollowing, s.at(HostSurface.HOME_FEED, 100, menuVisible = true))
        assertEquals(SteerAction.None, s.at(HostSurface.FOLLOWING_FEED, 200))
        (300L..2_000L step 100L).forEach { t ->
            assertEquals("scrolling at t=$t must not re-steer", SteerAction.None, s.at(HostSurface.HOME_FEED, t))
        }
    }

    /**
     * **The policy's central case.** A user who backs out of Following to Home did it on purpose and
     * must not be dragged back. Observing the Following feed SETS `attempted` rather than clearing it,
     * which is what makes the back-out indistinguishable from a steered arrival — deliberately.
     */
    @Test
    fun `no re-steer after backing out of following to home`() {
        val s = steer()
        s.at(HostSurface.HOME_FEED, 0)
        s.at(HostSurface.HOME_FEED, 100, menuVisible = true)
        s.at(HostSurface.FOLLOWING_FEED, 200)
        assertTrue("observing Following must mark the arrival attempted", s.hasAttempted)
        assertEquals(
            "the user backed out on purpose; steering again would fight them",
            SteerAction.None,
            s.at(HostSurface.HOME_FEED, 300)
        )
    }

    /** Reaching Following without any steer at all still spends the arrival. */
    @Test
    fun `a user who navigates to following themselves is not steered afterwards`() {
        val s = steer()
        assertEquals(SteerAction.None, s.at(HostSurface.FOLLOWING_FEED, 0))
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, 100))
    }

    /** The reel player, a story, a DM thread: no information, so nothing changes in either direction. */
    @Test
    fun `an unknown surface changes nothing`() {
        val s = steer()
        s.at(HostSurface.HOME_FEED, 0)
        s.at(HostSurface.HOME_FEED, 100, menuVisible = true)
        s.at(HostSurface.FOLLOWING_FEED, 200)

        assertEquals(SteerAction.None, s.at(HostSurface.UNKNOWN, 300))
        assertTrue("UNKNOWN must not clear the memory", s.hasAttempted)
        assertEquals(
            "returning from a reel must not look like a fresh arrival",
            SteerAction.None,
            s.at(HostSurface.HOME_FEED, 400)
        )
    }

    /**
     * **A pending attempt is resolved whatever the surface classifies as, UNKNOWN included.**
     *
     * This is the case that decides the shape of `onObservation`, and the `logo-menu` fixture is the
     * evidence: the real dump taken with the dropdown open contains no `title_logo`, no `tab_bar` and
     * no `action_bar_title`, so if the service is holding the popup window then the moment we are
     * waiting for classifies as UNKNOWN. Resolving the pending attempt inside the `HOME_FEED` branch
     * would make `ClickFollowing` unreachable exactly then, and Nudge would open Instagram's dropdown
     * and leave it hanging open over the user's feed — worse than not steering at all.
     *
     * `InstagramSurfacesFixtureTest` asserts the UNKNOWN half of that premise against the real dump.
     */
    @Test
    fun `a pending attempt is clicked through even when the surface reads as unknown`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        assertEquals(
            "the open dropdown may be the only window we can see; the click must still land",
            SteerAction.ClickFollowing,
            s.at(HostSurface.UNKNOWN, 200, menuVisible = true)
        )
        assertFalse(s.isMenuPending)
    }

    /**
     * The other side of resolving above the switch: an unrelated screen CAN age out a pending attempt.
     *
     * Accepted cost, bounded by [menuTimeoutMs] and at most one abandoned attempt on this arrival —
     * and much cheaper than the alternative, which is a dropdown left open on someone's feed.
     */
    @Test
    fun `an unknown surface can age out a pending attempt and does not retry`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        assertEquals(SteerAction.None, s.at(HostSurface.UNKNOWN, timeout + 1))
        assertFalse("the attempt aged out", s.isMenuPending)
        assertTrue("giving up must not clear `attempted`", s.hasAttempted)
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, timeout + 100))
    }

    /** Inside the window, an unrelated screen leaves the pending attempt alone. */
    @Test
    fun `an unknown surface inside the window leaves the attempt pending`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        assertEquals(SteerAction.None, s.at(HostSurface.UNKNOWN, timeout - 1))
        assertTrue(s.isMenuPending)
        assertEquals(SteerAction.ClickFollowing, s.at(HostSurface.HOME_FEED, timeout, menuVisible = true))
    }

    /**
     * A pending attempt outranks OTHER_TAB's reset, because it is resolved above the switch. The reset
     * still happens on the next observation once the attempt is settled, so the arrival is not stuck.
     */
    @Test
    fun `another tab does not reset while an attempt is still pending`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        assertEquals(SteerAction.None, s.at(HostSurface.OTHER_TAB, 100))
        assertTrue("the pending attempt is resolved before the surface is consulted", s.hasAttempted)

        // Once the attempt has settled, the next OTHER_TAB observation resets normally.
        s.at(HostSurface.HOME_FEED, timeout + 1)
        assertEquals(SteerAction.None, s.at(HostSurface.OTHER_TAB, timeout + 2))
        assertFalse(s.hasAttempted)
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, timeout + 3))
    }

    // -----------------------------------------------------------------------------------------
    // The two resets
    // -----------------------------------------------------------------------------------------

    @Test
    fun `re-steer after visiting another tab`() {
        val s = steer()
        s.at(HostSurface.HOME_FEED, 0)
        s.at(HostSurface.HOME_FEED, 100, menuVisible = true)
        s.at(HostSurface.FOLLOWING_FEED, 200)

        assertEquals(SteerAction.None, s.at(HostSurface.OTHER_TAB, 300))
        assertFalse("another tab is a real navigation away; the arrival is over", s.hasAttempted)
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 400))
    }

    @Test
    fun `re-steer after leaving the app`() {
        val s = steer()
        s.at(HostSurface.HOME_FEED, 0)
        s.at(HostSurface.HOME_FEED, 100, menuVisible = true)
        s.at(HostSurface.FOLLOWING_FEED, 200)

        assertEquals(
            SteerAction.None,
            s.onObservation(HostSurface.HOME_FEED, inHostApp = false, menuVisible = false, nowMs = 300)
        )
        assertFalse(s.hasAttempted)
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 400))
    }

    /** Leaving the app also abandons an in-flight attempt; the next arrival starts clean. */
    @Test
    fun `leaving the app clears a pending menu`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        s.onObservation(HostSurface.UNKNOWN, inHostApp = false, menuVisible = false, nowMs = 100)
        assertFalse(s.isMenuPending)
        assertFalse(s.hasAttempted)
        // A stale menu that happens to be visible on the next arrival must not be clicked before the
        // entry point has been pressed for THIS arrival.
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 200, menuVisible = true))
    }

    /** `inHostApp = false` wins over every surface value, including FOLLOWING_FEED. */
    @Test
    fun `leaving the app resets regardless of the surface reported`() {
        HostSurface.entries.forEach { surface ->
            val s = steer()
            s.at(HostSurface.HOME_FEED, 0)
            assertEquals(
                SteerAction.None,
                s.onObservation(surface, inHostApp = false, menuVisible = true, nowMs = 100)
            )
            assertFalse("$surface with inHostApp=false must reset", s.hasAttempted)
        }
    }

    /**
     * The explicit reset the service calls when the master toggle goes OFF, or when the service is
     * torn down. A globally-disabled Nudge must behave as if uninstalled, so the "already steered this
     * visit" memory is about a visit that no longer exists.
     */
    @Test
    fun `an explicit reset re-arms the steer`() {
        val s = steer()
        s.at(HostSurface.HOME_FEED, 0)
        s.at(HostSurface.HOME_FEED, 100, menuVisible = true)
        s.at(HostSurface.FOLLOWING_FEED, 200)
        assertTrue(s.hasAttempted)

        s.reset()
        assertFalse(s.hasAttempted)
        assertFalse(s.isMenuPending)
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 300))
    }

    /** Reset also abandons an attempt still in flight, so a stale menu is never clicked. */
    @Test
    fun `an explicit reset clears a pending menu`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        assertTrue(s.isMenuPending)
        s.reset()
        assertFalse(s.isMenuPending)
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 100, menuVisible = true))
    }

    /** Resetting a machine that has done nothing is a no-op, not an error. */
    @Test
    fun `resetting a fresh steer changes nothing`() {
        val s = steer()
        s.reset()
        assertFalse(s.hasAttempted)
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
    }

    // -----------------------------------------------------------------------------------------
    // The timeout
    // -----------------------------------------------------------------------------------------

    /**
     * The menu never appeared. Give up silently — and `attempted` STAYS true, so the give-up is final
     * for this arrival. One attempt, never a retry.
     */
    @Test
    fun `the menu timeout gives up and does not retry`() {
        val s = steer()
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, 0))
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, timeout + 1))
        assertFalse("the attempt is no longer pending", s.isMenuPending)
        assertTrue("giving up must not clear `attempted` — that would be a retry", s.hasAttempted)
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, timeout + 100))
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, timeout + 10_000))
    }

    /** Exactly at the timeout is still pending; the policy is strictly greater than. */
    @Test
    fun `the attempt is still pending exactly at the timeout`() {
        val s = steer()
        s.at(HostSurface.HOME_FEED, 0)
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, timeout))
        assertTrue(s.isMenuPending)
        assertEquals(SteerAction.ClickFollowing, s.at(HostSurface.HOME_FEED, timeout, menuVisible = true))
    }

    /** A menu that appears late, but inside the window, is still clicked. */
    @Test
    fun `a late but in-window menu is still clicked`() {
        val s = steer()
        s.at(HostSurface.HOME_FEED, 0)
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, timeout - 1))
        assertEquals(SteerAction.ClickFollowing, s.at(HostSurface.HOME_FEED, timeout - 1, menuVisible = true))
    }

    /** After a timed-out attempt, another tab still re-arms it: the resets are the only way back. */
    @Test
    fun `another tab re-arms the steer after a timeout`() {
        val s = steer()
        s.at(HostSurface.HOME_FEED, 0)
        s.at(HostSurface.HOME_FEED, timeout + 1)
        s.at(HostSurface.OTHER_TAB, timeout + 100)
        assertEquals(SteerAction.OpenMenu, s.at(HostSurface.HOME_FEED, timeout + 200))
    }

    /** The default timeout is the agreed 1.5s bounded wait. */
    @Test
    fun `the default menu timeout is one and a half seconds`() {
        val s = FollowingSteer()
        s.at(HostSurface.HOME_FEED, 0)
        assertEquals(SteerAction.None, s.at(HostSurface.HOME_FEED, 1_500))
        assertTrue("1500ms must still be inside the window", s.isMenuPending)
        s.at(HostSurface.HOME_FEED, 1_501)
        assertFalse("1501ms must be outside it", s.isMenuPending)
    }

    // -----------------------------------------------------------------------------------------
    // Structural
    // -----------------------------------------------------------------------------------------

    /** A menu visible with no attempt in flight is somebody else's menu. Never click it. */
    @Test
    fun `a visible menu with no attempt in flight opens our own attempt first`() {
        assertEquals(
            SteerAction.OpenMenu,
            steer().at(HostSurface.HOME_FEED, 0, menuVisible = true)
        )
    }

    /** Every `HostSurface` value is exercised, derived from the enum rather than hand-listed. */
    @Test
    fun `every host surface is decided`() {
        val actions = HostSurface.entries.associateWith { surface ->
            steer().at(surface, 0)
        }
        assertEquals(
            mapOf(
                HostSurface.HOME_FEED to SteerAction.OpenMenu,
                HostSurface.FOLLOWING_FEED to SteerAction.None,
                HostSurface.OTHER_TAB to SteerAction.None,
                HostSurface.UNKNOWN to SteerAction.None
            ),
            actions
        )
    }
}
