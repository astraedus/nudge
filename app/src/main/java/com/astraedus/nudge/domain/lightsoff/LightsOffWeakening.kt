package com.astraedus.nudge.domain.lightsoff

import com.astraedus.nudge.domain.lock.BlockModeStrength

/**
 * Pure comparison of two Lights Off configurations to decide whether an edit WEAKENS the lockdown.
 * Under Strict Mode (the Commitment Lock) weakening edits are gated behind the unlock challenge;
 * strengthening edits save freely — the same asymmetry as
 * [com.astraedus.nudge.domain.lock.RuleWeakening], which this deliberately does NOT reuse because
 * that one is `BlockRule`-shaped.
 *
 * Without this gate the Commitment Lock would be theater: a user mid-lockdown could simply add the
 * app they crave to the whitelist, or drag the end time back an hour, and walk straight through.
 *
 * ## Weakening (GATED)
 *  - turning Lights Off off
 *  - shrinking WHEN the lockdown applies — fewer days, later start, earlier end, or the schedule
 *    switched off entirely
 *  - adding a whitelist entry (one more app stays awake)
 *  - softening the block mode
 *
 * ## Not weakening (FREE)
 *  - turning Lights Off on
 *  - lengthening the window / adding days
 *  - removing a whitelist entry
 *  - hardening the block mode
 *  - anything at all while Lights Off is already OFF (there is no live protection to weaken, and
 *    turning it off was itself gated)
 *
 * The schedule comparison is done on the actual set of covered week-minutes rather than by comparing
 * start/end/day fields one at a time. That closes two holes a field-wise check has: flipping an
 * overnight window (22:00→07:00, 9h) into a same-day one (22:00→23:00, 1h) RAISES the end minute
 * while gutting the window, and sliding a window later in the day (22:00–07:00 → 02:00–11:00) keeps
 * its length while abandoning the evening. Both are protection removed, so both are gated.
 */
object LightsOffWeakening {

    private const val MINUTES_PER_DAY = LightsOffProfile.MINUTES_PER_DAY
    private const val WEEK_MINUTES = 7 * MINUTES_PER_DAY

    fun isWeakening(old: LightsOffSettings, new: LightsOffSettings): Boolean {
        // Turning the whole feature off is the headline weakening action.
        if (old.enabled && !new.enabled) return true

        // Lights Off was already off: nothing is currently protected, so no edit can remove
        // protection. (This is not a bypass — reaching the off state required passing the challenge.)
        if (!old.enabled) return false

        // Any moment that used to be locked down and no longer is.
        if (!coversAll(old = coveredWeekMinutes(old.profile), new = coveredWeekMinutes(new.profile))) {
            return true
        }

        // One more app allowed through the lockdown.
        if ((new.profile.whitelist.toSet() - old.profile.whitelist.toSet()).isNotEmpty()) return true

        // Softer enforcement for non-whitelisted apps.
        if (BlockModeStrength.isSoftened(old.profile.mode, new.profile.mode)) return true

        return false
    }

    /**
     * Every week-minute (index 0 = Monday 00:00 … 10079 = Sunday 23:59) during which [profile]'s
     * schedule would put the lights out.
     *
     * Mirrors [com.astraedus.nudge.domain.engine.ScheduleEvaluator] exactly, including its overnight
     * semantics: an overnight window on selected days is active during the LATE and the EARLY hours
     * of each selected day (the evaluator applies the day filter to the current day), not spilling
     * into the following day.
     */
    internal fun coveredWeekMinutes(profile: LightsOffProfile): BooleanArray {
        val covered = BooleanArray(WEEK_MINUTES)
        if (!profile.scheduleEnabled) return covered

        // An empty day list means "every day" — same as an unset schedule day list on a block rule.
        val days = profile.days.filter { it in 1..7 }.ifEmpty { (1..7).toList() }
        val start = profile.startMinute.coerceIn(0, MINUTES_PER_DAY)
        val end = profile.endMinute.coerceIn(0, MINUTES_PER_DAY)

        for (day in days) {
            val dayStart = (day - 1) * MINUTES_PER_DAY
            if (end >= start) {
                // Same-day window; start == end covers nothing (matches the evaluator's `until`).
                for (m in start until end) covered[dayStart + m] = true
            } else {
                // Overnight window: late evening plus the early hours of the same selected day.
                for (m in start until MINUTES_PER_DAY) covered[dayStart + m] = true
                for (m in 0 until end) covered[dayStart + m] = true
            }
        }
        return covered
    }

    /** True when [new] still covers every minute [old] covered (it may cover more). */
    private fun coversAll(old: BooleanArray, new: BooleanArray): Boolean {
        for (i in old.indices) {
            if (old[i] && !new[i]) return false
        }
        return true
    }
}
