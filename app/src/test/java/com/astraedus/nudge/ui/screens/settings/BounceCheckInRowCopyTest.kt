package com.astraedus.nudge.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings row must never be a switch that reads ON and silently delivers nothing. */
class BounceCheckInRowCopyTest {

    @Test
    fun `on with notifications blocked says so and the tap goes to the fix`() {
        val copy = bounceCheckInRowCopy(enabled = true, canPostNotifications = false)
        assertEquals(BOUNCE_ROW_BLOCKED_SUBTITLE, copy.subtitle)
        assertTrue(copy.tapFixesNotifications)
    }

    @Test
    fun `on and deliverable reads the plain explanation`() {
        val copy = bounceCheckInRowCopy(enabled = true, canPostNotifications = true)
        assertEquals(BOUNCE_ROW_SUBTITLE, copy.subtitle)
        assertFalse(copy.tapFixesNotifications)
    }

    /** Off is off: no nagging about a permission for a feature the user has not asked for. */
    @Test
    fun `off never mentions notifications, whatever the grant`() {
        listOf(true, false).forEach { canPost ->
            val copy = bounceCheckInRowCopy(enabled = false, canPostNotifications = canPost)
            assertEquals(BOUNCE_ROW_SUBTITLE, copy.subtitle)
            assertFalse(copy.tapFixesNotifications)
        }
    }

    @Test
    fun `the row is named the way the owner named it`() {
        assertEquals("Bro. wtf.", BOUNCE_ROW_TITLE)
    }
}
