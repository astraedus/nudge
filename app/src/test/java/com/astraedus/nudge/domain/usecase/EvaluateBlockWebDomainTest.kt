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
 * Use-case level tests for [EvaluateBlockUseCase.evaluateWebDomain] (issue #21): a rule's web
 * domains must be gated by [com.astraedus.nudge.domain.model.WebBlockMode.resolve], not the raw
 * app-level `mode`, so a whole-app-off rule (`mode = NONE`) can still enforce on the web.
 *
 * Real [BlockEngine] + [RuleEvaluator] (matches [EvaluateBlockContentFilterTest]'s harness);
 * repositories/preferences/content filter mocked.
 */
class EvaluateBlockWebDomainTest {

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

        every { usageRepository.getDailyForegroundTimeMs(any()) } returns 0L
        every { preferences.contentFilterStrictKeywords } returns flowOf(false)
        every { preferences.contentFilterEnabled } returns flowOf(false)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")

        useCase = EvaluateBlockUseCase(
            blockRuleRepository = blockRuleRepository,
            usageRepository = usageRepository,
            blockEngine = BlockEngine(ScheduleEvaluator()),
            ruleEvaluator = RuleEvaluator(),
            scheduleEvaluator = ScheduleEvaluator(),
            preferences = preferences,
            contentFilter = contentFilter
        )
    }

    private fun rule(
        mode: String,
        webDomains: String?,
        webBlockMode: String? = null,
        packageName: String? = "com.instagram.android",
        delaySeconds: Int = 15
    ) = BlockRule(
        id = 1,
        packageName = packageName,
        mode = mode,
        delaySeconds = delaySeconds,
        webDomains = webDomains,
        webBlockMode = webBlockMode
    )

    @Test
    fun `THE REGRESSION -- app mode NONE with webBlockMode HARD_BLOCK still blocks the site`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "NONE", webDomains = "instagram.com", webBlockMode = "HARD_BLOCK"))
        )

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `DELAY app mode with null webBlockMode blocks with DELAY and the rule's delaySeconds`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "DELAY", webDomains = "instagram.com", delaySeconds = 20))
        )

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        val block = result.decision as BlockDecision.Block
        assertEquals(BlockMode.DELAY, block.mode)
        assertEquals(20, block.delaySeconds)
    }

    @Test
    fun `HARD_BLOCK app mode with null webBlockMode blocks with HARD_BLOCK`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "HARD_BLOCK", webDomains = "instagram.com"))
        )

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `BREATHING app mode with null webBlockMode blocks with BREATHING`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "BREATHING", webDomains = "instagram.com", delaySeconds = 25))
        )

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        val block = result.decision as BlockDecision.Block
        assertEquals(BlockMode.BREATHING, block.mode)
        assertEquals(25, block.delaySeconds)
    }

    @Test
    fun `webBlockMode overrides a real app mode -- HARD_BLOCK app mode, DELAY web mode`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "HARD_BLOCK", webDomains = "instagram.com", webBlockMode = "DELAY"))
        )

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.DELAY, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `mode NONE with null webBlockMode -- un-migrated inherit case -- enforces nothing and falls through, filter ON blocks as Restricted content`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "NONE", webDomains = "instagram.com", webBlockMode = null))
        )
        every { preferences.contentFilterEnabled } returns flowOf(true)
        coEvery { contentFilter.isBlocked(any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals("Restricted content", (result.decision as BlockDecision.Block).ruleName)
    }

    @Test
    fun `mode NONE with null webBlockMode -- filter OFF -- falls through to Allow, not treated as handled`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "NONE", webDomains = "instagram.com", webBlockMode = null))
        )
        every { preferences.contentFilterEnabled } returns flowOf(false)

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Allow)
    }

    @Test
    fun `non-matching URL falls through to the content filter`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "HARD_BLOCK", webDomains = "instagram.com"))
        )
        every { preferences.contentFilterEnabled } returns flowOf(true)
        coEvery { contentFilter.isBlocked(any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://example.com")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals("Restricted content", (result.decision as BlockDecision.Block).ruleName)
    }

    @Test
    fun `trackingPackage is the matching rule's packageName`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "HARD_BLOCK", webDomains = "instagram.com", packageName = "com.instagram.android"))
        )

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertEquals("com.instagram.android", result.trackingPackage)
    }

    @Test
    fun `trackingPackage falls back to web when the rule has no packageName`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "HARD_BLOCK", webDomains = "instagram.com", packageName = null))
        )

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertEquals("web", result.trackingPackage)
    }

    @Test
    fun `ruleName reflects the resolved web mode for an app-mode-NONE plus web-HARD_BLOCK rule`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(
            listOf(rule(mode = "NONE", webDomains = "instagram.com", webBlockMode = "HARD_BLOCK"))
        )

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals("Web - Hard Block", (result.decision as BlockDecision.Block).ruleName)
    }

    @Test
    fun `no rules at all falls through to content filter and can Allow`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(emptyList())
        every { preferences.contentFilterEnabled } returns flowOf(false)

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Allow)
        assertNull(result.trackingPackage)
    }
}
