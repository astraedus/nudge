package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 for [ReelPlayerOnScreen] (issue #190): which reel-player container nodes count as "the user is
 * watching the reel player". The real-geometry cases are in [ReelPlayerOnScreenFixtureTest].
 */
class ReelPlayerOnScreenTest {

    private val window = ScreenBounds(0, 0, 1080, 2028)

    /** The height, in px of [window], that is exactly the threshold. Derived, never retyped. */
    private val thresholdPx = Math.ceil(window.height * ReelPlayerOnScreen.MIN_HEIGHT_FRACTION).toInt()

    @Test
    fun `a visible node that fills the window counts`() {
        assertTrue(ReelPlayerOnScreen.counts(true, window, window))
    }

    @Test
    fun `a node the user cannot see never counts, whatever its bounds`() {
        assertFalse(ReelPlayerOnScreen.counts(false, window, window))
        assertFalse(ReelPlayerOnScreen.counts(false, null, null))
    }

    @Test
    fun `a detached node reporting empty bounds does not count`() {
        assertFalse(ReelPlayerOnScreen.counts(true, ScreenBounds(0, 0, 0, 0), window))
    }

    @Test
    fun `a node laid out next to the screen, as a neighbouring pager page is, does not count`() {
        assertFalse(ReelPlayerOnScreen.counts(true, ScreenBounds(1080, 0, 2160, 2028), window))
    }

    @Test
    fun `only the on-screen part of a node is measured`() {
        // Full height, but scrolled so that only the bottom third is on screen.
        assertFalse(ReelPlayerOnScreen.counts(true, ScreenBounds(0, -1352, 1080, 676), window))
    }

    @Test
    fun `the threshold is inclusive at exactly the minimum height and exclusive one pixel below`() {
        assertTrue(ReelPlayerOnScreen.counts(true, ScreenBounds(0, 0, 1080, thresholdPx), window))
        assertFalse(ReelPlayerOnScreen.counts(true, ScreenBounds(0, 0, 1080, thresholdPx - 1), window))
    }

    @Test
    fun `height decides, not width, so a centred full-height column still counts`() {
        val landscape = ScreenBounds(0, 0, 2560, 1600)
        assertTrue(ReelPlayerOnScreen.counts(true, ScreenBounds(830, 0, 1730, 1600), landscape))
    }

    /** Fails toward the old behaviour: a measurement problem must never turn a real player into a miss. */
    @Test
    fun `unreadable bounds fall back to visibility alone`() {
        assertTrue(ReelPlayerOnScreen.counts(true, null, window))
        assertTrue(ReelPlayerOnScreen.counts(true, window, null))
        assertTrue(ReelPlayerOnScreen.counts(true, ScreenBounds(0, 0, 1080, 10), ScreenBounds(0, 0, 0, 0)))
    }
}
