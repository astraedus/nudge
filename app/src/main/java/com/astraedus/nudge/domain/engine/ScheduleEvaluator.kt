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
class ScheduleEvaluator @Inject constructor(
    private val firstUnlock: FirstUnlockState,
) {

    /**
     * Returns true if the rule should be active right now.
     * If no schedule fields are set (all null), the rule is always active.
     */
    fun isActiveNow(rule: ActiveRule): Boolean {
        return isActiveAt(rule, Calendar.getInstance())
    }

    /**
     * Testable overload that accepts an explicit [Calendar] for the current moment.
     *
     * A rule with [ActiveRule.firstUnlockMinutes] is delegated to [FirstUnlockWindow] and its
     * days-and-times fields are ignored: "When" is one choice, not two. Every other rule keeps the
     * exact schedule semantics it had.
     */
    fun isActiveAt(rule: ActiveRule, now: Calendar): Boolean {
        val minutes = rule.firstUnlockMinutes
        if (minutes != null) {
            return firstUnlockActive(minutes, rule.scheduleDays, rule.armedAt, now)
        }
        return isScheduledAt(rule.scheduleDays, rule.scheduleStartMinute, rule.scheduleEndMinute, now)
    }

    /**
     * The same question for a rule that has not been resolved for a package yet. Used by
     * [WholeAppCoverage], which must know whether a GROUP rule is in its schedule before any
     * package-specific resolution happens. One schedule semantics, two input shapes.
     */
    fun isActiveNow(rule: BlockRuleData): Boolean = isActiveAt(rule, Calendar.getInstance())

    /** Testable overload of [isActiveNow] for a [BlockRuleData]. Same first-unlock branch. */
    fun isActiveAt(rule: BlockRuleData, now: Calendar): Boolean {
        val minutes = rule.firstUnlockMinutes
        if (minutes != null) {
            return firstUnlockActive(minutes, rule.scheduleDays, rule.armedAt, now)
        }
        return isScheduledAt(rule.scheduleDays, rule.scheduleStartMinute, rule.scheduleEndMinute, now)
    }

    /**
     * When the window [rule] is in right now ends, as epoch ms: the block screen's "Until 8:42 AM"
     * (spec 3.9). Null when the rule has no schedule ("All the time" shows no "Until"), when it is
     * not in its window at [now], or when the window does not end within a week (every day, all day).
     *
     * Walks forward minute by minute over the SAME day/time test [isActiveAt] uses, so the answer
     * can never disagree with the schedule: an overnight window, back-to-back days ("Mon-Fri, all
     * day" on a Tuesday ends Saturday 00:00) and a days-only rule all fall out of it. The walk is
     * over absolute minutes read in [now]'s zone, so a DST change in the window moves the end by
     * exactly what the wall clock says ([ScheduleWalk]).
     */
    fun windowEndAt(rule: ActiveRule, now: Calendar): Long? {
        val minutes = rule.firstUnlockMinutes
        if (minutes != null) return firstUnlockEnd(minutes, rule.scheduleDays, rule.armedAt, now)
        return windowEndAt(rule.scheduleDays, rule.scheduleStartMinute, rule.scheduleEndMinute, now)
    }

    /** [windowEndAt] for a rule not yet resolved for a package (the Home lock card). */
    fun windowEndAt(rule: BlockRuleData, now: Calendar): Long? {
        val minutes = rule.firstUnlockMinutes
        if (minutes != null) return firstUnlockEnd(minutes, rule.scheduleDays, rule.armedAt, now)
        return windowEndAt(rule.scheduleDays, rule.scheduleStartMinute, rule.scheduleEndMinute, now)
    }

    /**
     * When [rule]'s days-and-times window next STARTS, as epoch ms strictly after [now]: the
     * boundary at which an app already in front must be looked at again (#169). For a rule in its
     * window at [now], the start of the NEXT window.
     *
     * Null when no clock can start it:
     *  - no schedule: the rule is on all the time, so it never "starts";
     *  - every day, all day: it never stops, so it never starts either;
     *  - a first-unlock rule: its window opens at an UNLOCK, which is an event, not a time. The
     *    service re-evaluates the app in front when the unlock stamp moves
     *    (`reevaluateForegroundForFirstUnlock`). Its END is a time, and [windowEndAt] answers it.
     *
     * Same walk and same day/time test as [windowEndAt] ([ScheduleWalk]), so a start can never
     * disagree with what [isActiveAt] reads at that instant, across midnight, a days-of-week edge or
     * a DST change.
     */
    fun nextStartAt(rule: BlockRuleData, now: Calendar): Long? {
        if (rule.firstUnlockMinutes != null) return null
        return ScheduleWalk.nextStart(
            rule.scheduleDays,
            rule.scheduleStartMinute,
            rule.scheduleEndMinute,
            now.timeInMillis,
            now.timeZone.toZoneId()
        )
    }

    /**
     * The first-unlock branch, in one place: the anchor is the evaluator's, the window the rule's.
     * [ActiveRule] and [BlockRuleData] carry the same three fields, and duplicating the call twice
     * (then twice again for the end) was four chances to pass one of them wrong.
     */
    private fun firstUnlockActive(
        minutes: Int,
        days: List<Int>?,
        armedAt: Long,
        now: Calendar
    ): Boolean = FirstUnlockWindow.isActive(
        nowMs = now.timeInMillis,
        stampMs = firstUnlock.firstUnlockAt(),
        dayStartMinute = firstUnlock.dayStartMinute(),
        minutes = minutes,
        days = days,
        armedAt = armedAt,
        zone = firstUnlock.zoneId()
    )

    /**
     * The first-unlock window's end for the block screen's "Until 8:42 AM". The day boundary can end
     * it before the rule's own minutes do, and [FirstUnlockWindow.endsAt] returns whichever is first.
     */
    private fun firstUnlockEnd(
        minutes: Int,
        days: List<Int>?,
        armedAt: Long,
        now: Calendar
    ): Long? = FirstUnlockWindow.endsAt(
        nowMs = now.timeInMillis,
        stampMs = firstUnlock.firstUnlockAt(),
        dayStartMinute = firstUnlock.dayStartMinute(),
        minutes = minutes,
        days = days,
        armedAt = armedAt,
        zone = firstUnlock.zoneId()
    )

    private fun windowEndAt(days: List<Int>?, startMinute: Int?, endMinute: Int?, now: Calendar): Long? =
        ScheduleWalk.windowEnd(days, startMinute, endMinute, now.timeInMillis, now.timeZone.toZoneId())

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

    companion object {
        /**
         * THE days-and-times test, over a local ISO day and minute of day. One semantics for
         * [isActiveAt] and for [ScheduleWalk], which asks it about future minutes.
         */
        internal fun isScheduledAt(
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
