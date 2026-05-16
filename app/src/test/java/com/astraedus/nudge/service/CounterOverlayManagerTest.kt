package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CounterOverlayManagerTest {

    @Test
    fun `formatCompactDuration formats 0 as 0s`() {
        assertEquals("0s", CounterOverlayManager.formatCompactDuration(0L))
    }

    @Test
    fun `formatCompactDuration formats seconds only`() {
        assertEquals("45s", CounterOverlayManager.formatCompactDuration(45_000L))
    }

    @Test
    fun `formatCompactDuration formats minutes only`() {
        assertEquals("42m", CounterOverlayManager.formatCompactDuration(42L * 60 * 1000))
    }

    @Test
    fun `formatCompactDuration formats hours and minutes`() {
        assertEquals("1h 12m", CounterOverlayManager.formatCompactDuration((1L * 60 + 12) * 60 * 1000))
    }

    @Test
    fun `formatCompactDuration formats exactly 1 hour as 1h 0m`() {
        assertEquals("1h 0m", CounterOverlayManager.formatCompactDuration(3_600_000L))
    }

    @Test
    fun `formatCompactDuration handles negative values`() {
        assertEquals("0s", CounterOverlayManager.formatCompactDuration(-1000L))
    }

    @Test
    fun `formatCompactDuration rounds down sub-minute to minutes`() {
        // 2 minutes and 30 seconds -> should show 2m (no seconds when minutes > 0)
        assertEquals("2m", CounterOverlayManager.formatCompactDuration(150_000L))
    }
}
