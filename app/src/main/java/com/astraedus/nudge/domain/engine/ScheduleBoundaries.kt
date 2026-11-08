package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockRuleData
import java.util.Calendar
import javax.inject.Inject

/**
 * The next instant at which ANY enabled rule's answer can change because of the clock alone: the
 * one moment the accessibility service must look at the app in front again (#169, #170).
 *
 * The boundaries, per enabled rule:
 *  - **a days-and-times window starts** ([ScheduleEvaluator.nextStartAt]): the app in front may now
 *    be covered (#169);
 *  - **the window it is in ends** ([ScheduleEvaluator.windowEndAt]), first-unlock windows
 *    included: a block may be over (#170);
 *  - **the next local midnight, for a rule with a daily limit**: a spent limit stops blocking when
 *    the day's usage starts again from zero, even on a rule that is on all the time and so has no
 *    window to end (#170's daily-limit case). See [ScheduleWalk.nextLocalMidnight].
 *
 * What is NOT a boundary, on purpose:
 *  - **a first-unlock window's start.** It opens at an unlock, an event the service already reacts
 *    to.
 *  - **a daily limit running OUT.** That depends on usage, not the clock. The per-app foreground
 *    clock watches it while that app is in front (v1.18.4).
 *
 * A set of rules with nothing on a clock (all the time, no limit) answers null, and the service then
 * posts no timer at all. That is #63's rule: a rule that needs no clock has none.
 *
 * Pure: the rules and the instant are parameters, and the zone is [now]'s.
 */
class ScheduleBoundaries @Inject constructor(
    private val scheduleEvaluator: ScheduleEvaluator
) {

    /** The earliest boundary strictly after [now], over every enabled rule in [rules], or null. */
    fun next(rules: List<BlockRuleData>, now: Calendar): Long? {
        val nowMs = now.timeInMillis
        val enabled = rules.filter { it.enabled }
        val ruleBoundaries = enabled.flatMap { rule ->
            listOfNotNull(
                scheduleEvaluator.nextStartAt(rule, now),
                scheduleEvaluator.windowEndAt(rule, now)
            )
        }
        val rollover = if (enabled.any { it.dailyLimitMinutes != null }) {
            ScheduleWalk.nextLocalMidnight(nowMs, now.timeZone.toZoneId())
        } else {
            null
        }
        return (ruleBoundaries + listOfNotNull(rollover)).filter { it > nowMs }.minOrNull()
    }
}
