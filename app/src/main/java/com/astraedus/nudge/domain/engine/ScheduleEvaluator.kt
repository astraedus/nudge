package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import java.util.Calendar
import javax.inject.Inject

/**
 * Determines whether a scheduled rule is currently active based on day-of-week and time-of-day.
 *
 * Day numbering: 1=Monday .. 7=Sunday (ISO 8601).
 * Time is expressed as minutes from midnight (0..1439).
 * Overnight schedules (end < start) are supported -- e.g. 23:00-06:00.
 */
class ScheduleEvaluator @Inject constructor() {

    /**
     * Returns true if the rule should be active right now.
     * If no schedule fields are set (all null), the rule is always active.
     */
    fun isActiveNow(rule: ActiveRule): Boolean {
        return isActiveAt(rule, Calendar.getInstance())
    }

    /**
     * Testable overload that accepts an explicit [Calendar] for the current moment.
     */
    fun isActiveAt(rule: ActiveRule, now: Calendar): Boolean {
        val days = rule.scheduleDays
        val startMinute = rule.scheduleStartMinute
        val endMinute = rule.scheduleEndMinute

        // No schedule configured -- always active
        if (days == null && startMinute == null && endMinute == null) return true

        // Convert Calendar.DAY_OF_WEEK (Sun=1..Sat=7) to ISO (Mon=1..Sun=7)
        val currentDayIso = calendarDayToIso(now.get(Calendar.DAY_OF_WEEK))

        // Check day-of-week constraint
        if (days != null && days.isNotEmpty() && currentDayIso !in days) return false

        // Check time-of-day constraint
        if (startMinute != null && endMinute != null) {
            val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            return if (startMinute <= endMinute) {
                // Normal range: e.g. 09:00 - 17:00
                currentMinute in startMinute until endMinute
            } else {
                // Overnight range: e.g. 23:00 - 06:00
                currentMinute >= startMinute || currentMinute < endMinute
            }
        }

        // Only days set (no time range) -- active all day on those days
        return true
    }

    companion object {
        /**
         * Convert [Calendar.DAY_OF_WEEK] (Sunday=1 .. Saturday=7)
         * to ISO 8601 (Monday=1 .. Sunday=7).
         */
        fun calendarDayToIso(calendarDay: Int): Int = when (calendarDay) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> calendarDay
        }
    }
}
