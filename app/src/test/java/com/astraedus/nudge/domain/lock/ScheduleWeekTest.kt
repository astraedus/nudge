package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import com.astraedus.nudge.testing.aBlockRuleData

/**
 * L1. [ScheduleWeek] is Strict Mode's own copy of "when is this rule active", so it is pinned
 * minute by minute against the engine's [ScheduleEvaluator] over a whole week for every shape of
 * schedule: if the two ever drift, Strict Mode would judge a window edit by a schedule the engine
 * does not enforce.
 */
class ScheduleWeekTest {

    /** Every schedule shape: none, days only, normal / overnight / empty / full-day ranges, odd CSVs. */
    private val schedules: List<Triple<String?, Int?, Int?>> = listOf(
        Triple(null, null, null),
        Triple("1,2,3,4,5", null, null),
        Triple("6,7", null, null),
        Triple("", null, null),
        Triple(" 3 , x,", null, null),
        Triple(null, 540, 1020),
        Triple("1,3,5", 540, 1020),
        Triple(null, 1320, 360),
        Triple("5,6", 1320, 360),
        Triple("7", 0, 1440),
        Triple("2", 600, 600),
        Triple(null, 0, 0),
        Triple("1", 1439, 0),
        Triple("4", 540, null),
        Triple(null, null, 1020)
    )

    @Test
    fun `active minutes match the engine's ScheduleEvaluator for every schedule shape`() {
        val evaluator = ScheduleEvaluator(FirstUnlockState.NONE)
        for ((days, start, end) in schedules) {
            val week = ScheduleWeek.activeMinutes(days, start, end)
            val rule = aBlockRuleData(
                id = 1, packageName = null, groupId = null, mode = BlockMode.HARD_BLOCK,
                delaySeconds = 0, dailyLimitMinutes = null, enabled = true,
                scheduleDays = days?.split(",")?.mapNotNull { it.trim().toIntOrNull() },
                scheduleStartMinute = start, scheduleEndMinute = end
            )
            val now = mondayMidnightUtc()
            for (minute in 0 until ScheduleWeek.MINUTES_PER_WEEK) {
                assertEquals(
                    "schedule ($days, $start, $end) at minute $minute",
                    evaluator.isActiveAt(rule, now),
                    week.get(minute)
                )
                now.add(Calendar.MINUTE, 1)
            }
        }
    }

    @Test
    fun `no schedule is every minute of the week`() {
        assertEquals(ScheduleWeek.MINUTES_PER_WEEK, ScheduleWeek.activeMinutes(null, null, null).cardinality())
    }

    @Test
    fun `an overnight window covers the end of each listed day and the start of the same day`() {
        // Friday (5) 22:00-06:00: Friday 00:00-06:00 and Friday 22:00-24:00, not Saturday morning.
        val week = ScheduleWeek.activeMinutes("5", 1320, 360)
        val friday = 4 * ScheduleWeek.MINUTES_PER_DAY
        assertTrue(week.get(friday + 0))
        assertTrue(week.get(friday + 1439))
        assertFalse(week.get(friday + 360))
        assertFalse(week.get(friday + ScheduleWeek.MINUTES_PER_DAY))
        assertEquals(8 * 60, week.cardinality())
    }

    @Test
    fun `isShortened is true only when an old minute is lost`() {
        val nineToFive = ScheduleWeek.activeMinutes(null, 540, 1020)
        val eightToSix = ScheduleWeek.activeMinutes(null, 480, 1080)
        assertFalse(ScheduleWeek.isShortened(nineToFive, eightToSix))
        assertTrue(ScheduleWeek.isShortened(eightToSix, nineToFive))
        assertFalse(ScheduleWeek.isShortened(nineToFive, nineToFive))
    }

    private fun mondayMidnightUtc(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2024, Calendar.JANUARY, 1, 0, 0, 0) // a Monday
        }
}
