package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * L1: [WholeAppCoverage], the question that decides whether a browser is evaluated as an app
 * (spec 5.5). Each condition is pinned on its own, so dropping one fails exactly one test.
 */
class WholeAppCoverageTest {

    private val chrome = "com.android.chrome"
    private val social = 7L
    private val always: (BlockRuleData) -> Boolean = { true }

    private val members = listOf(
        GroupMembership(social, chrome),
        GroupMembership(social, "com.instagram.android")
    )

    private fun groupRule(
        mode: BlockMode = BlockMode.HARD_BLOCK,
        groupId: Long? = social,
        enabled: Boolean = true,
        features: List<String>? = null,
        scheduleDays: List<Int>? = null,
        start: Int? = null,
        end: Int? = null
    ) = BlockRuleData(
        id = 1,
        packageName = null,
        groupId = groupId,
        mode = mode,
        delaySeconds = 15,
        dailyLimitMinutes = null,
        enabled = enabled,
        scheduleDays = scheduleDays,
        scheduleStartMinute = start,
        scheduleEndMinute = end,
        inAppFeatures = features
    )

    private fun covers(
        rules: List<BlockRuleData>,
        pkg: String = chrome,
        memberships: List<GroupMembership> = members,
        facts: CoverageFacts = CoverageFacts(essential = false, launchable = true),
        isInSchedule: (BlockRuleData) -> Boolean = always
    ) = WholeAppCoverage.coversNow(pkg, rules, memberships, facts, isInSchedule)

    @Test
    fun `an enabled whole-app group rule over a member covers it`() {
        assertTrue(covers(listOf(groupRule())))
    }

    @Test
    fun `every mode covers, including NONE`() {
        for (mode in BlockMode.values()) {
            assertTrue("mode $mode", covers(listOf(groupRule(mode = mode))))
        }
    }

    @Test
    fun `a disabled group rule does not cover`() {
        assertFalse(covers(listOf(groupRule(enabled = false))))
    }

    @Test
    fun `a DIRECT rule on the browser does not cover (F5)`() {
        val direct = groupRule(groupId = null).copy(packageName = chrome)
        assertFalse(covers(listOf(direct)))
    }

    @Test
    fun `a feature rule does not cover, an empty feature list does`() {
        assertFalse(covers(listOf(groupRule(features = listOf("reels")))))
        assertTrue(covers(listOf(groupRule(features = emptyList()))))
    }

    @Test
    fun `a package outside the group is not covered`() {
        assertFalse(covers(listOf(groupRule()), pkg = "org.mozilla.firefox"))
        assertFalse(covers(listOf(groupRule()), memberships = emptyList()))
    }

    @Test
    fun `a rule for a different group does not cover`() {
        assertFalse(covers(listOf(groupRule(groupId = 99L))))
    }

    @Test
    fun `a group rule out of its schedule does not cover`() {
        assertFalse(covers(listOf(groupRule()), isInSchedule = { false }))
    }

    @Test
    fun `any one covering rule is enough`() {
        val rules = listOf(groupRule(enabled = false), groupRule(features = listOf("reels")), groupRule())
        assertTrue(covers(rules))
    }

    @Test
    fun `no rules, no coverage`() {
        assertFalse(covers(emptyList()))
    }

    /**
     * Uses the production schedule semantics through [ScheduleEvaluator.isActiveAt] for a
     * [BlockRuleData]: a 9-to-5 weekday group covers at Monday noon and not at Monday 8 pm.
     */
    @Test
    fun `the schedule is the engine's schedule`() {
        val schedule = ScheduleEvaluator()
        val rule = groupRule(scheduleDays = listOf(1, 2, 3, 4, 5), start = 9 * 60, end = 17 * 60)
        val mondayNoon = calendar(Calendar.MONDAY, 12)
        val mondayEvening = calendar(Calendar.MONDAY, 20)
        assertTrue(covers(listOf(rule)) { schedule.isActiveAt(it, mondayNoon) })
        assertFalse(covers(listOf(rule)) { schedule.isActiveAt(it, mondayEvening) })
    }

    /**
     * The class invariant: coverage is never wider than resolution. Any rule that covers a package
     * is one [RuleEvaluator.resolveRulesForPackage] resolves for it, so evaluating a covered browser
     * as an app always has the covering rule to decide with.
     */
    @Test
    fun `a covering rule is always among the rules resolved for the package`() {
        val candidates = listOf(
            groupRule(),
            groupRule(enabled = false),
            groupRule(groupId = 99L),
            groupRule(features = listOf("reels")),
            groupRule(groupId = null).copy(packageName = chrome)
        )
        for (rule in candidates) {
            if (covers(listOf(rule))) {
                val resolved = RuleEvaluator().resolveRulesForPackage(chrome, listOf(rule), members, CoverageFacts(essential = false, launchable = true))
                assertEquals("rule $rule", 1, resolved.size)
            }
        }
    }

    private fun calendar(day: Int, hour: Int): Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
    }
}
