package com.astraedus.nudge.domain.lock

import java.util.BitSet

/**
 * A rule's schedule as the set of minutes of one week it is active in: bit
 * `(dayIso - 1) * 1440 + minuteOfDay`, Monday 00:00 = bit 0.
 *
 * Strict Mode needs "is the new window a SUPERSET of the old one?" for an All apps rule (a shorter
 * window is fewer minutes of blocking), and two schedules can only be compared that way once both
 * are spelled out minute by minute: "Mon-Fri 22:00-06:00" and "every day 23:00-05:00" overlap in a
 * shape no field-by-field comparison gets right.
 *
 * Same semantics as [com.astraedus.nudge.domain.engine.ScheduleEvaluator] (pinned against it by
 * `ScheduleWeekTest`): no schedule at all = every minute; null or empty days = every day; start and
 * end both set = `[start, end)`, an overnight window (end < start) covering `start..` and `..end`
 * of the SAME listed day; otherwise all day. Days are the stored CSV, 1 = Monday .. 7 = Sunday.
 *
 * No Android imports -- unit-testable on the JVM.
 */
object ScheduleWeek {

    const val MINUTES_PER_DAY = 24 * 60
    const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY

    /** The minutes of the week a rule with this schedule is active in. */
    fun activeMinutes(scheduleDays: String?, startMinute: Int?, endMinute: Int?): BitSet {
        val days = scheduleDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
        val week = BitSet(MINUTES_PER_WEEK)
        for (dayIso in 1..7) {
            for (minute in 0 until MINUTES_PER_DAY) {
                if (isActive(days, startMinute, endMinute, dayIso, minute)) {
                    week.set((dayIso - 1) * MINUTES_PER_DAY + minute)
                }
            }
        }
        return week
    }

    /** True when some minute [old] was active in is not active in [new]: the window got shorter. */
    fun isShortened(old: BitSet, new: BitSet): Boolean {
        val lost = old.clone() as BitSet
        lost.andNot(new)
        return !lost.isEmpty
    }

    private fun isActive(
        days: List<Int>?,
        startMinute: Int?,
        endMinute: Int?,
        dayIso: Int,
        minute: Int
    ): Boolean {
        if (days == null && startMinute == null && endMinute == null) return true
        if (!days.isNullOrEmpty() && dayIso !in days) return false
        if (startMinute != null && endMinute != null) {
            return if (startMinute <= endMinute) minute in startMinute until endMinute
            else minute >= startMinute || minute < endMinute
        }
        return true
    }
}
