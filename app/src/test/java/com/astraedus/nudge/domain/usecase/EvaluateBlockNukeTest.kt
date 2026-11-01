package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.nuke.NukePolicy
import com.astraedus.nudge.domain.nuke.NukeState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts

/**
 * L1 through the REAL use case: Nuke joins evaluation at the one entry every app-level caller uses,
 * reaches the engine as its top step, and leaves non-nuked apps exactly as they were.
 */
class EvaluateBlockNukeTest {

    private val nuked = "com.instagram.android"
    private val other = "com.google.android.youtube"

    private val blockRuleRepository: BlockRuleRepository = mockk()
    private val usageRepository: UsageRepository = mockk()

    private val asked = mutableListOf<String>()

    private fun useCase(state: NukeState) = EvaluateBlockUseCase(
        blockRuleRepository = blockRuleRepository,
        usageRepository = usageRepository,
        blockEngine = BlockEngine(ScheduleEvaluator()),
        ruleEvaluator = RuleEvaluator(),
        scheduleEvaluator = ScheduleEvaluator(),
        preferences = mockk<NudgePreferences>(),
        contentFilter = mockk<ContentFilter>(),
        nukeEnforcement = NukeEnforcement { pkg ->
            asked += pkg
            NukePolicy.isNuked(pkg, state, emptySet())
        },
        essentialPackages = FakeEssentialPackages(),
        packageFacts = FakePackageFacts()
    )

    private val on = NukeState(active = true, packages = setOf(nuked), keyHash = "k")

    /** A DELAY rule on BOTH apps, so a non-nuked answer is visibly the rule's. */
    private fun stubDelayRules() {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(
                BlockRule(id = 1, packageName = nuked, mode = BlockMode.DELAY.name, delaySeconds = 15, enabled = true),
                BlockRule(id = 2, packageName = other, mode = BlockMode.DELAY.name, delaySeconds = 15, enabled = true)
            )
        )
        every { blockRuleRepository.getAllGroups() } returns flowOf(emptyList())
        every { usageRepository.getDailyForegroundTimeMs(any()) } returns 0L
    }

    @Test
    fun `a nuked app is hard-blocked by Nuke, not by its rule`() = runTest {
        stubDelayRules()
        val decision = useCase(on).invoke(nuked)
        assertEquals(BlockEngine.NUKE_DECISION, decision)
        assertEquals(listOf(nuked), asked)
    }

    @Test
    fun `a nuked app never costs a rule lookup or a usage read`() = runTest {
        // Nothing stubbed: a single repository call would throw.
        val decision = useCase(on).invoke(nuked)
        assertEquals(BlockEngine.NUKE_DECISION, decision)
        verify(exactly = 0) { blockRuleRepository.getEnabledRules() }
        verify(exactly = 0) { usageRepository.getDailyForegroundTimeMs(any()) }
    }

    @Test
    fun `in-app feature evaluation of a nuked app is Nuke too`() = runTest {
        val decision = useCase(on).invoke(nuked, detectedFeature = "REELS", includeWholeAppRulesForFeature = false)
        assertEquals(BlockEngine.NUKE_DECISION, decision)
    }

    @Test
    fun `an app that is not on the list keeps its own rule`() = runTest {
        stubDelayRules()
        val decision = useCase(on).invoke(other)
        assertTrue(decision is BlockDecision.Block)
        decision as BlockDecision.Block
        assertEquals(BlockMode.DELAY, decision.mode)
        assertFalse(decision.nuke)
    }

    @Test
    fun `with Nuke off the listed app keeps its own rule`() = runTest {
        stubDelayRules()
        val decision = useCase(on.copy(active = false)).invoke(nuked)
        decision as BlockDecision.Block
        assertEquals(BlockMode.DELAY, decision.mode)
        assertFalse(decision.nuke)
    }

    @Test
    fun `a floor package on the list is not nuked`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(emptyList())
        every { blockRuleRepository.getAllGroups() } returns flowOf(emptyList())
        val dialerOnList = on.copy(packages = setOf("com.android.dialer"))
        assertEquals(BlockDecision.Allow, useCase(dialerOnList).invoke("com.android.dialer"))
    }
}
