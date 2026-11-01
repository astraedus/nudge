package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshotSource
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts

/**
 * L3: group rules through the real [EvaluateBlockUseCase] (spec PR 2).
 *
 *  - A group rule and a stricter DIRECT rule on the same app resolve to the strictest.
 *  - `excludeDirectRules` (what the service passes for a covered browser, spec 5.5) leaves the
 *    group's decision alone and drops the direct rule, so a direct rule on a browser keeps its
 *    web-only meaning (F5).
 *  - The decision carries the group's name, which is what the overlay shows.
 *  - `isCoveredWhole` (whether a browser is evaluated as an app at all) is fail CLOSED on a cold
 *    start: through a `RuleSnapshotSource` that never emits, a grouped browser is still covered and
 *    still blocked, exactly like a cold app in the same group.
 */
class EvaluateBlockGroupRulesTest {

    private val instagram = "com.instagram.android"
    private val chrome = "com.android.chrome"
    private val social = 7L

    private val groupDelay = BlockRule(id = 1, groupId = social, mode = BlockMode.DELAY.name, delaySeconds = 15)

    private fun snapshotOf(vararg rules: BlockRule) = RuleSnapshot.of(
        enabledRules = rules.toList(),
        groups = listOf(AppGroup(id = social, name = "Social")),
        members = listOf(AppGroupMember(social, instagram), AppGroupMember(social, chrome))
    )

    private fun useCase(snapshot: RuleSnapshot) = useCase(
        object : RuleSnapshots {
            override suspend fun current() = snapshot
            override suspend fun enabledRules() = snapshot.enabledRules
        }
    )

    private fun useCase(snapshots: RuleSnapshots) = EvaluateBlockUseCase(
        ruleSnapshots = snapshots,
        usageRepository = mockk<UsageRepository> { every { getDailyForegroundTimeMs(any()) } returns 0L },
        blockEngine = BlockEngine(ScheduleEvaluator()),
        ruleEvaluator = RuleEvaluator(),
        scheduleEvaluator = ScheduleEvaluator(),
        preferences = mockk<NudgePreferences> { every { contentFilterEnabled } returns flowOf(false) },
        contentFilter = mockk<ContentFilter>(),
        nukeEnforcement = NukeEnforcement.NEVER,
        essentialPackages = FakeEssentialPackages(),
        packageFacts = FakePackageFacts()
    )

    private fun BlockDecision.block(): BlockDecision.Block {
        assertTrue("expected a block, got $this", this is BlockDecision.Block)
        return this as BlockDecision.Block
    }

    @Test
    fun `a group Delay alone delays, named after the group`() = runTest {
        val decision = useCase(snapshotOf(groupDelay)).invoke(instagram).block()
        assertEquals(BlockMode.DELAY, decision.mode)
        assertEquals(15, decision.delaySeconds)
        assertEquals("Social", decision.ruleName)
    }

    @Test
    fun `a group Delay and a direct Hard block on one app resolve to the Hard block`() = runTest {
        val directHard = BlockRule(id = 2, packageName = instagram, mode = BlockMode.HARD_BLOCK.name)
        val decision = useCase(snapshotOf(groupDelay, directHard)).invoke(instagram).block()
        assertEquals(BlockMode.HARD_BLOCK, decision.mode)
        assertEquals("Hard Block", decision.ruleName)
    }

    @Test
    fun `excludeDirectRules ignores the direct rule and keeps the group's decision`() = runTest {
        val directHard = BlockRule(id = 2, packageName = instagram, mode = BlockMode.HARD_BLOCK.name)
        val decision = useCase(snapshotOf(groupDelay, directHard))
            .invoke(instagram, excludeDirectRules = true)
            .block()
        assertEquals(BlockMode.DELAY, decision.mode)
        assertEquals("Social", decision.ruleName)
    }

    @Test
    fun `excludeDirectRules over a browser with only a direct rule allows (F5)`() = runTest {
        val directChromeHard = BlockRule(id = 3, packageName = chrome, mode = BlockMode.HARD_BLOCK.name)
        val useCase = useCase(snapshotOf(directChromeHard))
        assertEquals(BlockDecision.Allow, useCase.invoke(chrome, excludeDirectRules = true))
    }

    @Test
    fun `a group Hard block over a browser blocks it as an app`() = runTest {
        val groupHard = groupDelay.copy(mode = BlockMode.HARD_BLOCK.name)
        val decision = useCase(snapshotOf(groupHard)).invoke(chrome, excludeDirectRules = true).block()
        assertEquals(BlockMode.HARD_BLOCK, decision.mode)
        assertEquals("Social", decision.ruleName)
    }

    @Test
    fun `the default still includes direct rules`() = runTest {
        val directHard = BlockRule(id = 2, packageName = instagram, mode = BlockMode.HARD_BLOCK.name)
        assertEquals(
            BlockMode.HARD_BLOCK,
            useCase(snapshotOf(directHard)).invoke(instagram).block().mode
        )
    }

    // ── Coverage: is a browser evaluated as an app at all (spec 5.5) ───────

    @Test
    fun `a browser in a group with a whole-app rule is covered, one with only a direct or feature rule is not`() = runTest {
        val groupHard = groupDelay.copy(mode = BlockMode.HARD_BLOCK.name)
        assertTrue(useCase(snapshotOf(groupHard)).isCoveredWhole(chrome))

        val directChromeHard = BlockRule(id = 3, packageName = chrome, mode = BlockMode.HARD_BLOCK.name)
        assertFalse("a direct rule keeps its web-only meaning (F5)", useCase(snapshotOf(directChromeHard)).isCoveredWhole(chrome))

        val groupFeature = groupHard.copy(inAppFeatures = "SHORTS")
        assertFalse("a feature rule never covers a whole browser", useCase(snapshotOf(groupFeature)).isCoveredWhole(chrome))
    }

    @Test
    fun `before the first snapshot, a browser in a hard-blocked group is still covered and blocked`() = runTest {
        // The member table never answers, so the in-memory snapshot can never be built: the state
        // the service is in during the first moments after the process starts.
        val groupHard = groupDelay.copy(mode = BlockMode.HARD_BLOCK.name)
        val members = listOf(AppGroupMember(social, instagram), AppGroupMember(social, chrome))
        val repository = mockk<BlockRuleRepository> {
            every { getEnabledRules() } returns flowOf(listOf(groupHard))
            every { getAllGroups() } returns flowOf(listOf(AppGroup(id = social, name = "Social")))
            every { getAllGroupMembers() } returns flow { awaitCancellation() }
            every { getGroupMembers(social) } returns flowOf(members)
        }
        val source = RuleSnapshotSource(repository, backgroundScope, NudgeLog.NoOp) { 0L }
        runCurrent()
        // The synchronous read the first version of this used: null here, and it answered "not
        // covered" for null, so Chrome opened freely while Instagram (below) was blocked.
        assertNull("premise: the snapshot is cold", source.snapshot.value)

        val useCase = useCase(source)
        assertTrue("a cold browser in the group is covered (table read)", useCase.isCoveredWhole(chrome))
        val decision = useCase.invoke(chrome, excludeDirectRules = true).block()
        assertEquals(BlockMode.HARD_BLOCK, decision.mode)
        assertEquals("Social", decision.ruleName)
        assertEquals(
            "the browser blocks exactly like a cold app in the same group",
            useCase.invoke(instagram).block().mode,
            decision.mode
        )
    }
}
