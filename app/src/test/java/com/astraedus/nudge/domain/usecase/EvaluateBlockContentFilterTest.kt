package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Use-case level tests for the generic content-filter fallback in
 * [EvaluateBlockUseCase.evaluateWebDomain]. The 274k-entry asset is NEVER loaded
 * here — [ContentFilter] is mocked so we exercise wiring only.
 */
class EvaluateBlockContentFilterTest {

    private lateinit var blockRuleRepository: BlockRuleRepository
    private lateinit var usageRepository: UsageRepository
    private lateinit var preferences: NudgePreferences
    private lateinit var contentFilter: ContentFilter
    private lateinit var useCase: EvaluateBlockUseCase

    @Before
    fun setUp() {
        blockRuleRepository = mockk()
        usageRepository = mockk()
        preferences = mockk()
        contentFilter = mockk()

        // No explicit per-rule web domain rules -> always falls through to filter.
        every { blockRuleRepository.getEnabledRules() } returns flowOf(emptyList())
        every { usageRepository.getDailyUsage(any()) } returns flowOf(0L)

        useCase = EvaluateBlockUseCase(
            blockRuleRepository = blockRuleRepository,
            usageRepository = usageRepository,
            blockEngine = BlockEngine(ScheduleEvaluator()),
            ruleEvaluator = RuleEvaluator(),
            preferences = preferences,
            contentFilter = contentFilter
        )
    }

    @Test
    fun `enabled and blocked returns Block with configured HARD_BLOCK mode`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        coEvery { contentFilter.isBlocked(any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Block)
        val block = result.decision as BlockDecision.Block
        assertEquals(BlockMode.HARD_BLOCK, block.mode)
        assertEquals("Restricted content", block.ruleName)
        assertEquals("web", result.trackingPackage)
    }

    @Test
    fun `enabled and blocked honors DELAY mode`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("DELAY")
        coEvery { contentFilter.isBlocked(any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.DELAY, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `disabled returns Allow even when filter would block`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(false)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        coEvery { contentFilter.isBlocked(any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Allow)
        assertNull(result.trackingPackage)
    }

    @Test
    fun `enabled but not blocked returns Allow`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        coEvery { contentFilter.isBlocked(any()) } returns false

        val result = useCase.evaluateWebDomain("https://wikipedia.org")

        assertTrue(result.decision is BlockDecision.Allow)
        assertNull(result.trackingPackage)
    }

    @Test
    fun `invalid mode string falls back to HARD_BLOCK`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("NONSENSE")
        coEvery { contentFilter.isBlocked(any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (result.decision as BlockDecision.Block).mode)
    }
}
