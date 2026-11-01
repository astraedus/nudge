package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * L1: [ScheduleEvaluator]'s [BlockRuleData] overload is the SAME schedule as its [ActiveRule] one.
 * Whole-app coverage (spec 5.5) asks the schedule before any resolution, and a second schedule
 * semantics would let "Chrome is covered" and "Chrome is blocked" disagree at the edges. Checked over
 * every day and every hour for a spread of schedule shapes, including overnight and days-only.
 */
class ScheduleEvaluatorRuleDataTest {

    private val evaluator = ScheduleEvaluator()

    private data class Shape(val days: List<Int>?, val start: Int?, val end: Int?)

    private val shapes = listOf(
        Shape(null, null, null),
        Shape(listOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60),
        Shape(null, 23 * 60, 6 * 60),
        Shape(listOf(6, 7), null, null),
        Shape(emptyList(), 8 * 60, 8 * 60 + 30),
        Shape(listOf(3), 22 * 60, 2 * 60)
    )

    @Test
    fun `both overloads agree on every day and hour`() {
        for (shape in shapes) {
            val data = BlockRuleData(
                id = 1, packageName = null, groupId = 1, mode = BlockMode.DELAY, delaySeconds = 10,
                dailyLimitMinutes = null, enabled = true, scheduleDays = shape.days,
                scheduleStartMinute = shape.start, scheduleEndMinute = shape.end
            )
            val active = RuleEvaluator()
                .resolveRulesForPackage("pkg", listOf(data), listOf(
                    com.astraedus.nudge.domain.model.GroupMembership(1, "pkg")
                ))
                .single()
            for (day in Calendar.SUNDAY..Calendar.SATURDAY) {
                for (hour in 0..23) {
                    val now = Calendar.getInstance().apply {
                        set(Calendar.DAY_OF_WEEK, day)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, 15)
                    }
                    assertEquals(
                        "$shape day=$day hour=$hour",
                        evaluator.isActiveAt(active, now),
                        evaluator.isActiveAt(data, now)
                    )
                }
            }
        }
    }
}
