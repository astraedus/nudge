package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3: a browser never joins a group by category, through the real [EvaluateBlockUseCase].
 *
 * Chrome reports SOCIAL and Firefox PRODUCTIVITY. Since PR 2 a browser covered by a blocking group is
 * blocked AS AN APP on every site, so before this rule, checking "Social" silently hard-blocked the
 * whole browser. The browser set is the one list (`EssentialPackages.browsers()`, the same one that
 * keeps browsers out of the essentials); a browser added to the group by hand is still covered.
 */
class EvaluateBlockCategoryBrowserTest {

    private val instagram = "com.instagram.android"
    private val chrome = "com.android.chrome"
    private val social = 7L

    private val groupHard = aBlockRule(id = 1, groupId = social, mode = BlockMode.HARD_BLOCK.name)

    private fun snapshot(members: List<AppGroupMember> = emptyList()) = RuleSnapshot.of(
        enabledRules = listOf(groupHard),
        groups = listOf(anAppGroup("Social", id = social, categories = "SOCIAL")),
        members = members
    )

    private fun useCase(snapshot: RuleSnapshot) = EvaluateBlockUseCase(
        ruleSnapshots = object : RuleSnapshots {
            override suspend fun current() = snapshot
            override suspend fun enabledRules() = snapshot.enabledRules
        },
        usageRepository = FakeUsageRepository(),
        blockEngine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE)),
        ruleEvaluator = RuleEvaluator(),
        scheduleEvaluator = ScheduleEvaluator(FirstUnlockState.NONE),
        preferences = FakeNudgePreferences(),
        contentFilter = mockk<ContentFilter>(),
        nukeEnforcement = NukeEnforcement.NEVER,
        essentialPackages = FakeEssentialPackages().apply { browsers = setOf(chrome) },
        packageFacts = FakePackageFacts(
            launchable = setOf(instagram, chrome),
            categories = mapOf(instagram to AppCategory.SOCIAL, chrome to AppCategory.SOCIAL)
        )
    )

    @Test
    fun `checking Social blocks a SOCIAL app but leaves a browser that reports SOCIAL open`() = runTest {
        val useCase = useCase(snapshot())
        val instagramDecision = useCase.invoke(instagram)
        assertTrue("premise: the category group blocks a SOCIAL app", instagramDecision is BlockDecision.Block)

        assertFalse("a browser is not covered as an app through a category", useCase.isCoveredWhole(chrome))
        assertEquals(BlockDecision.Allow, useCase.invoke(chrome, excludeDirectRules = true))
    }

    @Test
    fun `a browser added to the Social group by hand is covered and blocked as an app`() = runTest {
        val useCase = useCase(snapshot(listOf(aGroupMember(social, chrome))))
        assertTrue(useCase.isCoveredWhole(chrome))
        val decision = useCase.invoke(chrome, excludeDirectRules = true)
        assertTrue("expected a block, got $decision", decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
        assertEquals("Social", decision.ruleName)
    }
}
