package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L1: a group rule is named after its group (spec PR 2). The name is what the block overlay shows
 * as "Rule: <name>", so a Delay on Instagram that comes from "Social" says "Social", not "Delay".
 */
class RuleEvaluatorGroupNameTest {

    private val evaluator = RuleEvaluator()

    private fun rule(
        groupId: Long? = 7L,
        groupName: String? = "Social",
        mode: BlockMode = BlockMode.DELAY,
        limit: Int? = null,
        days: List<Int>? = null,
        features: List<String>? = null
    ) = BlockRuleData(
        id = 1,
        packageName = if (groupId == null) "com.instagram.android" else null,
        groupId = groupId,
        mode = mode,
        delaySeconds = 15,
        dailyLimitMinutes = limit,
        enabled = true,
        scheduleDays = days,
        inAppFeatures = features,
        groupName = groupName
    )

    @Test
    fun `a group rule is its group's name`() {
        assertEquals("Social", evaluator.buildRuleName(rule()))
    }

    @Test
    fun `whatever the mode`() {
        for (mode in BlockMode.values()) {
            assertEquals("mode $mode", "Social", evaluator.buildRuleName(rule(mode = mode)))
        }
    }

    @Test
    fun `the qualifiers stay`() {
        assertEquals("Social (scheduled)", evaluator.buildRuleName(rule(days = listOf(1, 2))))
        assertEquals("Social (30 min/day)", evaluator.buildRuleName(rule(limit = 30)))
        assertEquals(
            "Social (scheduled, 30 min/day)",
            evaluator.buildRuleName(rule(days = listOf(1), limit = 30))
        )
    }

    @Test
    fun `a feature group rule keeps its feature prefix`() {
        assertEquals("Reels - Social", evaluator.buildRuleName(rule(features = listOf("reels"))))
    }

    @Test
    fun `a group rule with no name is Group, never a mode name`() {
        assertEquals(RuleEvaluator.GROUP_FALLBACK_NAME, evaluator.buildRuleName(rule(groupName = null)))
        assertEquals(RuleEvaluator.GROUP_FALLBACK_NAME, evaluator.buildRuleName(rule(groupName = "  ")))
        assertEquals(
            "Group (30 min/day)",
            evaluator.buildRuleName(rule(groupName = null, mode = BlockMode.HARD_BLOCK, limit = 30))
        )
    }

    @Test
    fun `a direct rule is unchanged`() {
        assertEquals("Delay", evaluator.buildRuleName(rule(groupId = null, groupName = null)))
        assertEquals(
            "Hard Block (30 min/day)",
            evaluator.buildRuleName(rule(groupId = null, groupName = null, mode = BlockMode.HARD_BLOCK, limit = 30))
        )
    }

    @Test
    fun `resolution carries the group name to the active rule`() {
        val resolved = evaluator.resolveRulesForPackage(
            "com.instagram.android",
            listOf(rule()),
            membership(7L to "com.instagram.android"),
            CoverageFacts(essential = false, launchable = true, category = null)
        )
        assertEquals("Social", resolved.single().ruleName)
    }

    /** The snapshot is where the name comes from: the groups table, by the rule's group id. */
    @Test
    fun `RuleSnapshot fills the group name from the groups table`() {
        val snapshot = RuleSnapshot.of(
            enabledRules = listOf(
                BlockRule(id = 1, groupId = 7L, mode = BlockMode.DELAY.name),
                BlockRule(id = 2, groupId = 8L, mode = BlockMode.DELAY.name),
                BlockRule(id = 3, packageName = "com.example.app", mode = BlockMode.HARD_BLOCK.name)
            ),
            groups = listOf(AppGroup(id = 7L, name = "Social")),
            members = listOf(AppGroupMember(7L, "com.instagram.android"))
        )
        val byId = snapshot.ruleData.associateBy { it.id }
        assertEquals("Social", byId.getValue(1).groupName)
        assertNull("a missing group row leaves the name null", byId.getValue(2).groupName)
        assertNull("a direct rule has no group name", byId.getValue(3).groupName)
        assertEquals("Group", evaluator.buildRuleName(byId.getValue(2)))
    }
}
