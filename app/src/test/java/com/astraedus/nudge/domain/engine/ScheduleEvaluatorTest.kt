package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class ScheduleEvaluatorTest {

    private lateinit var evaluator: ScheduleEvaluator

    @Before
    fun setUp() {
        evaluator = ScheduleEvaluator()
    }

    private fun ruleWithSchedule(
        days: List<Int>? = null,
        startMinute: Int? = null,
        endMinute: Int? = null
    ): ActiveRule = ActiveRule(
        mode = BlockMode.HARD_BLOCK,
        delaySeconds = 0,
        dailyLimitMinutes = null,
        enabled = true,
        scheduleDays = days,
        scheduleStartMinute = startMinute,
        scheduleEndMinute = endMinute
    )

    private fun calendarAt(dayOfWeek: Int, hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    // --- No schedule (all null) -> always active ---

    @Test
    fun `no schedule fields means rule is always active`() {
        val rule = ruleWithSchedule()
        val monday9am = calendarAt(Calendar.MONDAY, 9, 0)
        assertTrue(evaluator.isActiveAt(rule, monday9am))
    }

    @Test
    fun `no schedule fields means active on weekend too`() {
        val rule = ruleWithSchedule()
        val saturday = calendarAt(Calendar.SATURDAY, 23, 59)
        assertTrue(evaluator.isActiveAt(rule, saturday))
    }

    // --- Weekday schedule, day checks ---

    @Test
    fun `weekday schedule active on Monday`() {
        val rule = ruleWithSchedule(days = listOf(1, 2, 3, 4, 5)) // Mon-Fri
        val monday10am = calendarAt(Calendar.MONDAY, 10, 0)
        assertTrue(evaluator.isActiveAt(rule, monday10am))
    }

    @Test
    fun `weekday schedule inactive on Saturday`() {
        val rule = ruleWithSchedule(days = listOf(1, 2, 3, 4, 5)) // Mon-Fri
        val saturday10am = calendarAt(Calendar.SATURDAY, 10, 0)
        assertFalse(evaluator.isActiveAt(rule, saturday10am))
    }

    @Test
    fun `weekday schedule inactive on Sunday`() {
        val rule = ruleWithSchedule(days = listOf(1, 2, 3, 4, 5)) // Mon-Fri
        val sunday = calendarAt(Calendar.SUNDAY, 14, 0)
        assertFalse(evaluator.isActiveAt(rule, sunday))
    }

    // --- Time range checks ---

    @Test
    fun `within time range is active`() {
        // 9:00 - 17:00
        val rule = ruleWithSchedule(startMinute = 540, endMinute = 1020)
        val at10am = calendarAt(Calendar.WEDNESDAY, 10, 0)
        assertTrue(evaluator.isActiveAt(rule, at10am))
    }

    @Test
    fun `outside time range is inactive`() {
        // 9:00 - 17:00
        val rule = ruleWithSchedule(startMinute = 540, endMinute = 1020)
        val at8am = calendarAt(Calendar.WEDNESDAY, 8, 0)
        assertFalse(evaluator.isActiveAt(rule, at8am))
    }

    @Test
    fun `at exact start time is active`() {
        val rule = ruleWithSchedule(startMinute = 540, endMinute = 1020)
        val at9am = calendarAt(Calendar.WEDNESDAY, 9, 0)
        assertTrue(evaluator.isActiveAt(rule, at9am))
    }

    @Test
    fun `at exact end time is inactive`() {
        // endMinute is exclusive
        val rule = ruleWithSchedule(startMinute = 540, endMinute = 1020)
        val at5pm = calendarAt(Calendar.WEDNESDAY, 17, 0)
        assertFalse(evaluator.isActiveAt(rule, at5pm))
    }

    @Test
    fun `after end time is inactive`() {
        val rule = ruleWithSchedule(startMinute = 540, endMinute = 1020)
        val at6pm = calendarAt(Calendar.WEDNESDAY, 18, 0)
        assertFalse(evaluator.isActiveAt(rule, at6pm))
    }

    // --- Overnight schedule (end < start) ---

    @Test
    fun `overnight schedule active at 23_30`() {
        // 23:00 - 06:00 (overnight)
        val rule = ruleWithSchedule(startMinute = 1380, endMinute = 360)
        val at2330 = calendarAt(Calendar.FRIDAY, 23, 30)
        assertTrue(evaluator.isActiveAt(rule, at2330))
    }

    @Test
    fun `overnight schedule active at 02_00`() {
        val rule = ruleWithSchedule(startMinute = 1380, endMinute = 360)
        val at2am = calendarAt(Calendar.FRIDAY, 2, 0)
        assertTrue(evaluator.isActiveAt(rule, at2am))
    }

    @Test
    fun `overnight schedule inactive at 12_00`() {
        val rule = ruleWithSchedule(startMinute = 1380, endMinute = 360)
        val atNoon = calendarAt(Calendar.FRIDAY, 12, 0)
        assertFalse(evaluator.isActiveAt(rule, atNoon))
    }

    @Test
    fun `overnight schedule inactive at 07_00`() {
        val rule = ruleWithSchedule(startMinute = 1380, endMinute = 360)
        val at7am = calendarAt(Calendar.FRIDAY, 7, 0)
        assertFalse(evaluator.isActiveAt(rule, at7am))
    }

    // --- Combined day + time ---

    @Test
    fun `weekday 9-5 active on Wednesday at 10am`() {
        val rule = ruleWithSchedule(
            days = listOf(1, 2, 3, 4, 5),
            startMinute = 540,
            endMinute = 1020
        )
        val wed10am = calendarAt(Calendar.WEDNESDAY, 10, 0)
        assertTrue(evaluator.isActiveAt(rule, wed10am))
    }

    @Test
    fun `weekday 9-5 inactive on Saturday at 10am`() {
        val rule = ruleWithSchedule(
            days = listOf(1, 2, 3, 4, 5),
            startMinute = 540,
            endMinute = 1020
        )
        val sat10am = calendarAt(Calendar.SATURDAY, 10, 0)
        assertFalse(evaluator.isActiveAt(rule, sat10am))
    }

    @Test
    fun `weekday 9-5 inactive on Monday at 8am`() {
        val rule = ruleWithSchedule(
            days = listOf(1, 2, 3, 4, 5),
            startMinute = 540,
            endMinute = 1020
        )
        val mon8am = calendarAt(Calendar.MONDAY, 8, 0)
        assertFalse(evaluator.isActiveAt(rule, mon8am))
    }

    // --- Edge: only days, no time ---

    @Test
    fun `only days set - active all day on matching day`() {
        val rule = ruleWithSchedule(days = listOf(6, 7)) // weekend only
        val satMidnight = calendarAt(Calendar.SATURDAY, 0, 0)
        assertTrue(evaluator.isActiveAt(rule, satMidnight))
    }

    @Test
    fun `only days set - inactive on non-matching day`() {
        val rule = ruleWithSchedule(days = listOf(6, 7)) // weekend only
        val monday = calendarAt(Calendar.MONDAY, 12, 0)
        assertFalse(evaluator.isActiveAt(rule, monday))
    }

    // --- Calendar day conversion ---

    @Test
    fun `calendarDayToIso converts Sunday correctly`() {
        // Calendar.SUNDAY = 1, ISO Sunday = 7
        val iso = ScheduleEvaluator.calendarDayToIso(Calendar.SUNDAY)
        assertTrue(iso == 7)
    }

    @Test
    fun `calendarDayToIso converts Monday correctly`() {
        // Calendar.MONDAY = 2, ISO Monday = 1
        val iso = ScheduleEvaluator.calendarDayToIso(Calendar.MONDAY)
        assertTrue(iso == 1)
    }
}
