package com.astraedus.nudge.ui.screens.stats

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

/**
 * Pure calculation logic for stats screen, extracted for testability.
 * No Android dependencies -- operates only on data models.
 *
 * Methods that take a [referenceDayStartMs] use that as the "anchor" day
 * (the last day of a 7-day window). When omitted, defaults to today.
 */
class StatsCalculator @Inject constructor(
    private val timeTracker: TimeTracker
) {

    private val todayStart: Long get() = timeTracker.startOfToday()

    fun buildWeeklyData(weekEvents: List<UsageEvent>, referenceDayStartMs: Long = todayStart): List<DayData> {
        val result = mutableListOf<DayData>()

        for (i in 6 downTo 0) {
            val dayStart = referenceDayStartMs - i * DAY_MS
            val dayEnd = dayStart + DAY_MS
            val dayTotal = weekEvents
                .filter { it.timestamp in dayStart until dayEnd }
                .sumOf { it.durationMs }

            val label = getDayLabel(dayStart)
            result.add(DayData(label = label, totalMs = dayTotal))
        }

        return result
    }

    /**
     * Build weekly bar chart data from pre-computed daily totals (from UsageStatsManager).
     * @param dailyTotals list of 7 entries, index 0 = 6 days ago, index 6 = reference day.
     * @param referenceDayStartMs the anchor day (for day labels). Defaults to today.
     */
    fun buildWeeklyDataFromTotals(dailyTotals: List<Long>, referenceDayStartMs: Long = todayStart): List<DayData> {
        val result = mutableListOf<DayData>()

        for (i in 6 downTo 0) {
            val dayStart = referenceDayStartMs - i * DAY_MS
            val label = getDayLabel(dayStart)
            val totalMs = dailyTotals.getOrElse(6 - i) { 0L }
            result.add(DayData(label = label, totalMs = totalMs))
        }

        return result
    }

    fun buildTrendData(weekEvents: List<UsageEvent>, referenceDayStartMs: Long = todayStart): List<TrendDay> {
        val result = mutableListOf<TrendDay>()

        for (i in 6 downTo 0) {
            val dayStart = referenceDayStartMs - i * DAY_MS
            val dayEnd = dayStart + DAY_MS
            val dayEvents = weekEvents.filter { it.timestamp in dayStart until dayEnd }

            val blockedCount = dayEvents.count { it.wasBlocked }
            val walkedAwayCount = dayEvents.count { it.userChangedMind }
            val label = getDayLabel(dayStart)

            result.add(TrendDay(label = label, blockedCount = blockedCount, walkedAwayCount = walkedAwayCount))
        }

        return result
    }

    fun buildHourlyData(todayEvents: List<UsageEvent>): List<Long> {
        val hourlyMs = MutableList(24) { 0L }

        todayEvents.forEach { event ->
            val cal = Calendar.getInstance(TimeZone.getDefault())
            cal.timeInMillis = event.timestamp
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            hourlyMs[hour] += event.durationMs
        }

        return hourlyMs
    }

    /**
     * Build trend data (blocked + walked away) for a specific app package.
     * Filters weekEvents by packageName before computing per-day counts.
     */
    fun buildAppTrendData(
        weekEvents: List<UsageEvent>,
        packageName: String,
        referenceDayStartMs: Long = todayStart
    ): List<TrendDay> {
        val result = mutableListOf<TrendDay>()
        for (i in 6 downTo 0) {
            val dayStart = referenceDayStartMs - i * DAY_MS
            val dayEnd = dayStart + DAY_MS
            val dayEvents = weekEvents
                .filter { it.packageName == packageName && it.timestamp in dayStart until dayEnd }
            val blockedCount = dayEvents.count { it.wasBlocked }
            val walkedAwayCount = dayEvents.count { it.userChangedMind }
            val label = getDayLabel(dayStart)
            result.add(TrendDay(label = label, blockedCount = blockedCount, walkedAwayCount = walkedAwayCount))
        }
        return result
    }

    /**
     * Calculate streak: consecutive days (ending at reference day or the day before) where
     * the user had at least one nudge interaction (blocked or walked away).
     * If the reference day has no events at all, it's skipped (user hasn't used phone yet).
     */
    fun calculateStreak(weekEvents: List<UsageEvent>, referenceDayStartMs: Long = todayStart): Int {
        var streak = 0

        for (i in 0..6) {
            val dayStart = referenceDayStartMs - i * DAY_MS
            val dayEnd = dayStart + DAY_MS
            val dayEvents = weekEvents.filter { it.timestamp in dayStart until dayEnd }

            val hadWalkedAway = dayEvents.any { it.userChangedMind }
            val hadBlocked = dayEvents.any { it.wasBlocked }

            if (hadWalkedAway || hadBlocked) {
                streak++
            } else if (i == 0 && dayEvents.isEmpty()) {
                continue
            } else {
                break
            }
        }

        return streak
    }

    private fun getDayLabel(dayStartMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = dayStartMs
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            Calendar.SUNDAY -> "Sun"
            else -> ""
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
