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

    // --- buildWeeklyDataFromTotals tests ---

    @Test
    fun `buildWeeklyDataFromTotals returns 7 entries`() {
        val totals = listOf(100L, 200L, 300L, 400L, 500L, 600L, 700L)
        val weekly = calculator.buildWeeklyDataFromTotals(totals)
        assertEquals(7, weekly.size)
    }

    @Test
    fun `buildWeeklyDataFromTotals maps totals to correct days`() {
        val totals = listOf(
            60_000L,   // 6 days ago
            120_000L,  // 5 days ago
            180_000L,  // 4 days ago
            240_000L,  // 3 days ago
            300_000L,  // 2 days ago
            360_000L,  // yesterday
            420_000L   // today
        )
        val weekly = calculator.buildWeeklyDataFromTotals(totals)

        // Index 0 is 6 days ago, index 6 is today
        assertEquals(60_000L, weekly[0].totalMs)
        assertEquals(420_000L, weekly[6].totalMs)
        assertEquals(360_000L, weekly[5].totalMs)
    }

    @Test
    fun `buildWeeklyDataFromTotals has correct day labels`() {
        val totals = List(7) { 1000L }
        val weekly = calculator.buildWeeklyDataFromTotals(totals)
        val validLabels = setOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        weekly.forEach { day ->
            assertTrue("Label '${day.label}' not in valid set", day.label in validLabels)
        }
    }

    @Test
    fun `buildWeeklyDataFromTotals handles empty list gracefully`() {
        val weekly = calculator.buildWeeklyDataFromTotals(emptyList())
        assertEquals(7, weekly.size)
        assertTrue("All should be 0 when no data", weekly.all { it.totalMs == 0L })
    }

    // --- Regression: durationMs=0 events produce zero screen time ---

    @Test
    fun `buildWeeklyData shows zero when events have no durationMs - documents the old bug`() {
        val todayStart = timeTracker.startOfToday()

        // Simulate real logged events: durationMs is always 0 (the bug's root cause)
        val events = listOf(
            makeEvent(todayStart + 1000, durationMs = 0L, wasBlocked = true),
            makeEvent(todayStart + 5000, durationMs = 0L),
            makeEvent(todayStart + 10000, durationMs = 0L, wasBlocked = true),
        )

        val weekly = calculator.buildWeeklyData(events)
        // This is the documented bug: events logged without durationMs always sum to 0
        assertEquals(0L, weekly.last().totalMs)
    }

    // --- Per-app trend data tests ---

    @Test
    fun `buildAppTrendData returns 7 entries`() {
        val trend = calculator.buildAppTrendData(emptyList(), "com.test.app")
        assertEquals(7, trend.size)
    }

    @Test
    fun `buildAppTrendData filters by package name`() {
        val todayStart = timeTracker.startOfToday()

        val events = listOf(
            makeEvent(todayStart + 1000, wasBlocked = true, packageName = "com.test.app"),
            makeEvent(todayStart + 2000, wasBlocked = true, packageName = "com.other.app"),
            makeEvent(todayStart + 3000, userChangedMind = true, packageName = "com.test.app"),
        )

        val trend = calculator.buildAppTrendData(events, "com.test.app")
        val today = trend.last()
        assertEquals(1, today.blockedCount)
        assertEquals(1, today.walkedAwayCount)
    }

    @Test
    fun `buildAppTrendData ignores events from other packages`() {
        val todayStart = timeTracker.startOfToday()

        val events = listOf(
            makeEvent(todayStart + 1000, wasBlocked = true, packageName = "com.other.app"),
            makeEvent(todayStart + 2000, userChangedMind = true, packageName = "com.other.app"),
        )

        val trend = calculator.buildAppTrendData(events, "com.test.app")
        val today = trend.last()
        assertEquals(0, today.blockedCount)
        assertEquals(0, today.walkedAwayCount)
    }

    // --- Date-parameterized referenceDayStartMs tests ---

    @Test
    fun `buildWeeklyData with past reference day includes correct window`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L
        // Reference day = yesterday
        val yesterdayStart = todayStart - dayMs

        val events = listOf(
            // Event on yesterday
            makeEvent(yesterdayStart + 1000, durationMs = 50000),
            // Event 2 days ago (within yesterday's 7-day window)
            makeEvent(yesterdayStart - dayMs + 1000, durationMs = 30000),
            // Event on today (outside yesterday's 7-day ending window)
            makeEvent(todayStart + 1000, durationMs = 99000),
        )

        val weekly = calculator.buildWeeklyData(events, referenceDayStartMs = yesterdayStart)
        // Last entry should be yesterday, so today's event should NOT appear
        assertEquals(50000L, weekly.last().totalMs)
        assertEquals(30000L, weekly[weekly.size - 2].totalMs)
    }

    @Test
    fun `buildTrendData with past reference day scopes correctly`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L
        val yesterdayStart = todayStart - dayMs

        val events = listOf(
            makeEvent(yesterdayStart + 1000, wasBlocked = true),
            makeEvent(todayStart + 1000, wasBlocked = true),
        )

        val trend = calculator.buildTrendData(events, referenceDayStartMs = yesterdayStart)
        // Last entry is yesterday
        val lastDay = trend.last()
        assertEquals(1, lastDay.blockedCount)
    }

    @Test
    fun `calculateStreak with past reference day uses correct anchor`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L
        val yesterdayStart = todayStart - dayMs

        val events = listOf(
            makeEvent(yesterdayStart + 1000, wasBlocked = true),
            makeEvent(yesterdayStart - dayMs + 1000, wasBlocked = true),
        )

        val streak = calculator.calculateStreak(events, referenceDayStartMs = yesterdayStart)
        assertEquals(2, streak)
    }

    @Test
    fun `buildWeeklyDataFromTotals with past reference day produces correct labels`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L
        val yesterdayStart = todayStart - dayMs

        val totals = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L)
        val weekly = calculator.buildWeeklyDataFromTotals(totals, referenceDayStartMs = yesterdayStart)

        assertEquals(7, weekly.size)
        // Last entry should use yesterday's day label
        assertEquals(70L, weekly.last().totalMs)
        val validLabels = setOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        weekly.forEach { day ->
            assertTrue("Label '${day.label}' not in valid set", day.label in validLabels)
        }
    }

    @Test
    fun `buildAppTrendData with past reference day scopes to correct window`() {
        val todayStart = timeTracker.startOfToday()
        val dayMs = 24L * 60L * 60L * 1000L
        val yesterdayStart = todayStart - dayMs

        val events = listOf(
            makeEvent(yesterdayStart + 1000, wasBlocked = true, packageName = "com.test.app"),
            makeEvent(todayStart + 1000, wasBlocked = true, packageName = "com.test.app"),
        )

        val trend = calculator.buildAppTrendData(events, "com.test.app", referenceDayStartMs = yesterdayStart)
        val lastDay = trend.last()
        // Only yesterday's event should be in the last slot
        assertEquals(1, lastDay.blockedCount)
    }

    // --- formatDateLabel tests ---

    @Test
    fun `formatDateLabel produces readable output`() {
        val date = java.time.LocalDate.of(2026, 5, 21)
        val label = StatsViewModel.formatDateLabel(date)
        // May 21, 2026 is a Thursday
        assertTrue("Label should contain 'Thu', got: $label", label.contains("Thu"))
        assertTrue("Label should contain 'May', got: $label", label.contains("May"))
        assertTrue("Label should contain '21', got: $label", label.contains("21"))
    }

    @Test
    fun `toEpochMs converts LocalDate to midnight epoch millis`() {
        val date = java.time.LocalDate.of(2026, 1, 1)
        val epochMs = with(StatsViewModel.Companion) { date.toEpochMs() }
        // Should be a positive value at midnight
        assertTrue("Epoch ms should be positive", epochMs > 0)
        // Should be at midnight (no fractional day)
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = epochMs
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
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
