package com.astraedus.nudge.service

import com.astraedus.nudge.MainDispatcherRule
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase
import com.astraedus.nudge.testing.FIXED_EPOCH_MS
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeScreenTime
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * L3: the mid-session daily-limit path for a GROUP member, end to end through the real pieces,
 * issue [#146](https://github.com/astraedus/nudge/issues/146).
 *
 * The bench repro: a one-app group, Hard, 2 min/day; the app left in front ~178 s; never blocked,
 * because the counter cache held no entry for the member, so the foreground clock never started and
 * [TimeRemainingHandler.maybeUpdate] returned on its first line. This drives the same chain the
 * service runs on a clock tick:
 *
 *  1. the service's loader ([CounterCacheCoverage.load]) fills a real [CounterCacheRefresher];
 *  2. the member's entry needs the tick (that is what starts `ForegroundClock`);
 *  3. a tick reaches the real [TimeRemainingHandler], which reads the member's usage and calls
 *     `onTimeLimitExceeded(member, 2)`;
 *  4. the service's `enforceExhaustedBudget` then re-derives through the real
 *     [EvaluateBlockUseCase] on the SAME snapshot and the SAME usage, and must get the hard block,
 *     or it logs `rules_disagree` and shows nothing.
 *
 * Usage is injected (one [FakeUsageRepository] for both halves) and the engine's clock is pinned;
 * nothing reads the wall-clock date.
 *
 * Counterfactual: built with the pre-fix loader (`enabledRules.mapNotNull(appEntryFor)`), step 2
 * fails (no entry), so no tick ever reaches step 3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CounterCacheGroupMidSessionTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val member = "com.instagram.android"
    private val group = 7L
    private val limitMinutes = 2

    private val snapshot = RuleSnapshot.of(
        enabledRules = listOf(
            aBlockRule(groupId = group, mode = BlockMode.HARD_BLOCK.name, dailyLimitMinutes = limitMinutes)
        ),
        groups = listOf(anAppGroup("Focus", id = group)),
        members = listOf(aGroupMember(group, member))
    )

    private val screenTime = FakeScreenTime()
    private val usage = FakeUsageRepository(screenTime = screenTime)

    private val pinnedClock: () -> Calendar = {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = FIXED_EPOCH_MS }
    }

    private val evaluate = EvaluateBlockUseCase(
        ruleSnapshots = object : RuleSnapshots {
            override suspend fun current() = snapshot
            override suspend fun enabledRules() = snapshot.enabledRules
        },
        usageRepository = usage,
        blockEngine = BlockEngine(ScheduleEvaluator(FirstUnlockState.NONE), NudgeLog.NoOp, pinnedClock),
        ruleEvaluator = RuleEvaluator(),
        scheduleEvaluator = ScheduleEvaluator(FirstUnlockState.NONE),
        preferences = FakeNudgePreferences(),
        contentFilter = mockk<ContentFilter>(),
        nukeEnforcement = NukeEnforcement.NEVER,
        essentialPackages = FakeEssentialPackages(),
        packageFacts = FakePackageFacts()
    )

    @Test
    fun `a group member's spent limit fires the clock path, and the re-derive agrees it is a hard block`() = runTest {
        val cache = CounterCacheRefresher()
        cache.forceRefresh {
            CounterCacheCoverage.load(
                snapshot = snapshot,
                readLaunchable = { emptyMap() },
                readEssentials = { emptySet() },
                readBrowsers = { emptySet() }
            )
        }

        val entry = cache.getEntry(member)
        assertTrue("the member has an entry that starts the foreground clock", entry?.needsForegroundTimeTick == true)

        val fired = mutableListOf<Pair<String, Int>>()
        val tickScope = TestScope(dispatcher)
        val handler = TimeRemainingHandler(
            timeRemainingOverlayManager = SilentOverlay(),
            usageRepository = usage,
            preferences = AlwaysOn(),
            counterCache = cache,
            passthroughManager = PassthroughManager(),
            logger = NudgeLog.NoOp,
            serviceScope = tickScope,
            onTimeLimitExceeded = { pkg, limit -> fired += pkg to limit }
        )

        // One second past the group's per-app budget, while still in the app.
        screenTime.todayMs[member] = limitMinutes * 60_000L + 1_000L
        handler.maybeUpdate(member)
        tickScope.advanceUntilIdle()

        assertEquals(listOf(member to limitMinutes), fired)

        // enforceExhaustedBudget's re-derive: same snapshot, same usage.
        val decision = evaluate.invoke(member)
        assertTrue("re-derive must block, got $decision", decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
        assertEquals(limitMinutes, decision.dailyLimitMinutes)
    }

    @Test
    fun `under the budget the tick fires nothing`() = runTest {
        val cache = CounterCacheRefresher()
        cache.forceRefresh {
            CounterCacheCoverage.load(snapshot, { emptyMap() }, { emptySet() }, { emptySet() })
        }
        val fired = mutableListOf<Pair<String, Int>>()
        val tickScope = TestScope(dispatcher)
        val handler = TimeRemainingHandler(
            SilentOverlay(), usage, AlwaysOn(), cache, PassthroughManager(), NudgeLog.NoOp, tickScope
        ) { pkg, limit -> fired += pkg to limit }

        screenTime.todayMs[member] = limitMinutes * 60_000L - 1_000L
        handler.maybeUpdate(member)
        tickScope.advanceUntilIdle()

        assertTrue(fired.isEmpty())
    }

    private class SilentOverlay : TimeRemainingOverlayManagerApi {
        override fun show() = Unit
        override fun hide() = Unit
        override fun isVisible(): Boolean = false
        override fun updateTimeRemaining(remainingMs: Long?, limitMinutes: Int?) = Unit
    }

    private class AlwaysOn : GlobalEnabledProvider {
        override val isGlobalEnabled: Flow<Boolean> = MutableStateFlow(true)
    }
}
