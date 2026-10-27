package com.astraedus.nudge.ui.screens.settings

import com.astraedus.nudge.domain.sitting.ShortTrips
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the "keep the pass on short trips" row tells the user (#110). */
class ShortTripsRowTest {

    @Test
    fun `off says that leaving costs a new wait`() {
        val text = shortTripsRowSubtitle(ShortTrips.OFF, strictModeEnabled = false)
        assertTrue(text, text.startsWith("Off"))
    }

    @Test
    fun `on names the configured length`() {
        val text = shortTripsRowSubtitle(3, strictModeEnabled = false)
        assertTrue(text, text.contains("3 min"))
        assertFalse(text, text.contains("unlock challenge"))
    }

    @Test
    fun `under strict mode it says loosening needs the challenge`() {
        assertTrue(shortTripsRowSubtitle(3, strictModeEnabled = true).contains("unlock challenge"))
    }
}
