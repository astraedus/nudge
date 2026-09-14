package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.block.BlockLaunchGate.Decision
import com.astraedus.nudge.domain.block.BlockLaunchGate.WalkAway
import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Every branch of the launch gate, and the combinations that matter.
 *
 * The two issues this gate closes have opposite shapes, which is why the combination cases below
 * are the interesting ones rather than padding:
 *
 *  - [#31](https://github.com/astraedus/nudge/issues/31): the decision is stale because the user
 *    moved. The foreground check catches it and the walk-away window is irrelevant.
 *  - [#26](https://github.com/astraedus/nudge/issues/26): the decision is stale because the user is
 *    LEAVING, and at the instant it lands the blocked app genuinely is in front. The foreground
 *    check is powerless; only the walk-away window can see it.
 */
class BlockLaunchGateTest {

    private val blocked = "com.instagram.android"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val nudge = "dev.astraedus.nudge"
    private val other = "com.google.android.keep"

    // --- the foreground condition (issue #31) ---------------------------------------------------

    @Test
    fun `a decision for the app that is still in front launches`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, foreground = blocked, walkAway = null, nowMs = 1_000)
        )
    }

    @Test
    fun `a decision that lands after the user went home is dropped`() {
        assertEquals(
            Decision.DROP_FOREGROUND_MOVED,
            BlockLaunchGate.decide(blocked, foreground = launcher, walkAway = null, nowMs = 1_000)
        )
    }

    /** The most visible form of the report: an overlay landing on top of Nudge's own screens. */
    @Test
    fun `a decision that lands after the user opened Nudge is dropped`() {
        assertEquals(
            Decision.DROP_FOREGROUND_MOVED,
            BlockLaunchGate.decide(blocked, foreground = nudge, walkAway = null, nowMs = 1_000)
        )
    }

    @Test
    fun `a decision that lands after the user switched to another app is dropped`() {
        assertEquals(
            Decision.DROP_FOREGROUND_MOVED,
            BlockLaunchGate.decide(blocked, foreground = other, walkAway = null, nowMs = 1_000)
        )
    }

    /**
     * "We have observed nothing" is not "the user is elsewhere". The gate may only ever WEAKEN
     * enforcement on positive evidence, the same failure direction the launcher set, the active
     * window read and the URL-bar read all already take.
     */
    @Test
    fun `an unknown foreground never suppresses a block`() {
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, foreground = null, walkAway = null, nowMs = 1_000)
        )
    }

    /**
     * A web block is attributed to the rule's app and targeted at the BROWSER. Passing the
     * attributed package as the target would compare Instagram against Chrome and drop every web
     * block ever written.
     */
    @Test
    fun `a web block targets the browser the user is sitting in`() {
        val chrome = "com.android.chrome"
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(chrome, foreground = chrome, walkAway = null, nowMs = 1_000)
        )
        assertEquals(
            "and the rule's app is never in front when its website is blocked in a browser",
            Decision.DROP_FOREGROUND_MOVED,
            BlockLaunchGate.decide(blocked, foreground = chrome, walkAway = null, nowMs = 1_000)
        )
    }

    // --- the walk-away condition (issue #26) ----------------------------------------------------

    /**
     * THE ISSUE #26 CASE, in one assertion. The blocked app is in front, its window really did
     * resurface under the finishing overlay, so the foreground condition says launch. It must not.
     */
    @Test
    fun `the blocked app resurfacing during a walk-away does not re-arm the block`() {
        val walkAway = WalkAway(blocked, armedAtMs = 1_000)
        assertEquals(
            Decision.DROP_WALK_AWAY_IN_FLIGHT,
            BlockLaunchGate.decide(blocked, foreground = blocked, walkAway = walkAway, nowMs = 1_100)
        )
    }

    /** The counterfactual: without the window, that same state is a launch. */
    @Test
    fun `the same state with no walk-away in flight really would launch`() {
        assertEquals(
            "if this ever reads DROP, the test above has stopped proving anything",
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, foreground = blocked, walkAway = null, nowMs = 1_100)
        )
    }

    @Test
    fun `the walk-away window expires so a later re-entry still blocks`() {
        val walkAway = WalkAway(blocked, armedAtMs = 1_000)
        val justInside = 1_000 + BlockLaunchGate.WALK_AWAY_TRANSITION_MS - 1
        val atTheBoundary = 1_000 + BlockLaunchGate.WALK_AWAY_TRANSITION_MS
        assertEquals(
            Decision.DROP_WALK_AWAY_IN_FLIGHT,
            BlockLaunchGate.decide(blocked, blocked, walkAway, nowMs = justInside)
        )
        assertEquals(
            "the window is exclusive at its upper bound, a re-entry after it blocks",
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, blocked, walkAway, nowMs = atTheBoundary)
        )
    }

    /** Walking away from one app must not buy a free entry into a different one. */
    @Test
    fun `a walk-away from one app does not suppress a block for another`() {
        val walkAway = WalkAway(other, armedAtMs = 1_000)
        assertEquals(
            Decision.LAUNCH,
            BlockLaunchGate.decide(blocked, foreground = blocked, walkAway = walkAway, nowMs = 1_100)
        )
    }

    /**
     * Both conditions true at once. The walk-away answer wins because it is the more specific one,
     * and because logcat naming the wrong cause is how a device session gets spent on the wrong
     * hypothesis.
     */
    @Test
    fun `a walk-away in flight outranks a moved foreground in the reported reason`() {
        val walkAway = WalkAway(blocked, armedAtMs = 1_000)
        assertEquals(
            Decision.DROP_WALK_AWAY_IN_FLIGHT,
            BlockLaunchGate.decide(blocked, foreground = launcher, walkAway = walkAway, nowMs = 1_100)
        )
    }

    // --- foregroundAfter ------------------------------------------------------------------------

    @Test
    fun `only an app window, home and our own UI move the foreground`() {
        val previous = blocked
        assertEquals(other, BlockLaunchGate.foregroundAfter(ForegroundSignal.AppWindow(other), previous))
        assertEquals(launcher, BlockLaunchGate.foregroundAfter(ForegroundSignal.Home(launcher), previous))
        assertEquals(nudge, BlockLaunchGate.foregroundAfter(ForegroundSignal.OwnUi(nudge), previous))
    }

    /**
     * The four that must NOT move it. Each one of these being read as a foreground change would
     * drop legitimate blocks whenever the shade, a permission dialog, a keyboard or a
     * picture-in-picture bubble happened to land inside the milliseconds a rule lookup takes, the
     * `SYSTEM_PACKAGES`-answers-two-questions trap, for the fourth time.
     */
    @Test
    fun `a system surface, a keyboard, a PiP bubble and a non-window event leave the foreground alone`() {
        val previous = blocked
        listOf(
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground(blocked)
        ).forEach { signal ->
            assertEquals(
                "$signal must not move the foreground",
                previous,
                BlockLaunchGate.foregroundAfter(signal, previous)
            )
        }
    }

    // --- walkAwayAfter --------------------------------------------------------------------------

    @Test
    fun `there is nothing to expire when no walk-away is pending`() {
        assertNull(BlockLaunchGate.walkAwayAfter(ForegroundSignal.Home(launcher), pending = null))
    }

    /** The ordinary end of the window: the go-home landed, so it is over on evidence, not a clock. */
    @Test
    fun `observing home closes the walk-away window`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        assertNull(BlockLaunchGate.walkAwayAfter(ForegroundSignal.Home(launcher), pending))
    }

    @Test
    fun `the walked-away app coming back keeps the window open`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        assertSame(pending, BlockLaunchGate.walkAwayAfter(ForegroundSignal.AppWindow(blocked), pending))
    }

    /** Any OTHER app in front is proof the departure happened, so the window has done its job. */
    @Test
    fun `a different app coming to the front closes the walk-away window`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        assertNull(BlockLaunchGate.walkAwayAfter(ForegroundSignal.AppWindow(other), pending))
    }

    /**
     * Our own UI must NOT close it. The overlay that armed the window is itself Nudge UI on its way
     * out, and its dying window event arrives before the transition it is waiting for, closing on
     * it would make the window last approximately zero milliseconds and quietly restore issue #26.
     */
    @Test
    fun `Nudge's own UI does not close the walk-away window`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        assertSame(pending, BlockLaunchGate.walkAwayAfter(ForegroundSignal.OwnUi(nudge), pending))
    }

    @Test
    fun `signals that claim nothing about the foreground leave the window alone`() {
        val pending = WalkAway(blocked, armedAtMs = 1_000)
        listOf(
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground(blocked)
        ).forEach { signal ->
            assertSame("$signal must not close the walk-away window", pending, BlockLaunchGate.walkAwayAfter(signal, pending))
        }
    }
}
