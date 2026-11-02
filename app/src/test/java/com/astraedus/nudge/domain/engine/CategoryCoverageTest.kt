package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.testing.aBlockRuleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1: a group that matches by category (spec 5.2) covers an app through [RuleCoverage], and the
 * precedence holds: UNDEFINED / unread never match, "Removed by you" beats a category, an essential
 * is never covered by a group rule.
 */
class CategoryCoverageTest {

    private val social = 7L
    private val handMade = 8L
    private val resolver = GroupMembershipResolver(
        listOf(
            GroupDefinition(social, categories = setOf(AppCategory.SOCIAL), excludes = setOf("removed")),
            GroupDefinition(handMade, includes = setOf("manual"))
        )
    )

    private val hardBlock = aBlockRuleData(id = 1L, mode = BlockMode.HARD_BLOCK)

    private fun facts(category: AppCategory?, essential: Boolean = false) =
        CoverageFacts(essential = essential, launchable = true, category = category, browser = false)

    private fun covers(
        rule: BlockRuleData,
        pkg: String,
        facts: CoverageFacts,
        membership: GroupMembershipResolver = resolver
    ) = RuleCoverage.coveringRules(pkg, listOf(rule), membership, facts).isNotEmpty()

    @Test
    fun `a category group covers an app whose category matches`() {
        assertTrue(covers(hardBlock.copy(groupId = social), "insta", facts(AppCategory.SOCIAL)))
    }

    @Test
    fun `a category group does not cover an app of another category`() {
        assertFalse(covers(hardBlock.copy(groupId = social), "maps", facts(AppCategory.MAPS)))
    }

    @Test
    fun `UNDEFINED and an unread category never match a category group`() {
        assertFalse(covers(hardBlock.copy(groupId = social), "x", facts(AppCategory.UNDEFINED)))
        assertFalse(covers(hardBlock.copy(groupId = social), "x", facts(null)))
    }

    @Test
    fun `an unread category still matches a hand-added app`() {
        assertTrue(covers(hardBlock.copy(groupId = handMade), "manual", facts(null)))
    }

    @Test
    fun `Removed by you beats the category`() {
        assertFalse(covers(hardBlock.copy(groupId = social), "removed", facts(AppCategory.SOCIAL)))
        assertEquals(emptySet<Long>(), RuleCoverage.groupIdsOf("removed", resolver, facts(AppCategory.SOCIAL)))
    }

    @Test
    fun `an essential app is never covered by a category group, even when added by hand`() {
        val withInclude = GroupMembershipResolver(
            listOf(GroupDefinition(social, categories = setOf(AppCategory.SOCIAL), includes = setOf("dialer")))
        )
        val essential = facts(AppCategory.SOCIAL, essential = true)
        assertFalse(covers(hardBlock.copy(groupId = social), "dialer", essential, withInclude))
        assertFalse(covers(hardBlock.copy(groupId = social), "other", essential, withInclude))
    }

    @Test
    fun `an All apps rule allowing a category group leaves that category uncovered`() {
        val allApps = hardBlock.copy(allApps = true, exceptGroupIds = setOf(social))
        assertFalse(covers(allApps, "insta", facts(AppCategory.SOCIAL)))
        assertTrue(covers(allApps, "maps", facts(AppCategory.MAPS)))
    }

    @Test
    fun `categoryNeeded is false when no rule targets a category group`() {
        val rules = listOf(hardBlock.copy(groupId = handMade), hardBlock.copy(allApps = true, exceptGroupIds = setOf(handMade)), hardBlock.copy(groupId = 99L))
        assertFalse(RuleCoverage.categoryNeeded(rules, resolver))
        assertFalse(RuleCoverage.categoryNeeded(emptyList(), resolver))
    }

    @Test
    fun `categoryNeeded is true when a rule targets a category group`() {
        assertTrue(RuleCoverage.categoryNeeded(listOf(hardBlock.copy(groupId = social)), resolver))
    }

    @Test
    fun `categoryNeeded is true for an All apps rule allowing a category group`() {
        assertTrue(RuleCoverage.categoryNeeded(listOf(hardBlock.copy(allApps = true, exceptGroupIds = setOf(social))), resolver))
    }

    @Test
    fun `categoryNeeded is false when no group uses categories at all`() {
        assertFalse(RuleCoverage.categoryNeeded(listOf(hardBlock.copy(groupId = handMade)), membership(handMade to "manual")))
    }
}
