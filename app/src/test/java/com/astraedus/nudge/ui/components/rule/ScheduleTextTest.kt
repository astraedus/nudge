package com.astraedus.nudge.ui.components.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a rule is active, in words (spec 3.2), matching what ScheduleEvaluator enforces. */
class ScheduleTextTest {

    private fun text(days: List<Int>?, start: Int? = null, end: Int? = null) =
        RuleSummary.scheduleText(days, start, end)

    @Test
    fun `all the time is left out`() {
        assertNull(text(null))
        assertNull(text(emptyList()))
        assertNull(text((1..7).toList()))
        // Half a window is no window to the engine.
        assertNull(text(null, start = 540))
        assertNull(text(null, end = 1020))
    }

    @Test
    fun `days compress into ranges of three or more`() {
        assertEquals("Mon–Fri", text(listOf(5, 4, 3, 2, 1)))
        assertEquals("Mon–Wed, Sat", text(listOf(1, 2, 3, 6)))
        assertEquals("Mon, Wed, Fri", text(listOf(1, 3, 5)))
        assertEquals("Sat, Sun", text(listOf(6, 7)))
        assertEquals("Tue", text(listOf(2, 2)))
    }

    @Test
    fun `out-of-range days are ignored, as the picker never writes them`() {
        assertEquals("Mon", text(listOf(0, 1, 8)))
    }

    @Test
    fun `times are 24h without a leading zero, overnight kept as written`() {
        assertEquals("Mon–Fri 9:00–17:00", text(listOf(1, 2, 3, 4, 5), 540, 1020))
        assertEquals("Every day 22:00–6:30", text(null, 22 * 60, 6 * 60 + 30))
        assertEquals("Sat, Sun 0:00–12:05", text(listOf(6, 7), 0, 725))
    }
}
