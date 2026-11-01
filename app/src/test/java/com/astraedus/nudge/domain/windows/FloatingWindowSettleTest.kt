package com.astraedus.nudge.domain.windows

import com.astraedus.nudge.NudgeIdentity
import com.astraedus.nudge.domain.windows.FloatingWindowSettle.Companion.MAX_WAIT_MS
import com.astraedus.nudge.domain.windows.FloatingWindowSettle.Companion.SETTLE_MS
import com.astraedus.nudge.domain.windows.WindowFixtures.FREEFORM
import com.astraedus.nudge.domain.windows.WindowFixtures.floatingApp
import com.astraedus.nudge.domain.windows.WindowFixtures.fullscreenApp
import com.astraedus.nudge.domain.windows.WindowFixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1: a window is floating only once it has STAYED floating-sized (issue #119), and the #71 cover
 * still reaches a real floating window.
 */
class FloatingWindowSettleTest {

    // --- #119: the field capture, Pixel 9a / GrapheneOS / Android 17 ------------------------------

    /**
     * The window lists of P119-A2 (v1.23.0, 2026-11-01), as the service logged them. Clock's bounds
     * are the cover's own log line at 41.786 (`WindowBounds(left=106, top=237, right=974,
     * bottom=2186)`); a window the log printed without a size is fullscreen by
     * `WindowSnapshotReader.describe`'s rule, so it gets the whole display.
     */
    private object A2 {
        const val DISPLAY_W = 1080
        const val DISPLAY_H = 2424
        const val CLOCK = "com.android.deskclock"
        val FULL = WindowBounds(0, 0, DISPLAY_W, DISPLAY_H)
        val CLOCK_MID_ANIMATION = WindowBounds(106, 237, 974, 2186)

        fun list(vararg windows: ScreenWindow) = WindowSnapshot(windows.toList(), DISPLAY_W, DISPLAY_H)

        /** 41.771: `[10:system, 12:system, 18:application:com.android.deskclock:focused:active:868x1949]` */
        val clockAnimatingIn = list(
            WindowFixtures.window(10, WindowKind.SYSTEM),
            WindowFixtures.window(12, WindowKind.SYSTEM),
            fullscreenApp(18, CLOCK, focused = true, bounds = CLOCK_MID_ANIMATION)
        )

        /** 41.983: `[25:accessibility_overlay, 10:system, 12:system, 27:application:<nudge>:focused:active]` */
        val overlayUp = list(
            WindowFixtures.window(25, WindowKind.ACCESSIBILITY_OVERLAY),
            WindowFixtures.window(10, WindowKind.SYSTEM),
            WindowFixtures.window(12, WindowKind.SYSTEM),
            fullscreenApp(27, NudgeIdentity.APPLICATION_ID, focused = true, bounds = FULL)
        )

        const val T_CLOCK_READ = 41_771L
        const val T_LAUNCH = 41_786L
        const val T_OVERLAY_READ = 41_983L
    }

    @Test
    fun `precondition - the captured Clock frame IS floating-sized to the single-snapshot rule`() {
        // If this ever fails the fixture no longer exercises #119: the frame must sit below the
        // fullscreen threshold on both axes, which is what the one-list rule misread.
        val w = A2.CLOCK_MID_ANIMATION
        assertTrue(w.width < A2.DISPLAY_W * WindowSnapshot.FULLSCREEN_FRACTION)
        assertTrue(w.height < A2.DISPLAY_H * WindowSnapshot.FULLSCREEN_FRACTION)
        assertTrue(A2.clockAnimatingIn.isFloating(A2.CLOCK))
    }

    @Test
    fun `#119 replay - a fullscreen app mid-launch-animation never licenses the BACK`() {
        val settle = FloatingWindowSettle()
        settle.observe(A2.clockAnimatingIn, A2.T_CLOCK_READ)

        // The launch site asked 15ms after the read. Shipped: BACK on window 18.
        val atLaunch = settle.verdict(A2.clockAnimatingIn, A2.CLOCK, A2.T_LAUNCH)
        assertTrue("got $atLaunch", atLaunch is FloatingVerdict.Unsettled)
        assertEquals(
            "re-check once the window has had a full settle period",
            SETTLE_MS - (A2.T_LAUNCH - A2.T_CLOCK_READ),
            (atLaunch as FloatingVerdict.Unsettled).recheckInMs
        )

        // The next list: our overlay is up and Clock (occluded) is not in it. The deferred check
        // finds nothing to cover, and the ordinary overlay was the whole block.
        settle.observe(A2.overlayUp, A2.T_OVERLAY_READ)
        val recheckAt = A2.T_LAUNCH + atLaunch.recheckInMs
        assertEquals(FloatingVerdict.NotFloating, settle.verdict(A2.overlayUp, A2.CLOCK, recheckAt))
    }

    @Test
    fun `#119 counterfactual - the shipped single-snapshot rule presses BACK on the same frame`() {
        // What 1.23.0 asked at 41.786: the cover target and the BACK target off this one list.
        assertEquals(A2.CLOCK_MID_ANIMATION, FloatingWindowCover.coverFor(A2.clockAnimatingIn, A2.CLOCK))
        assertEquals(18, FloatingWindowCover.backTarget(A2.clockAnimatingIn, A2.CLOCK, alreadyPressed = emptySet()))
    }

    @Test
    fun `#119 - even a stale mid-animation frame settles only after the full period`() {
        // Worst case for the fix: the platform never sends a later list and the animated frame is
        // still what the window list says at the re-check. One tick short is still not floating.
        val settle = FloatingWindowSettle()
        settle.observe(A2.clockAnimatingIn, A2.T_CLOCK_READ)
        val justShort = A2.T_CLOCK_READ + SETTLE_MS - 1
        assertTrue(settle.verdict(A2.clockAnimatingIn, A2.CLOCK, justShort) is FloatingVerdict.Unsettled)
    }

    // --- #71 must keep working ---------------------------------------------------------------------

    private val launcher = "com.huawei.android.launcher"
    private val youtube = "com.google.android.youtube"
    private val freeform = snapshot(fullscreenApp(1, launcher, focused = false), floatingApp(60, youtube, focused = true))

    @Test
    fun `#71 - a floating window that holds still settles after SETTLE_MS`() {
        val settle = FloatingWindowSettle()
        settle.observe(freeform, 0L)
        assertTrue(settle.verdict(freeform, youtube, SETTLE_MS - 1) is FloatingVerdict.Unsettled)
        // No new list arrived: a still window produces no window events. Time alone settles it.
        assertEquals(FloatingVerdict.Settled(FREEFORM), settle.verdict(freeform, youtube, SETTLE_MS))
    }

    @Test
    fun `#71 - a window open since before the block is settled the moment the block is asked`() {
        val settle = FloatingWindowSettle()
        settle.observe(freeform, 0L)
        settle.observe(freeform, 5_000L)
        assertEquals(FloatingVerdict.Settled(FREEFORM), settle.verdict(freeform, youtube, 5_000L))
    }

    @Test
    fun `moving restarts the settle, and a window that never stops is floating after MAX_WAIT_MS`() {
        val settle = FloatingWindowSettle()
        var t = 0L
        var bounds = FREEFORM
        while (t < MAX_WAIT_MS) {
            val list = snapshot(fullscreenApp(1, launcher, focused = false), floatingApp(60, youtube, true, bounds))
            settle.observe(list, t)
            assertTrue("moving at t=$t", settle.verdict(list, youtube, t) is FloatingVerdict.Unsettled)
            bounds = WindowBounds(bounds.left + 10, bounds.top, bounds.right + 10, bounds.bottom)
            t += SETTLE_MS / 3
        }
        val last = snapshot(fullscreenApp(1, launcher, focused = false), floatingApp(60, youtube, true, bounds))
        settle.observe(last, t)
        assertTrue("never withdrawn, only delayed", settle.verdict(last, youtube, t) is FloatingVerdict.Settled)
    }

    @Test
    fun `the re-check is never later than the max wait`() {
        val settle = FloatingWindowSettle()
        settle.observe(freeform, 0L)
        val moved = snapshot(fullscreenApp(1, launcher, focused = false), floatingApp(60, youtube, true, WindowBounds(130, 600, 910, 1700)))
        val t = MAX_WAIT_MS - SETTLE_MS / 2
        settle.observe(moved, t)
        val verdict = settle.verdict(moved, youtube, t)
        assertEquals(FloatingVerdict.Unsettled(moved.floatingBoundsOf(youtube)!!, MAX_WAIT_MS - t), verdict)
    }

    @Test
    fun `going fullscreen forgets the history, coming back floating starts over`() {
        val settle = FloatingWindowSettle()
        settle.observe(freeform, 0L)
        val full = snapshot(fullscreenApp(60, youtube, focused = true))
        settle.observe(full, 2 * SETTLE_MS)
        assertEquals(FloatingVerdict.NotFloating, settle.verdict(full, youtube, 2 * SETTLE_MS))
        settle.observe(freeform, 3 * SETTLE_MS)
        assertTrue(settle.verdict(freeform, youtube, 3 * SETTLE_MS) is FloatingVerdict.Unsettled)
    }

    @Test
    fun `a window already under the cover stays confirmed while the user drags it`() {
        val settle = FloatingWindowSettle()
        settle.observe(freeform, 0L)
        val dragged = snapshot(fullscreenApp(1, launcher, focused = false), floatingApp(60, youtube, true, WindowBounds(200, 600, 980, 1700)))
        // Inside the max wait, so only the cover can make this verdict Settled.
        settle.observe(dragged, SETTLE_MS)
        assertTrue(settle.verdict(dragged, youtube, SETTLE_MS) is FloatingVerdict.Unsettled)
        assertEquals(
            FloatingVerdict.Settled(dragged.floatingBoundsOf(youtube)!!),
            settle.verdict(dragged, youtube, SETTLE_MS, covered = true)
        )
    }

    @Test
    fun `a window floating for longer than the max wait stays floating when it moves`() {
        val settle = FloatingWindowSettle()
        settle.observe(freeform, 0L)
        val moved = snapshot(fullscreenApp(1, launcher, focused = false), floatingApp(60, youtube, true, WindowBounds(200, 600, 980, 1700)))
        settle.observe(moved, MAX_WAIT_MS)
        assertTrue(settle.verdict(moved, youtube, MAX_WAIT_MS) is FloatingVerdict.Settled)
    }

    @Test
    fun `covered never makes a non-floating window floating`() {
        val settle = FloatingWindowSettle()
        val full = snapshot(fullscreenApp(60, youtube, focused = true))
        settle.observe(full, 0L)
        assertEquals(FloatingVerdict.NotFloating, settle.verdict(full, youtube, 0L, covered = true))
    }

    @Test
    fun `no window list is no evidence - neither a verdict nor a reset`() {
        val settle = FloatingWindowSettle()
        settle.observe(freeform, 0L)
        settle.observe(null, SETTLE_MS / 2)
        assertEquals(FloatingVerdict.NotFloating, settle.verdict(null, youtube, SETTLE_MS))
        assertEquals("the failed read did not restart the clock", FloatingVerdict.Settled(FREEFORM), settle.verdict(freeform, youtube, SETTLE_MS))
    }

    @Test
    fun `a snapshot the settle never observed is not evidence of stillness`() {
        val settle = FloatingWindowSettle()
        assertTrue(settle.verdict(freeform, youtube, 60_000L) is FloatingVerdict.Unsettled)
    }

    @Test
    fun `reset forgets every sighting`() {
        val settle = FloatingWindowSettle()
        settle.observe(freeform, 0L)
        settle.reset()
        assertFalse(settle.verdict(freeform, youtube, 60_000L) is FloatingVerdict.Settled)
    }

    @Test
    fun `the settle outlasts the platform's 500ms forced window-list refresh`() {
        // AccessibilityWindowsPopulator holds window-list updates back while windows change on
        // consecutive frames and forces one out after 500ms. A list younger than that may simply not
        // have been refreshed yet, so it cannot be the evidence a window holds still.
        assertTrue(SETTLE_MS > 500L)
        assertTrue(MAX_WAIT_MS > SETTLE_MS)
    }
}
