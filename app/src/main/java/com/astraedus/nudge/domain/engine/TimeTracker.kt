package com.astraedus.nudge.domain.engine

import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

class TimeTracker @Inject constructor() {

    companion object {
        /** Days in a trailing-week window. Matches `ScreenTimeProvider.WEEK_DAYS` by construction. */
        const val WEEK_DAYS = 7
    }


    /** Returns epoch millis for midnight today in the default timezone. */
    fun startOfToday(): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Returns epoch millis for midnight [days] days before the day starting at [dayStartMs].
     * Calendar day arithmetic, so the result is true local midnight even when a DST
     * transition falls inside the window (raw `days * 86_400_000` subtraction is off by
     * an hour across a transition).
     */
    fun startOfDayDaysBefore(dayStartMs: Long, days: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = dayStartMs
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return cal.timeInMillis
    }

    /**
     * Local midnight that starts the trailing week ending on the day at [dayStartMs].
     *
     * The ONE definition of "a week ago" for every trailing-week read in the app: the home
     * dashboard's events query and its "Blocked most this week" card, and the Top-blocked widget.
     * All three answer the same question on two different surfaces, and this package's recurring
     * defect is two spellings of one boundary drifting apart, so there is one spelling.
     *
     * `WEEK_DAYS - 1` because a 7-day window means today plus the six days before it, not the last
     * 168 hours, and the calendar arithmetic above is what keeps a DST day 23 or 25 hours wide.
     */
    fun startOfTrailingWeek(dayStartMs: Long): Long =
        startOfDayDaysBefore(dayStartMs, WEEK_DAYS - 1)

    /** Returns true if usage exceeds the given limit. */
    fun hasExceededLimit(usageMs: Long, limitMinutes: Int): Boolean {
        return usageMs >= limitMinutes.toLong() * 60L * 1000L
    }

    /** Returns remaining time in ms, or 0 if the limit is already exceeded. */
    fun remainingMs(usageMs: Long, limitMinutes: Int): Long {
        val limitMs = limitMinutes.toLong() * 60L * 1000L
        val remaining = limitMs - usageMs
        return if (remaining > 0) remaining else 0L
    }

    /** Formats a duration in ms to a human-readable string like "2h 5m" or "45m" or "30s". */
    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> {
                if (seconds > 0) "${minutes}m ${seconds}s"
                else "${minutes}m"
            }
            else -> "${seconds}s"
        }
    }
}
