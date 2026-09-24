package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TabCoverPlacement.of] is the validator between a HOST app's node bounds — the one input Nudge does
 * not control — and a filled rectangle painted on the user's screen. Every rejection below is a real
 * shape an accessibility node reports, and the consequence of accepting one is not a quiet failure:
 * it is an opaque block over an app Nudge was never asked to cover, with no visible way to dismiss it.
 */
class TabCoverPlacementTest {

    @Test
    fun `the measured reels tab bounds convert to width and height`() {
        assertEquals(
            TabCoverPlacement(x = 216, y = 1896, width = 216, height = 132),
            TabCoverPlacement.of(216, 1896, 432, 2028)
        )
    }

    @Test
    fun `an origin at zero is accepted`() {
        // feed_tab's real bounds: the leftmost tab genuinely starts at x=0.
        assertEquals(
            TabCoverPlacement(x = 0, y = 1896, width = 216, height = 132),
            TabCoverPlacement.of(0, 1896, 216, 2028)
        )
    }

    /** `[0,0][0,0]` is what a detached node reports — the single most likely junk value. */
    @Test
    fun `an all-zero rectangle is rejected`() {
        assertNull(TabCoverPlacement.of(0, 0, 0, 0))
    }

    @Test
    fun `zero width is rejected`() {
        assertNull(TabCoverPlacement.of(216, 1896, 216, 2028))
    }

    @Test
    fun `zero height is rejected`() {
        assertNull(TabCoverPlacement.of(216, 1896, 432, 1896))
    }

    @Test
    fun `negative width is rejected`() {
        assertNull(TabCoverPlacement.of(432, 1896, 216, 2028))
    }

    @Test
    fun `negative height is rejected`() {
        assertNull(TabCoverPlacement.of(216, 2028, 432, 1896))
    }

    /** A node animated or scrolled off the left edge; the cover would be anchored off-screen. */
    @Test
    fun `a negative left is rejected`() {
        assertNull(TabCoverPlacement.of(-216, 1896, 0, 2028))
    }

    @Test
    fun `a negative top is rejected`() {
        assertNull(TabCoverPlacement.of(216, -132, 432, 0))
    }

    /** Both coordinates negative, which is the shape of a fully off-screen node. */
    @Test
    fun `a fully off-screen rectangle is rejected`() {
        assertNull(TabCoverPlacement.of(-432, -132, -216, -1))
    }

    /**
     * A one-pixel rectangle is degenerate-looking but valid, and is NOT rejected: the validator's job
     * is to reject impossible geometry, not to second-guess a host app's layout. Pinned so nobody
     * "tidies up" a minimum-size heuristic in later — a size threshold would be a guess, and the class
     * KDoc's rule is that a rectangle we had to guess at is the one not to paint.
     */
    @Test
    fun `a one pixel rectangle is valid`() {
        assertEquals(
            TabCoverPlacement(x = 5, y = 7, width = 1, height = 1),
            TabCoverPlacement.of(5, 7, 6, 8)
        )
    }

    /** Value semantics: the decider compares placements with `==` to decide "did the tab move". */
    @Test
    fun `equal bounds produce equal placements`() {
        assertEquals(TabCoverPlacement.of(216, 1896, 432, 2028), TabCoverPlacement.of(216, 1896, 432, 2028))
    }
}
