package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.nuke.NukePolicy
import com.astraedus.nudge.domain.nuke.NukeState
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.FakeScreenTime
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 through the REAL use case: Nuke joins evaluation at the one entry every app-level caller uses,
 * reaches the engine as its top step, and leaves non-nuked apps exactly as they were.
 */
class EvaluateBlockNukeTest {

    private val nuked = "com.instagram.android"
    private val other = "com.google.android.youtube"

    private val db = FakeRuleDatabase()
    private val screenTime = FakeScreenTime()

    private val asked = mutableListOf<String>()

    private fun useCase(state: NukeState, repository: BlockRuleRepository = db.repository) = EvaluateBlockUseCase(
        blockRuleRepository = repository,
        usageRepository = FakeUsageRepository(screenTime = screenTime),
        blockEngine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE)),
        ruleEvaluator = RuleEvaluator(),
        scheduleEvaluator = ScheduleEvaluator(FirstUnlockState.NONE),
        preferences = FakeNudgePreferences(),
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
        db.seedRule(aBlockRule(packageName = nuked, mode = BlockMode.DELAY.name))
        db.seedRule(aBlockRule(packageName = other, mode = BlockMode.DELAY.name))
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
        // A strict mock repository (nothing stubbed: any call throws) -- the fake database cannot count
        // reads, and this test is exactly the "must not be called" check.
        val repository = mockk<BlockRuleRepository>()
        val decision = useCase(on, repository).invoke(nuked)
        assertEquals(BlockEngine.NUKE_DECISION, decision)
        verify(exactly = 0) { repository.getEnabledRules() }
        assertEquals(0, screenTime.reads)
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
        val dialerOnList = on.copy(packages = setOf("com.android.dialer"))
        assertEquals(BlockDecision.Allow, useCase(dialerOnList).invoke("com.android.dialer"))
    }
}
