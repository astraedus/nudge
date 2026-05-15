package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuleEvaluatorTest {

    private lateinit var evaluator: RuleEvaluator

    @Before
    fun setUp() {
        evaluator = RuleEvaluator()
    }

    @Test
    fun `direct package match returns rule`() {
        val rules = listOf(
            BlockRuleData(
                id = 1, packageName = "com.example.app", groupId = null,
                mode = BlockMode.HARD_BLOCK, delaySeconds = 0,
                dailyLimitMinutes = null, enabled = true
            )
        )
        val result = evaluator.resolveRulesForPackage("com.example.app", rules, emptyList())
        assertEquals(1, result.size)
        assertEquals(BlockMode.HARD_BLOCK, result[0].mode)
    }

    @Test
    fun `group membership match returns rule`() {
        val rules = listOf(
            BlockRuleData(
                id = 1, packageName = null, groupId = 10,
                mode = BlockMode.DELAY, delaySeconds = 15,
                dailyLimitMinutes = null, enabled = true
            )
        )
        val memberships = listOf(
            GroupMembership(groupId = 10, packageName = "com.example.app")
        )
        val result = evaluator.resolveRulesForPackage("com.example.app", rules, memberships)
        assertEquals(1, result.size)
        assertEquals(BlockMode.DELAY, result[0].mode)
        assertEquals(15, result[0].delaySeconds)
    }

    @Test
    fun `no match returns empty list`() {
        val rules = listOf(
            BlockRuleData(
                id = 1, packageName = "com.other.app", groupId = null,
                mode = BlockMode.HARD_BLOCK, delaySeconds = 0,
                dailyLimitMinutes = null, enabled = true
            )
        )
        val result = evaluator.resolveRulesForPackage("com.example.app", rules, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `multiple matches returns all rules`() {
        val rules = listOf(
            BlockRuleData(
                id = 1, packageName = "com.example.app", groupId = null,
                mode = BlockMode.HARD_BLOCK, delaySeconds = 0,
                dailyLimitMinutes = null, enabled = true
            ),
            BlockRuleData(
                id = 2, packageName = null, groupId = 5,
                mode = BlockMode.DELAY, delaySeconds = 30,
                dailyLimitMinutes = 60, enabled = true
            )
        )
        val memberships = listOf(
            GroupMembership(groupId = 5, packageName = "com.example.app")
        )
        val result = evaluator.resolveRulesForPackage("com.example.app", rules, memberships)
        assertEquals(2, result.size)
    }

    @Test
    fun `rule for different group does not match`() {
        val rules = listOf(
            BlockRuleData(
                id = 1, packageName = null, groupId = 10,
                mode = BlockMode.BREATHING, delaySeconds = 20,
                dailyLimitMinutes = null, enabled = true
            )
        )
        val memberships = listOf(
            GroupMembership(groupId = 5, packageName = "com.example.app")
        )
        val result = evaluator.resolveRulesForPackage("com.example.app", rules, memberships)
        assertTrue(result.isEmpty())
    }
}
