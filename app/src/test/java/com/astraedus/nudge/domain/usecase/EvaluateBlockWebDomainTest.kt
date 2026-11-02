package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.data.repository.ContentFilter
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
import com.astraedus.nudge.testing.aBlockRule
import io.mockk.coEvery
import io.mockk.mockk
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
 * rules, usage and preferences are the shared fakes; only the content filter is mocked.
 */
class EvaluateBlockWebDomainTest {

    private val instagram = "com.instagram.android"
    private val db = FakeRuleDatabase()
    private val preferences = FakeNudgePreferences()
    private lateinit var contentFilter: ContentFilter
    private lateinit var useCase: EvaluateBlockUseCase

    @Before
    fun setUp() {
        contentFilter = mockk()

        // The real preferences ship the content filter off, strict keywords off, mode HARD_BLOCK.
        useCase = EvaluateBlockUseCase(
            blockRuleRepository = db.repository,
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
    fun `THE REGRESSION -- app mode NONE with webBlockMode HARD_BLOCK still blocks the site`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "NONE", webDomains = "instagram.com", webBlockMode = "HARD_BLOCK"))

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `DELAY app mode with null webBlockMode blocks with DELAY and the rule's delaySeconds`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "DELAY", webDomains = "instagram.com", delaySeconds = 20))

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        val block = result.decision as BlockDecision.Block
        assertEquals(BlockMode.DELAY, block.mode)
        assertEquals(20, block.delaySeconds)
    }

    @Test
    fun `HARD_BLOCK app mode with null webBlockMode blocks with HARD_BLOCK`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "HARD_BLOCK", webDomains = "instagram.com"))

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `BREATHING app mode with null webBlockMode blocks with BREATHING`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "BREATHING", webDomains = "instagram.com", delaySeconds = 25))

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        val block = result.decision as BlockDecision.Block
        assertEquals(BlockMode.BREATHING, block.mode)
        assertEquals(25, block.delaySeconds)
    }

    @Test
    fun `webBlockMode overrides a real app mode -- HARD_BLOCK app mode, DELAY web mode`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "HARD_BLOCK", webDomains = "instagram.com", webBlockMode = "DELAY"))

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals(BlockMode.DELAY, (result.decision as BlockDecision.Block).mode)
    }

    @Test
    fun `mode NONE with null webBlockMode -- un-migrated inherit case -- enforces nothing and falls through, filter ON blocks as Restricted content`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "NONE", webDomains = "instagram.com", webBlockMode = null))
        preferences.setContentFilterEnabled(true)
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals("Restricted content", (result.decision as BlockDecision.Block).ruleName)
    }

    @Test
    fun `mode NONE with null webBlockMode -- filter OFF -- falls through to Allow, not treated as handled`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "NONE", webDomains = "instagram.com", webBlockMode = null))
        preferences.setContentFilterEnabled(false)

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Allow)
    }

    @Test
    fun `non-matching URL falls through to the content filter`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "HARD_BLOCK", webDomains = "instagram.com"))
        preferences.setContentFilterEnabled(true)
        coEvery { contentFilter.isBlocked(any(), any(), any(), any()) } returns true

        val result = useCase.evaluateWebDomain("https://example.com")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals("Restricted content", (result.decision as BlockDecision.Block).ruleName)
    }

    @Test
    fun `trackingPackage is the matching rule's packageName`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "HARD_BLOCK", webDomains = "instagram.com"))

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertEquals("com.instagram.android", result.trackingPackage)
    }

    @Test
    fun `trackingPackage falls back to web when the rule has no packageName`() = runTest {
        db.seedRule(aBlockRule(packageName = null, mode = "HARD_BLOCK", webDomains = "instagram.com"))

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertEquals("web", result.trackingPackage)
    }

    @Test
    fun `ruleName reflects the resolved web mode for an app-mode-NONE plus web-HARD_BLOCK rule`() = runTest {
        db.seedRule(aBlockRule(packageName = instagram, mode = "NONE", webDomains = "instagram.com", webBlockMode = "HARD_BLOCK"))

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Block)
        assertEquals("Web - Hard Block", (result.decision as BlockDecision.Block).ruleName)
    }

    @Test
    fun `no rules at all falls through to content filter and can Allow`() = runTest {
        preferences.setContentFilterEnabled(false)

        val result = useCase.evaluateWebDomain("https://www.instagram.com/reels")

        assertTrue(result.decision is BlockDecision.Allow)
        assertNull(result.trackingPackage)
    }
}
