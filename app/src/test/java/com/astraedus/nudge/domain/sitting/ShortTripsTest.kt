package com.astraedus.nudge.domain.sitting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored "keep the pass on short trips" value (#110) and the one rule Strict Mode reads. */
class ShortTripsTest {

    @Test
    fun `off and anything below it map to no window`() {
        assertNull(ShortTrips.returnWindowMs(ShortTrips.OFF))
        assertNull(ShortTrips.returnWindowMs(-3))
    }

    @Test
    fun `every offered length maps to that many minutes`() {
        ShortTrips.OPTIONS_MINUTES.forEach { minutes ->
            assertEquals(minutes * 60_000L, ShortTrips.returnWindowMs(minutes))
        }
    }

    /** A hand-edited or future preference can never hold a pass open past the longest option. */
    @Test
    fun `a stored value above the longest option is clamped to it`() {
        val longest = ShortTrips.OPTIONS_MINUTES.max()
        assertEquals(longest * 60_000L, ShortTrips.returnWindowMs(600))
    }

    /** Switching it on changes nothing about the length: it starts where app switches already are. */
    @Test
    fun `switching it on selects the shipped return window`() {
        assertEquals(
            SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS,
            ShortTrips.returnWindowMs(ShortTrips.DEFAULT_MINUTES)
        )
        assertTrue(ShortTrips.DEFAULT_MINUTES in ShortTrips.OPTIONS_MINUTES)
    }

    @Test
    fun `turning it on and lengthening it are weakening`() {
        assertTrue(ShortTrips.isWeakening(ShortTrips.OFF, 1))
        assertTrue(ShortTrips.isWeakening(2, 3))
    }

    @Test
    fun `shortening it, turning it off and re-selecting the same length are not`() {
        assertFalse(ShortTrips.isWeakening(5, 2))
        assertFalse(ShortTrips.isWeakening(3, ShortTrips.OFF))
        assertFalse(ShortTrips.isWeakening(3, 3))
    }

    /** Clamping applies to the comparison too, so an out-of-range stored value cannot hide a raise. */
    @Test
    fun `an out-of-range stored value compares as the longest option`() {
        assertFalse(ShortTrips.isWeakening(600, ShortTrips.OPTIONS_MINUTES.max()))
        assertTrue(ShortTrips.isWeakening(ShortTrips.OFF, 600))
    }
}
