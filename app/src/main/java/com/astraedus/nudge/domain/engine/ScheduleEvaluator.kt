package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockRuleData
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
    fun isActiveAt(rule: ActiveRule, now: Calendar): Boolean =
        isScheduledAt(rule.scheduleDays, rule.scheduleStartMinute, rule.scheduleEndMinute, now)

    /**
     * The same question for a rule that has not been resolved for a package yet. Used by
     * [WholeAppCoverage], which must know whether a GROUP rule is in its schedule before any
     * package-specific resolution happens. One schedule semantics, two input shapes.
     */
    fun isActiveNow(rule: BlockRuleData): Boolean = isActiveAt(rule, Calendar.getInstance())

    /** Testable overload of [isActiveNow] for a [BlockRuleData]. */
    fun isActiveAt(rule: BlockRuleData, now: Calendar): Boolean =
        isScheduledAt(rule.scheduleDays, rule.scheduleStartMinute, rule.scheduleEndMinute, now)

    /**
     * When the window [rule] is in right now ends, as epoch ms: the block screen's "Until 8:42 AM"
     * (spec 3.9). Null when the rule has no schedule ("All the time" shows no "Until"), when it is
     * not in its window at [now], or when the window does not end within a week (every day, all day).
     *
     * Walks forward minute by minute over the SAME day/time test [isActiveAt] uses, so the answer
     * can never disagree with the schedule: an overnight window, back-to-back days ("Mon-Fri, all
     * day" on a Tuesday ends Saturday 00:00) and a days-only rule all fall out of it. At most one
     * week of minutes, pure integer arithmetic, and only asked once a rule has decided a block.
     */
    fun windowEndAt(rule: ActiveRule, now: Calendar): Long? =
        windowEndAt(rule.scheduleDays, rule.scheduleStartMinute, rule.scheduleEndMinute, now)

    /** [windowEndAt] for a rule not yet resolved for a package (the Home lock card). */
    fun windowEndAt(rule: BlockRuleData, now: Calendar): Long? =
        windowEndAt(rule.scheduleDays, rule.scheduleStartMinute, rule.scheduleEndMinute, now)

    private fun windowEndAt(days: List<Int>?, startMinute: Int?, endMinute: Int?, now: Calendar): Long? {
        if (days == null && startMinute == null && endMinute == null) return null
        if (!isScheduledAt(days, startMinute, endMinute, now)) return null
        val dayIso = calendarDayToIso(now.get(Calendar.DAY_OF_WEEK))
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        for (step in 1..MINUTES_PER_WEEK) {
            val total = minute + step
            val stepDay = ((dayIso - 1 + total / MINUTES_PER_DAY) % 7) + 1
            if (!isScheduledAt(days, startMinute, endMinute, stepDay, total % MINUTES_PER_DAY)) {
                val end = now.clone() as Calendar
                end.set(Calendar.SECOND, 0)
                end.set(Calendar.MILLISECOND, 0)
                end.add(Calendar.MINUTE, step)
                return end.timeInMillis
            }
        }
        return null
    }

    private fun isScheduledAt(
        days: List<Int>?,
        startMinute: Int?,
        endMinute: Int?,
        now: Calendar
    ): Boolean = isScheduledAt(
        days,
        startMinute,
        endMinute,
        dayIso = calendarDayToIso(now.get(Calendar.DAY_OF_WEEK)),
        minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    )

    private fun isScheduledAt(
        days: List<Int>?,
        startMinute: Int?,
        endMinute: Int?,
        dayIso: Int,
        minuteOfDay: Int
    ): Boolean {
        // No schedule configured -- always active
        if (days == null && startMinute == null && endMinute == null) return true

        val currentDayIso = dayIso

        // Check day-of-week constraint
        if (days != null && days.isNotEmpty() && currentDayIso !in days) return false

        // Check time-of-day constraint
        if (startMinute != null && endMinute != null) {
            val currentMinute = minuteOfDay
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
        private const val MINUTES_PER_DAY = 24 * 60
        private const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY

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
