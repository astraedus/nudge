package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.testing.aBlockRuleData
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

    private val socialRule = aBlockRuleData(id = 1, groupId = social, mode = BlockMode.HARD_BLOCK)

    private val members = membership(social to chrome, social to "com.instagram.android")

    private fun covers(
        rules: List<BlockRuleData>,
        pkg: String = chrome,
        memberships: GroupMembershipResolver = members,
        facts: CoverageFacts = CoverageFacts(essential = false, launchable = true, category = null, browser = false),
        isInSchedule: (BlockRuleData) -> Boolean = always
    ) = WholeAppCoverage.coversNow(pkg, rules, memberships, facts, isInSchedule)

    @Test
    fun `an enabled whole-app group rule over a member covers it`() {
        assertTrue(covers(listOf(socialRule)))
    }

    @Test
    fun `every mode covers, including NONE`() {
        for (mode in BlockMode.values()) {
            assertTrue("mode $mode", covers(listOf(socialRule.copy(mode = mode))))
        }
    }

    @Test
    fun `a disabled group rule does not cover`() {
        assertFalse(covers(listOf(socialRule.copy(enabled = false))))
    }

    @Test
    fun `a DIRECT rule on the browser does not cover (F5)`() {
        val direct = socialRule.copy(groupId = null).copy(packageName = chrome)
        assertFalse(covers(listOf(direct)))
    }

    @Test
    fun `a feature rule does not cover, an empty feature list does`() {
        assertFalse(covers(listOf(socialRule.copy(inAppFeatures = listOf("reels")))))
        assertTrue(covers(listOf(socialRule.copy(inAppFeatures = emptyList()))))
    }

    @Test
    fun `a package outside the group is not covered`() {
        assertFalse(covers(listOf(socialRule), pkg = "org.mozilla.firefox"))
        assertFalse(covers(listOf(socialRule), memberships = GroupMembershipResolver.NONE))
    }

    @Test
    fun `a rule for a different group does not cover`() {
        assertFalse(covers(listOf(socialRule.copy(groupId = 99L))))
    }

    @Test
    fun `a group rule out of its schedule does not cover`() {
        assertFalse(covers(listOf(socialRule), isInSchedule = { false }))
    }

    @Test
    fun `any one covering rule is enough`() {
        val rules = listOf(socialRule.copy(enabled = false), socialRule.copy(inAppFeatures = listOf("reels")), socialRule)
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
        val rule = socialRule.copy(scheduleDays = listOf(1, 2, 3, 4, 5), scheduleStartMinute = 9 * 60, scheduleEndMinute = 17 * 60)
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
            socialRule,
            socialRule.copy(enabled = false),
            socialRule.copy(groupId = 99L),
            socialRule.copy(inAppFeatures = listOf("reels")),
            socialRule.copy(groupId = null).copy(packageName = chrome)
        )
        for (rule in candidates) {
            if (covers(listOf(rule))) {
                val resolved = RuleEvaluator().resolveRulesForPackage(chrome, listOf(rule), members, CoverageFacts(essential = false, launchable = true, category = null, browser = false))
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
