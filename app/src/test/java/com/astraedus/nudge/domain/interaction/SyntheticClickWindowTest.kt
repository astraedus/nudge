package com.astraedus.nudge.domain.interaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyntheticClickWindow] — "did WE just cause that click?"
 *
 * Measured on the bench device: the Following steer's own `ACTION_CLICK` came back as a real
 * `TYPE_VIEW_CLICKED` and was counted as the user's (`interaction counted ... reason=click
 * session=14`), inflating the "taps today" number the counter exists to make honest. Issue #28 is
 * the same failure from the other direction — counting autoplaying video as input — and the rule it
 * left behind is that the counter reads zero rather than making a number up.
 *
 * The failure direction matters in both tests below: suppressing too much silently UNDER-counts the
 * user, which is worse than the bug being fixed, so the window must be narrow, consumed, and unable
 * to swallow anything it was not opened for.
 */
class SyntheticClickWindowTest {

    @Test
    fun `a click with no dispatch behind it is the user's`() {
        val window = SyntheticClickWindow()

        assertFalse(window.shouldSuppress(1_000L))
    }

    @Test
    fun `the click we just dispatched is ours`() {
        val window = SyntheticClickWindow()
        window.onDispatch(1_000L)

        assertTrue(window.shouldSuppress(1_020L))
    }

    /**
     * ONE dispatch excuses ONE click. The steer only ever clicks once at a time, so a burst arriving
     * inside the window is the user tapping — and swallowing those would under-count them.
     */
    @Test
    fun `one dispatch suppresses exactly one click`() {
        val window = SyntheticClickWindow()
        window.onDispatch(1_000L)

        assertTrue("the first click is ours", window.shouldSuppress(1_010L))
        assertFalse("a second click inside the window is the user's", window.shouldSuppress(1_020L))
    }

    @Test
    fun `a click after the window has passed is the user's`() {
        val window = SyntheticClickWindow(windowMs = 500L)
        window.onDispatch(1_000L)

        assertFalse(window.shouldSuppress(1_501L))
    }

    @Test
    fun `a click exactly at the boundary is still ours`() {
        val window = SyntheticClickWindow(windowMs = 500L)
        window.onDispatch(1_000L)

        assertTrue(window.shouldSuppress(1_500L))
    }

    /**
     * The clock is monotonic in production (`SystemClock.elapsedRealtime`), but a caller handing back
     * an earlier timestamp must not be read as a match — that would be an unbounded window opening
     * backwards.
     */
    @Test
    fun `a click dated before the dispatch is not ours`() {
        val window = SyntheticClickWindow()
        window.onDispatch(1_000L)

        assertFalse(window.shouldSuppress(900L))
    }

    @Test
    fun `reset forgets an outstanding dispatch`() {
        val window = SyntheticClickWindow()
        window.onDispatch(1_000L)
        window.reset()

        assertFalse(window.shouldSuppress(1_010L))
    }

    /**
     * The window has to be long enough to cover event delivery on a loaded device and far short of a
     * plausible second real tap. Pinned as a NUMBER rather than "there is a default", because the
     * value is the whole safety argument.
     */
    @Test
    fun `the default window is sized for event delivery, not for a human`() {
        assertTrue(
            "must outlast event delivery, got ${SyntheticClickWindow.DEFAULT_WINDOW_MS}ms",
            SyntheticClickWindow.DEFAULT_WINDOW_MS >= 500L
        )
        assertTrue(
            "must be far shorter than a second deliberate tap, got " +
                "${SyntheticClickWindow.DEFAULT_WINDOW_MS}ms",
            SyntheticClickWindow.DEFAULT_WINDOW_MS <= 1_500L
        )
    }
}
