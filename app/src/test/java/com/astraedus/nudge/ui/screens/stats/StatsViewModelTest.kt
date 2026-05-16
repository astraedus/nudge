package com.astraedus.nudge.ui.screens.stats

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Tests for the stats calculation logic extracted from StatsViewModel.
 * Uses StatsCalculator directly to avoid needing Android dependencies.
 */
class StatsViewModelTest {

    private lateinit var timeTracker: TimeTracker
    private lateinit var calculator: StatsCalculator

    @Before
    fun setup() {
        timeTracker = TimeTracker()
        calculator = StatsCalculator(timeTracker)
    }

    // --- Streak tests ---

    @Test
    fun `calculateStreak returns 0 when no events`() {
        val streak = calculator.calculateStreak(emptyList())
        assertEquals(0, streak)
    }

    @Test
    fun `calculateStreak counts consecutive days with walked away`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L

        val events = listOf(
            makeEvent(todayStart + 1000, userChangedMind = true),
            makeEvent(todayStart - dayMs + 5000, wasBlocked = true),
            makeEvent(todayStart - 2 * dayMs + 3000, userChangedMind = true),
        )

        val streak = calculator.calculateStreak(events)
        assertEquals(3, streak)
    }

    @Test
    fun `calculateStreak breaks on day with no nudge events`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L

        val events = listOf(
            makeEvent(todayStart + 1000, userChangedMind = true),
            // Yesterday - only regular usage, no block/walk-away
            makeEvent(todayStart - dayMs + 5000, wasBlocked = false, userChangedMind = false),
            makeEvent(todayStart - 2 * dayMs + 3000, wasBlocked = true),
        )

        val streak = calculator.calculateStreak(events)
        assertEquals(1, streak)
    }

    @Test
    fun `calculateStreak skips today if no events yet`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L

        val events = listOf(
            makeEvent(todayStart - dayMs + 5000, wasBlocked = true),
            makeEvent(todayStart - 2 * dayMs + 3000, userChangedMind = true),
        )

        val streak = calculator.calculateStreak(events)
        assertEquals(2, streak)
    }

    @Test
    fun `calculateStreak handles all 7 days active`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L

        val events = (0..6).map { i ->
            makeEvent(todayStart - i * dayMs + 1000, wasBlocked = true)
        }

        val streak = calculator.calculateStreak(events)
        assertEquals(7, streak)
    }

    // --- Hourly heatmap tests ---

    @Test
    fun `buildHourlyData returns 24 zeroes for empty events`() {
        val hourly = calculator.buildHourlyData(emptyList())
        assertEquals(24, hourly.size)
        assertTrue(hourly.all { it == 0L })
    }

    @Test
    fun `buildHourlyData buckets events by hour`() {
        val todayStart = timeTracker.startOfToday()
        val hourMs = 60L * 60L * 1000L

        val events = listOf(
            makeEvent(todayStart + 9 * hourMs + 1000, durationMs = 5000),
            makeEvent(todayStart + 9 * hourMs + 30000, durationMs = 3000),
            makeEvent(todayStart + 14 * hourMs + 1000, durationMs = 10000),
        )

        val hourly = calculator.buildHourlyData(events)
        assertEquals(24, hourly.size)
        assertEquals(8000L, hourly[9])
        assertEquals(10000L, hourly[14])
        assertEquals(0L, hourly[0])
        assertEquals(0L, hourly[23])
    }

    // --- Weekly data tests ---

    @Test
    fun `buildWeeklyData returns 7 entries`() {
        val weekly = calculator.buildWeeklyData(emptyList())
        assertEquals(7, weekly.size)
    }

    @Test
    fun `buildWeeklyData sums per day correctly`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L

        val events = listOf(
            makeEvent(todayStart + 1000, durationMs = 60000),
            makeEvent(todayStart + 5000, durationMs = 40000),
            makeEvent(todayStart - dayMs + 1000, durationMs = 120000),
        )

        val weekly = calculator.buildWeeklyData(events)
        // Last entry is today
        assertEquals(100000L, weekly.last().totalMs)
        // Second to last is yesterday
        assertEquals(120000L, weekly[weekly.size - 2].totalMs)
    }

    @Test
    fun `buildWeeklyData has correct day labels`() {
        val weekly = calculator.buildWeeklyData(emptyList())
        val validLabels = setOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        weekly.forEach { day ->
            assertTrue("Label '${day.label}' not in valid set", day.label in validLabels)
        }
    }

    // --- Trend data tests ---

    @Test
    fun `buildTrendData returns 7 entries`() {
        val trend = calculator.buildTrendData(emptyList())
        assertEquals(7, trend.size)
    }

    @Test
    fun `buildTrendData counts blocked and walked away`() {
        val todayStart = timeTracker.startOfToday()

        val events = listOf(
            makeEvent(todayStart + 1000, wasBlocked = true),
            makeEvent(todayStart + 2000, wasBlocked = true),
            makeEvent(todayStart + 3000, userChangedMind = true),
        )

        val trend = calculator.buildTrendData(events)
        val today = trend.last()
        assertEquals(2, today.blockedCount)
        assertEquals(1, today.walkedAwayCount)
    }

    // --- Helpers ---

    private fun makeEvent(
        timestamp: Long,
        durationMs: Long = 0L,
        wasBlocked: Boolean = false,
        userChangedMind: Boolean = false,
        packageName: String = "com.test.app"
    ): UsageEvent = UsageEvent(
        id = 0,
        packageName = packageName,
        timestamp = timestamp,
        durationMs = durationMs,
        wasBlocked = wasBlocked,
        userChangedMind = userChangedMind
    )
}
