package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.UsageRepository
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
import com.astraedus.nudge.testing.FakeScreenTime
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for issue #14 ("daily timer/display not working").
 *
 * The daily budget must be spent against real foreground time from `UsageStatsManager`
 * ([UsageRepository.getDailyForegroundTimeMs]). It used to be read from the Room `usage_events`
 * table, whose `durationMs` column was never written (and was deleted in issue #22) — so the
 * number was always 0, the time-budget HARD_BLOCK could never fire, and the overlay's
 * "X left today" was pinned at the full limit no matter how long the app had been used.
 *
 * These tests fail against that old wiring: with a 30-minute limit and 40 minutes on the clock,
 * the old code saw 0ms of usage and returned [BlockDecision.Allow].
 */
class EvaluateBlockDailyLimitTest {

    private val pkg = "com.instagram.android"

    private val db = FakeRuleDatabase()
    private val screenTime = FakeScreenTime()
    private lateinit var contentFilter: ContentFilter
    private lateinit var useCase: EvaluateBlockUseCase

    @Before
    fun setUp() {
        contentFilter = mockk()

        useCase = EvaluateBlockUseCase(
            blockRuleRepository = db.repository,
            usageRepository = FakeUsageRepository(screenTime = screenTime),
            blockEngine = BlockEngine(ScheduleEvaluator()),
            ruleEvaluator = RuleEvaluator(),
            scheduleEvaluator = ScheduleEvaluator(),
            preferences = FakeNudgePreferences(),
            contentFilter = contentFilter,
            nukeEnforcement = NukeEnforcement.NEVER,
            essentialPackages = FakeEssentialPackages(),
            packageFacts = FakePackageFacts()
        )
    }

    /** A DELAY rule carrying a 30 min/day budget. */
    private fun delayRuleWithDailyLimit(limitMinutes: Int = 30) = aBlockRule(
        packageName = pkg,
        mode = BlockMode.DELAY.name,
        dailyLimitMinutes = limitMinutes
    )

    private fun withUsageMinutes(rule: BlockRule, minutes: Long) {
        db.seedRule(rule)
        screenTime.todayMs[pkg] = minutes * 60_000L
    }

    @Test
    fun `budget exhausted hard-blocks instead of delaying`() = runTest {
        withUsageMinutes(delayRuleWithDailyLimit(30), minutes = 40)

        val decision = useCase.invoke(pkg)

        assertTrue("expected a block once the daily budget is spent", decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `budget exactly reached hard-blocks`() = runTest {
        withUsageMinutes(delayRuleWithDailyLimit(30), minutes = 30)

        val decision = useCase.invoke(pkg)

        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    @Test
    fun `budget not yet spent still applies the rule's own mode`() = runTest {
        withUsageMinutes(delayRuleWithDailyLimit(30), minutes = 10)

        val decision = useCase.invoke(pkg)

        assertEquals(BlockMode.DELAY, (decision as BlockDecision.Block).mode)
    }

    /**
     * The overlay's "X left today" line reads this field. Under the old wiring it was always the
     * full limit; it must now shrink as the app is used.
     */
    @Test
    fun `remaining time reflects real foreground usage`() = runTest {
        withUsageMinutes(delayRuleWithDailyLimit(30), minutes = 10)

        val block = useCase.invoke(pkg) as BlockDecision.Block

        assertEquals(30, block.dailyLimitMinutes)
        assertEquals(20 * 60_000L, block.dailyTimeRemainingMs)
    }

    /** Never report negative time left once the user has overshot the budget. */
    @Test
    fun `remaining time floors at zero when overshooting the budget`() = runTest {
        withUsageMinutes(delayRuleWithDailyLimit(30), minutes = 45)

        val block = useCase.invoke(pkg) as BlockDecision.Block

        assertEquals(0L, block.dailyTimeRemainingMs)
    }

    /**
     * Usage Access is a separate grant from the accessibility service. When it is missing the
     * platform read returns 0, and the app must fall back to the rule's normal mode rather than
     * manufacture a block out of a permission the user never gave.
     */
    @Test
    fun `missing usage access does not manufacture a block`() = runTest {
        withUsageMinutes(delayRuleWithDailyLimit(30), minutes = 0)

        val decision = useCase.invoke(pkg)

        assertEquals(BlockMode.DELAY, (decision as BlockDecision.Block).mode)
    }

    /**
     * `getDailyForegroundTimeMs` is a binder call that pulls the whole device's event log for the
     * day and filters it in Kotlin, and it runs on the accessibility hot path. When no rule carries
     * a daily limit the engine cannot consult the number, so it must not be fetched at all. The
     * fake counts every read, so a fetch fails the `reads == 0` assertion below.
     */
    @Test
    fun `usage is not read when no rule has a daily limit`() = runTest {
        db.seedRule(aBlockRule(packageName = pkg, mode = BlockMode.DELAY.name, dailyLimitMinutes = null))

        val decision = useCase.invoke(pkg)

        assertEquals(BlockMode.DELAY, (decision as BlockDecision.Block).mode)
        assertEquals("usage must not be read without a daily limit", 0, screenTime.reads)
    }
}
