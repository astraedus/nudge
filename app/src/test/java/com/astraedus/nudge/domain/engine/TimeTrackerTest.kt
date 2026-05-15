package com.astraedus.nudge.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimeTrackerTest {

    private lateinit var timeTracker: TimeTracker

    @Before
    fun setUp() {
        timeTracker = TimeTracker()
    }

    @Test
    fun `hasExceededLimit returns true when usage exceeds limit`() {
        val usageMs = 61L * 60L * 1000L // 61 minutes
        assertTrue(timeTracker.hasExceededLimit(usageMs, limitMinutes = 60))
    }

    @Test
    fun `hasExceededLimit returns false when usage is under limit`() {
        val usageMs = 30L * 60L * 1000L // 30 minutes
        assertFalse(timeTracker.hasExceededLimit(usageMs, limitMinutes = 60))
    }

    @Test
    fun `hasExceededLimit returns true when usage exactly equals limit`() {
        val usageMs = 60L * 60L * 1000L // exactly 60 minutes
        assertTrue(timeTracker.hasExceededLimit(usageMs, limitMinutes = 60))
    }

    @Test
    fun `remainingMs returns positive value when under limit`() {
        val usageMs = 30L * 60L * 1000L // 30 minutes
        val remaining = timeTracker.remainingMs(usageMs, limitMinutes = 60)
        assertEquals(30L * 60L * 1000L, remaining) // 30 minutes remaining
    }

    @Test
    fun `remainingMs returns 0 when limit exceeded`() {
        val usageMs = 90L * 60L * 1000L // 90 minutes
        val remaining = timeTracker.remainingMs(usageMs, limitMinutes = 60)
        assertEquals(0L, remaining)
    }

    @Test
    fun `remainingMs returns 0 when usage exactly equals limit`() {
        val usageMs = 60L * 60L * 1000L
        val remaining = timeTracker.remainingMs(usageMs, limitMinutes = 60)
        assertEquals(0L, remaining)
    }

    @Test
    fun `formatDuration formats 30 seconds`() {
        assertEquals("30s", timeTracker.formatDuration(30_000L))
    }

    @Test
    fun `formatDuration formats 90 seconds as 1m 30s`() {
        assertEquals("1m 30s", timeTracker.formatDuration(90_000L))
    }

    @Test
    fun `formatDuration formats 1 hour as 1h 0m`() {
        assertEquals("1h 0m", timeTracker.formatDuration(3_600_000L))
    }

    @Test
    fun `formatDuration formats 2h 5m`() {
        val ms = (2L * 3600L + 5L * 60L) * 1000L
        assertEquals("2h 5m", timeTracker.formatDuration(ms))
    }

    @Test
    fun `formatDuration formats 45 minutes`() {
        assertEquals("45m", timeTracker.formatDuration(45L * 60L * 1000L))
    }

    @Test
    fun `formatDuration formats 0 as 0s`() {
        assertEquals("0s", timeTracker.formatDuration(0L))
    }
}
