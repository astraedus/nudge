package com.astraedus.nudge.domain.engine

import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

class TimeTracker @Inject constructor() {

    /** Returns epoch millis for midnight today in the default timezone. */
    fun startOfToday(): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

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
