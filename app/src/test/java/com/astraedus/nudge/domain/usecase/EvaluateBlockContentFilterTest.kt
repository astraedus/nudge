package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.domain.ContentFilterAllowlist
import com.astraedus.nudge.domain.ContentFilterBlocklist
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.FakeUsageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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

    // No rules are seeded -> every evaluation falls through to the filter.
    private val preferences = FakeNudgePreferences()
    private lateinit var contentFilter: ContentFilter
    private lateinit var useCase: EvaluateBlockUseCase

    @Before
    fun setUp() {
        contentFilter = mockk()

        // Strict keywords default to false (opt-in) on the real preferences. Individual tests override.
        useCase = EvaluateBlockUseCase(
            blockRuleRepository = FakeRuleDatabase().repository,
            usageRepository = FakeUsageRepository(),
            blockEngine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE)),
            ruleEvaluator = RuleEvaluator(),
            scheduleEvaluator = ScheduleEvaluator(FirstUnlockState.NONE),
            preferences = preferences,
            contentFilter = contentFilter,
            nukeEnforcement = NukeEnforcement.NEVER,
            essentialPackages = FakeEssentialPackages(),
            packageFacts = FakePackageFacts()
        )
    }

    @Test
    fun `enabled and blocked returns Block with configured HARD_BLOCK mode`() = runTest {
        preferences.setContentFilterEnabled(true)
        preferences.setContentFilterMode("HARD_BLOCK")
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
        preferences.setContentFilterEnabled(true)
        preferences.setContentFilterMode("DELAY")
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.DELAY, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `disabled returns Allow even when filter would block`() = runTest {
        preferences.setContentFilterEnabled(false)
        preferences.setContentFilterMode("HARD_BLOCK")
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Allow)
        assertNull(result.trackingPackage)
    }

    @Test
    fun `enabled but not blocked returns Allow`() = runTest {
        preferences.setContentFilterEnabled(true)
        preferences.setContentFilterMode("HARD_BLOCK")
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns false

        val result = useCase.evaluateWebDomain("https://wikipedia.org")

        assertTrue(result.decision is BlockDecision.Allow)
        assertNull(result.trackingPackage)
    }

    @Test
    fun `invalid mode string falls back to HARD_BLOCK`() = runTest {
        preferences.setContentFilterEnabled(true)
        preferences.setContentFilterMode("NONSENSE")
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://blockedsite.com")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `strict pref TRUE threads strictKeywords=true into isBlocked`() = runTest {
        preferences.setContentFilterEnabled(true)
        preferences.setContentFilterMode("HARD_BLOCK")
        preferences.setContentFilterStrictKeywords(true)
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        useCase.evaluateWebDomain("https://www.google.com/search?q=bbc")

        coVerify { contentFilter.isBlocked("https://www.google.com/search?q=bbc", true, any(), any()) }
    }

    @Test
    fun `strict pref FALSE default threads strictKeywords=false into isBlocked`() = runTest {
        preferences.setContentFilterEnabled(true)
        preferences.setContentFilterMode("HARD_BLOCK")
        // contentFilterStrictKeywords defaults to false via setUp.
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns false

        useCase.evaluateWebDomain("https://www.google.com/search?q=bbc")

        coVerify { contentFilter.isBlocked("https://www.google.com/search?q=bbc", false, any(), any()) }
    }

    // ── "Allowed words & sites" (issue #78) ──

    @Test
    fun `strict on threads the stored allowlist into isBlocked`() = runTest {
        preferences.setContentFilterEnabled(true)
        preferences.setContentFilterMode("HARD_BLOCK")
        preferences.setContentFilterStrictKeywords(true)
        preferences.addContentFilterAllowlistEntry("cnc")
        preferences.addContentFilterAllowlistEntry("cnc-shop.com")
        val passed = slot<ContentFilterAllowlist>()
        coEvery { contentFilter.isBlocked(any(), any(), capture(passed), any()) } returns false

        val result = useCase.evaluateWebDomain("https://www.google.com/search?q=cnc+machine")

        assertEquals(BlockDecision.Allow, result.decision)
        assertEquals(setOf("cnc", "cnc-shop.com"), passed.captured.entries)
    }

    @Test
    fun `strict off never reads the allowlist and passes no exceptions`() = runTest {
        preferences.setContentFilterEnabled(true)
        preferences.setContentFilterMode("HARD_BLOCK")
        // A stored allowlist that would show up in `passed` if the use case read it with strict off.
        preferences.addContentFilterAllowlistEntry("cnc")
        val passed = slot<ContentFilterAllowlist>()
        coEvery { contentFilter.isBlocked(any(), any(), capture(passed), any()) } returns false

        useCase.evaluateWebDomain("https://www.google.com/search?q=cnc")

        assertTrue("no exceptions without strict matching", passed.captured.isEmpty)
    }

    @Test
    fun `the stored block list is threaded into isBlocked`() = runTest {
        preferences.setContentFilterEnabled(true)
        preferences.setContentFilterMode("HARD_BLOCK")
        preferences.addContentFilterBlocklistEntry("crypto")
        preferences.addContentFilterBlocklistEntry("stake.com")
        val passed = slot<ContentFilterBlocklist>()
        coEvery { contentFilter.isBlocked(any(), any(), any(), capture(passed)) } returns false

        useCase.evaluateWebDomain("https://crypto.com/")

        assertEquals(setOf("crypto", "stake.com"), passed.captured.entries)
    }

}
