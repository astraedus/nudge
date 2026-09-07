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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What Nudge is allowed to do to an app **no rule targets**, including in the window right after the
 * OS has killed and restarted the process.
 *
 * The 2026-09-07 report was that Nudge repeatedly surfaced its own UI over Telegram after the
 * nightly backup kill, with a single YouTube rule configured and zero block events ever recorded for
 * Telegram. The absence of those rows is itself evidence about the mechanism: every block this use
 * case decides writes a `UsageEvent`, so a decision made *here* would have left a trail. These tests
 * pin the half that is ours to guarantee — an un-ruled package is never blocked, and a rule set that
 * has not loaded yet is worth nothing rather than everything.
 */
class EvaluateBlockAfterRestartTest {

    private val ruledPackage = "com.google.android.youtube"
    private val unruledPackage = "org.telegram.messenger"

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

        every { blockRuleRepository.getAllGroups() } returns flowOf(emptyList())
        every { usageRepository.getDailyForegroundTimeMs(any()) } returns 0L

        useCase = EvaluateBlockUseCase(
            blockRuleRepository = blockRuleRepository,
            usageRepository = usageRepository,
            blockEngine = BlockEngine(ScheduleEvaluator()),
            ruleEvaluator = RuleEvaluator(),
            preferences = preferences,
            contentFilter = contentFilter
        )
    }

    /** The exact configuration on the device that produced the report: one YouTube DELAY rule. */
    private val youtubeDelayRule = BlockRule(
        id = 1L,
        packageName = ruledPackage,
        mode = BlockMode.DELAY.name,
        delaySeconds = 15,
        enabled = true
    )

    @Test
    fun `an app with no rule is not blocked, even while another app has one`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(listOf(youtubeDelayRule))

        assertTrue(
            "Telegram has no rule and must be allowed",
            useCase.invoke(unruledPackage) is BlockDecision.Allow
        )
        assertTrue(
            "the one configured rule must still fire, or this test proves nothing",
            useCase.invoke(ruledPackage) is BlockDecision.Block
        )
    }

    /**
     * The post-kill window, at its worst: the process has restarted and the rule set has not loaded.
     * An empty rule set must enforce **nothing** — never "block until we know better".
     */
    @Test
    fun `an empty rule set enforces nothing`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(emptyList())

        listOf(unruledPackage, ruledPackage, "com.android.chrome", "com.android.settings")
            .forEach { pkg ->
                assertTrue(
                    "$pkg must be allowed when no rules are loaded",
                    useCase.invoke(pkg) is BlockDecision.Allow
                )
            }
    }

    /**
     * A rule that exists but is disabled is the same as no rule. `getEnabledRules` is the only
     * source, so this is really a guard against someone widening it to `getAllRules` for
     * convenience — which would resurrect every rule a user has ever switched off.
     */
    @Test
    fun `a disabled rule blocks nothing`() = runTest {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(emptyList())

        assertTrue(useCase.invoke(ruledPackage) is BlockDecision.Allow)
    }
}
