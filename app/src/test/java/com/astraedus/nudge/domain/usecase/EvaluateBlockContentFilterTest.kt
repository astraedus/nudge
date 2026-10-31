package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.ContentFilterAllowlist
import com.astraedus.nudge.domain.ContentFilterBlocklist
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Use-case level tests for the generic content-filter fallback in
 * [EvaluateBlockUseCase.evaluateWebDomain]. The bundled asset is NEVER loaded here —
 * [ContentFilter] is mocked so we exercise wiring only. (The asset's own contents are
 * gated by `ContentFilterAssetTest`.)
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
        every { usageRepository.getDailyForegroundTimeMs(any()) } returns 0L
        // Default strict-keywords pref to false (opt-in). Individual tests override.
        every { preferences.contentFilterStrictKeywords } returns flowOf(false)
        every { preferences.contentFilterAllowlist } returns flowOf(emptySet())
        every { preferences.contentFilterBlocklist } returns flowOf(emptySet())

        useCase = EvaluateBlockUseCase(
            blockRuleRepository = blockRuleRepository,
            usageRepository = usageRepository,
            blockEngine = BlockEngine(ScheduleEvaluator()),
            ruleEvaluator = RuleEvaluator(),
            scheduleEvaluator = ScheduleEvaluator(),
            preferences = preferences,
            contentFilter = contentFilter,
            nukeEnforcement = NukeEnforcement.NEVER
        )
    }

    @Test
    fun `enabled and blocked returns Block with configured HARD_BLOCK mode`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

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
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.DELAY, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `disabled returns Allow even when filter would block`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(false)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Allow)
        assertNull(result.trackingPackage)
    }

    @Test
    fun `enabled but not blocked returns Allow`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns false

        val result = useCase.evaluateWebDomain("https://wikipedia.org")

        assertTrue(result.decision is BlockDecision.Allow)
        assertNull(result.trackingPackage)
    }

    @Test
    fun `invalid mode string falls back to HARD_BLOCK`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("NONSENSE")
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `strict pref TRUE threads strictKeywords=true into isBlocked`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        every { preferences.contentFilterStrictKeywords } returns flowOf(true)
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        useCase.evaluateWebDomain("https://www.google.com/search?q=bbc")

        coVerify { contentFilter.isBlocked("https://www.google.com/search?q=bbc", true, any(), any()) }
    }

    @Test
    fun `strict pref FALSE default threads strictKeywords=false into isBlocked`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        // contentFilterStrictKeywords defaults to false via setUp.
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns false

        useCase.evaluateWebDomain("https://www.google.com/search?q=bbc")

        coVerify { contentFilter.isBlocked("https://www.google.com/search?q=bbc", false, any(), any()) }
    }

    // ── "Allowed words & sites" (issue #78) ──

    @Test
    fun `strict on threads the stored allowlist into isBlocked`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        every { preferences.contentFilterStrictKeywords } returns flowOf(true)
        every { preferences.contentFilterAllowlist } returns flowOf(setOf("cnc", "cnc-shop.com"))
        val passed = slot<ContentFilterAllowlist>()
        coEvery { contentFilter.isBlocked(any(), any(), capture(passed), any()) } returns false

        val result = useCase.evaluateWebDomain("https://www.google.com/search?q=cnc+machine")

        assertEquals(BlockDecision.Allow, result.decision)
        assertEquals(setOf("cnc", "cnc-shop.com"), passed.captured.entries)
    }

    @Test
    fun `strict off never reads the allowlist and passes no exceptions`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        val passed = slot<ContentFilterAllowlist>()
        coEvery { contentFilter.isBlocked(any(), any(), capture(passed), any()) } returns false

        useCase.evaluateWebDomain("https://www.google.com/search?q=cnc")

        assertTrue("no exceptions without strict matching", passed.captured.isEmpty)
        verify(exactly = 0) { preferences.contentFilterAllowlist }
    }

    @Test
    fun `the stored block list is threaded into isBlocked`() = runTest {
        every { preferences.contentFilterEnabled } returns flowOf(true)
        every { preferences.contentFilterMode } returns flowOf("HARD_BLOCK")
        every { preferences.contentFilterBlocklist } returns flowOf(setOf("crypto", "stake.com"))
        val passed = slot<ContentFilterBlocklist>()
        coEvery { contentFilter.isBlocked(any(), any(), any(), capture(passed)) } returns false

        useCase.evaluateWebDomain("https://crypto.com/")

        assertEquals(setOf("crypto", "stake.com"), passed.captured.entries)
    }

}
