package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * All five transitions of [TabCoverDecider], with the "bounds unchanged" one carrying the most weight.
 *
 * The decider holds no state, so each case is one call with the state passed in. That is the design
 * being pinned as much as the rule: a decider keeping its own copy of `shown` could desynchronise from
 * the real window, and then no test could tell you which of the two was right.
 */
class TabCoverDeciderTest {

    private val decider = TabCoverDecider()
    private val tab = TabCoverPlacement(x = 216, y = 1896, width = 216, height = 132)
    private val moved = TabCoverPlacement(x = 216, y = 1800, width = 216, height = 132)

    // 1. Not requested, nothing shown.
    @Test
    fun `not requested with nothing shown does nothing`() {
        assertEquals(TabCoverEffect.None, decider.decide(vanishRequested = false, bounds = tab, shown = null))
    }

    // 2. Not requested, something shown.
    @Test
    fun `not requested with a cover up hides it`() {
        assertEquals(TabCoverEffect.Hide, decider.decide(vanishRequested = false, bounds = tab, shown = tab))
    }

    // 3. Requested with bounds, nothing shown.
    @Test
    fun `requested with bounds and nothing shown shows the cover`() {
        assertEquals(
            TabCoverEffect.Show(tab),
            decider.decide(vanishRequested = true, bounds = tab, shown = null)
        )
    }

    /**
     * 4. Requested, shown, bounds unchanged. **The load-bearing case.**
     *
     * The service asks this on content-change events, and a scrolling Instagram feed fires those
     * continuously while `tab_bar` stays exactly where it is. Re-issuing `Show` on each one would churn
     * the window through the window manager dozens of times a second: visible flicker, and a window
     * repeatedly torn down and rebuilt is a window that is briefly not eating the tap it exists to eat.
     */
    @Test
    fun `requested with unchanged bounds does nothing`() {
        assertEquals(
            "a cover already exactly there must not be re-shown — this is the no-churn rule",
            TabCoverEffect.None,
            decider.decide(vanishRequested = true, bounds = tab, shown = tab)
        )
    }

    /** An equal-by-value but distinct instance must also count as unchanged. */
    @Test
    fun `unchanged is decided by value not identity`() {
        val sameValue = TabCoverPlacement(x = 216, y = 1896, width = 216, height = 132)
        assertEquals(
            TabCoverEffect.None,
            decider.decide(vanishRequested = true, bounds = sameValue, shown = tab)
        )
    }

    // 5. Requested, shown, bounds moved.
    @Test
    fun `requested with moved bounds repositions the cover`() {
        assertEquals(
            TabCoverEffect.Show(moved),
            decider.decide(vanishRequested = true, bounds = moved, shown = tab)
        )
    }

    /**
     * Requested but the tab node is gone or reported junk bounds: hide, do not freeze.
     *
     * A `clips_tab` that has vanished from the tree means the Following screen or the reel player —
     * both of which have no nav bar at all — so the cover has nothing left to sit on. Leaving it where
     * it was would paint it over content.
     */
    @Test
    fun `requested with no bounds hides an existing cover`() {
        assertEquals(
            TabCoverEffect.Hide,
            decider.decide(vanishRequested = true, bounds = null, shown = tab)
        )
    }

    @Test
    fun `requested with no bounds and nothing shown does nothing`() {
        assertEquals(
            TabCoverEffect.None,
            decider.decide(vanishRequested = true, bounds = null, shown = null)
        )
    }

    /** `Hide` is only ever emitted when something is actually up; otherwise it is `None`. */
    @Test
    fun `hide is never emitted when no cover is up`() {
        val cases = listOf(
            decider.decide(vanishRequested = false, bounds = null, shown = null),
            decider.decide(vanishRequested = false, bounds = tab, shown = null),
            decider.decide(vanishRequested = true, bounds = null, shown = null)
        )
        cases.forEach { assertEquals(TabCoverEffect.None, it) }
    }

    /** The decider is a pure function of its arguments: repeating a call repeats the answer. */
    @Test
    fun `the decider holds no state between calls`() {
        val first = decider.decide(vanishRequested = true, bounds = tab, shown = null)
        decider.decide(vanishRequested = false, bounds = null, shown = tab)
        val again = decider.decide(vanishRequested = true, bounds = tab, shown = null)
        assertEquals(first, again)
    }
}
