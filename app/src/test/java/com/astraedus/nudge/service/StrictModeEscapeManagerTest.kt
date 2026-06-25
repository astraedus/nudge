package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StrictModeEscapeManagerTest {

    private lateinit var manager: StrictModeEscapeManager

    @Before
    fun setUp() {
        manager = StrictModeEscapeManager()
    }

    @Test
    fun `no grace by default`() {
        assertFalse(manager.isWithinGrace(now = 1_000L))
    }

    @Test
    fun `grant opens a grace window for the configured duration`() {
        val now = 10_000L
        manager.grantGrace(now)

        // Inside the window.
        assertTrue(manager.isWithinGrace(now))
        assertTrue(manager.isWithinGrace(now + StrictModeEscapeManager.GRACE_WINDOW_MS - 1))
    }

    @Test
    fun `grace expires exactly at the window boundary`() {
        val now = 10_000L
        manager.grantGrace(now)

        // At and after graceUntil, no longer within grace.
        assertFalse(manager.isWithinGrace(now + StrictModeEscapeManager.GRACE_WINDOW_MS))
        assertFalse(manager.isWithinGrace(now + StrictModeEscapeManager.GRACE_WINDOW_MS + 1))
    }

    @Test
    fun `clear ends an active grace window immediately`() {
        val now = 10_000L
        manager.grantGrace(now)
        assertTrue(manager.isWithinGrace(now))

        manager.clear()

        assertFalse(manager.isWithinGrace(now))
        assertEquals(0L, manager.graceUntil)
    }

    @Test
    fun `re-granting extends the window from the new now`() {
        manager.grantGrace(10_000L)
        // A later grant pushes the window forward.
        manager.grantGrace(50_000L)
        assertEquals(50_000L + StrictModeEscapeManager.GRACE_WINDOW_MS, manager.graceUntil)
        assertTrue(manager.isWithinGrace(50_000L + 100))
    }
}
