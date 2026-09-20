package com.astraedus.nudge.ui.screens.stats

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.isShownConfrontation
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
 * Methods that take a `referenceDayStartMs` use that as the "anchor" day
 * (the last day of a 7-day window). When omitted, defaults to today.
 *
 * Day boundaries are walked with [TimeTracker.startOfDayDaysBefore] (calendar arithmetic), never
 * `i * 86_400_000`: every series here is drawn beside the screen-time bars, which are bucketed by
 * true local midnight, and across a DST transition raw subtraction would slide these labels and
 * counts an hour off — one chart's "Wed" covering a different 24 hours than its neighbour's.
 */
class StatsCalculator @Inject constructor(
    private val timeTracker: TimeTracker
) {

    private val todayStart: Long get() = timeTracker.startOfToday()

    /**
     * Build weekly bar chart data from pre-computed daily totals (from UsageStatsManager).
     * @param dailyTotals list of 7 entries, index 0 = 6 days ago, index 6 = reference day.
     * @param referenceDayStartMs the anchor day (for day labels). Defaults to today.
     */
    fun buildWeeklyDataFromTotals(dailyTotals: List<Long>, referenceDayStartMs: Long = todayStart): List<DayData> {
        val result = mutableListOf<DayData>()

        for (i in 6 downTo 0) {
            val dayStart = timeTracker.startOfDayDaysBefore(referenceDayStartMs, i)
            val label = getDayLabel(dayStart)
            val totalMs = dailyTotals.getOrElse(6 - i) { 0L }
            result.add(DayData(label = label, totalMs = totalMs))
        }

        return result
    }

    fun buildTrendData(weekEvents: List<UsageEvent>, referenceDayStartMs: Long = todayStart): List<TrendDay> {
        val result = mutableListOf<TrendDay>()

        for (i in 6 downTo 0) {
            val dayStart = timeTracker.startOfDayDaysBefore(referenceDayStartMs, i)
            val dayEnd = timeTracker.startOfDayDaysBefore(referenceDayStartMs, i - 1)
            val dayEvents = weekEvents.filter { it.timestamp in dayStart until dayEnd }

            // A walk-away writes a SECOND `wasBlocked` row for the same confrontation. Counting
            // that column raw drew every walk-away in BOTH series at once: one overlay the user
            // turned away from once was worth "blocked 2, walked away 1" on the same bar.
            val blockedCount = dayEvents.count { it.isShownConfrontation }
            val walkedAwayCount = dayEvents.count { it.userChangedMind }
            val label = getDayLabel(dayStart)

            result.add(TrendDay(label = label, blockedCount = blockedCount, walkedAwayCount = walkedAwayCount))
        }

        return result
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
            val dayStart = timeTracker.startOfDayDaysBefore(referenceDayStartMs, i)
            val dayEnd = timeTracker.startOfDayDaysBefore(referenceDayStartMs, i - 1)
            val dayEvents = weekEvents
                .filter { it.packageName == packageName && it.timestamp in dayStart until dayEnd }
            val blockedCount = dayEvents.count { it.isShownConfrontation }
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
            val dayStart = timeTracker.startOfDayDaysBefore(referenceDayStartMs, i)
            val dayEnd = timeTracker.startOfDayDaysBefore(referenceDayStartMs, i - 1)
            val dayEvents = weekEvents.filter { it.timestamp in dayStart until dayEnd }

            val hadWalkedAway = dayEvents.any { it.userChangedMind }
            val hadBlocked = dayEvents.any { it.isShownConfrontation }

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

    /**
     * The day a bar is labelled with — and, everywhere a bar also carries a count, the bucket
     * that count was gathered into.
     */
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

}
