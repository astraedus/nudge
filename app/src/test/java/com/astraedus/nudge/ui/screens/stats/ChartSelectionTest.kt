package com.astraedus.nudge.ui.screens.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The day-selection contract as BEHAVIOUR, through the one state holder ([StatsDaySelection]).
 *
 * The bug it replaces a source scan for: `WeeklyBarChart` and `BlockedTrendChart` each kept a
 * private `selectedIndex`, so tapping a bar dimmed its neighbours and printed a tooltip while no
 * number on the screen moved, because the screen's real day lived in the ViewModel. Charts are now
 * controlled by the holder: whatever bar is highlighted is, by construction, the day whose numbers
 * are shown. These tests pin that invariant for every way the selection can move.
 */
class ChartSelectionTest {

    private val today = LocalDate.of(2026, 8, 21)

    /** The day a chart draws at bar [index] of the window. */
    private fun dayDrawnAt(s: StatsDaySelection, index: Int) = s.weekStart.plusDays(index.toLong())

    private fun assertBarMatchesNumbers(s: StatsDaySelection) {
        assertEquals(
            "the highlighted bar must be the day the screen's numbers describe",
            s.selected,
            dayDrawnAt(s, s.selectedIndex)
        )
    }

    @Test
    fun `tapping any bar highlights that bar and moves the numbers to its day`() {
        val start = StatsDaySelection.startingAt(today)
        for (tap in 0 until StatsDaySelection.WINDOW_DAYS) {
            val after = start.selectIndex(tap, today)
            assertEquals("bar $tap is highlighted", tap, after.selectedIndex)
            assertEquals("the numbers follow bar $tap", dayDrawnAt(start, tap), after.selected)
            assertBarMatchesNumbers(after)
        }
    }

    @Test
    fun `bar and numbers stay in step through arrows, taps and the way back`() {
        var s = StatsDaySelection.startingAt(today)
        val steps: List<(StatsDaySelection) -> StatsDaySelection> = listOf(
            { it.previousDay() },
            { it.previousDay() },
            { it.selectIndex(0, today) },
            { it.previousDay() }, // slides the window off the left edge
            { it.nextDay(today) },
            { it.selectIndex(6, today) },
            { it.jumpToToday(today) }
        )
        steps.forEach { step ->
            s = step(s)
            assertBarMatchesNumbers(s)
        }
        assertEquals(today, s.selected)
    }
}
