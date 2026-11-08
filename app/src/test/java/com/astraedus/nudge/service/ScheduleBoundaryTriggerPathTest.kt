package com.astraedus.nudge.service

import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.toRuleData
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleBoundaries
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.packages.FakeEssentialPackages
import com.astraedus.nudge.domain.packages.FakePackageFacts
import com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase
import com.astraedus.nudge.testing.FakeNudgePreferences
import com.astraedus.nudge.testing.FakeRuleDatabase
import com.astraedus.nudge.testing.FakeScreenTime
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.SourceScan
import com.astraedus.nudge.testing.TestClock
import com.astraedus.nudge.testing.aBlockRule
import io.mockk.mockk
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3, the REAL trigger path for #169: the boundary timer ([ScheduleBoundaryWatch]) armed by the real
 * [ScheduleBoundaries] over real rules, firing into [ScheduleBoundaryWatch.recheckTarget] and the real
 * [EvaluateBlockUseCase] + [BlockEngine], all on one injected clock. Nothing between the timer and
 * the decision is mocked; the fake is the scheduler (the timer's wait is the test advancing the
 * clock) and the active window (a string).
 *
 * The service's glue is the one thing this file models rather than runs: `recheckForegroundAtBoundary`
 * goes through `evaluateForegroundPackage`, whose grant gate runs before the use case. The
 * source contracts at the bottom pin that the real service has exactly that shape, so the model
 * cannot drift from it silently.
 *
 * Before the fix there was no timer: the only evaluation of an app opened at 21:55 under a
 * 22:00-23:00 rule was the one at 21:55 ([counterfactual]).
 */
class ScheduleBoundaryTriggerPathTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val instagram = "com.instagram.android"
    private val minute = 60_000L

    /** Monday 2026-11-09 21:55 UTC: five minutes before an evening window. */
    private val clock = TestClock(
        ZonedDateTime.of(2026, 11, 9, 21, 55, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    )

    private fun calendarAt(ms: Long): Calendar = Calendar.getInstance(utc).apply { timeInMillis = ms }

    private fun utcAt(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 11, 9, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private val db = FakeRuleDatabase()
    private val scheduleEvaluator = ScheduleEvaluator(FirstUnlockState.NONE)

    private val useCase = EvaluateBlockUseCase(
        blockRuleRepository = db.repository,
        usageRepository = FakeUsageRepository(screenTime = FakeScreenTime()),
        blockEngine = BlockEngine(scheduleEvaluator, NudgeLog.NoOp) { calendarAt(clock.now) },
        ruleEvaluator = RuleEvaluator(),
        scheduleEvaluator = scheduleEvaluator,
        preferences = FakeNudgePreferences(),
        contentFilter = mockk<ContentFilter>(),
        nukeEnforcement = NukeEnforcement.NEVER,
        essentialPackages = FakeEssentialPackages(),
        packageFacts = FakePackageFacts()
    )

    private val passthrough = PassthroughManager()
    private val scheduler = FakeBoundaryScheduler(clock)
    private val front = instagram

    /** What the re-check decided at each boundary: null = nothing looked at, else the decision. */
    private val decisions = mutableListOf<BlockDecision?>()

    /**
     * The service's `recheckForegroundAtBoundary` -> `evaluateForegroundPackage`, in its order: the
     * target gate, then the grant gate (`grantLetsThrough`), then the use case. A grant answers
     * Allow here because that is what the service does with it: it returns without a block.
     */
    private fun recheck() {
        val target = ScheduleBoundaryWatch.recheckTarget(
            globalEnabled = true,
            overlayActive = false,
            frontPackage = front,
            ownPackage = OWN
        )
        decisions += when {
            target == null -> null
            passthrough.shouldSkipForegroundEvaluation(target) -> BlockDecision.Allow
            else -> runBlocking { useCase.invoke(target) }
        }
    }

    private val watch = ScheduleBoundaryWatch(
        scheduler = scheduler,
        wallClock = clock.asSource(),
        nextBoundary = { now ->
            ScheduleBoundaries(scheduleEvaluator).next(db.rules.map { it.toRuleData() }, calendarAt(now))
        },
        deviceReady = { true },
        onBoundary = { recheck() },
        logger = NudgeLog.NoOp
    )

    private fun eveningDelayRule() = db.seedRule(
        aBlockRule(
            packageName = instagram,
            mode = BlockMode.DELAY.name,
            delaySeconds = 15,
            scheduleStartMinute = 22 * 60,
            scheduleEndMinute = 23 * 60
        )
    )

    @Test
    fun `a window starting over the open app blocks it at the start, and lets go at the end`() {
        eveningDelayRule()
        // The arrival at 21:55: before the window, nothing to block.
        assertEquals(BlockDecision.Allow, runBlocking { useCase.invoke(instagram) })

        watch.rearm("rules")
        assertEquals("one wait to 22:00", 5 * minute, scheduler.pendingDelayMs)

        scheduler.elapse() // 22:00, the user has not touched the phone
        val atStart = decisions.single() as BlockDecision.Block
        assertEquals(BlockMode.DELAY, atStart.mode)
        assertEquals(15, atStart.delaySeconds)
        assertEquals("the block screen gets its own end (#170)", utcAt(23, 0), atStart.endsAt)

        assertEquals("next wait to the window's end", 60 * minute, scheduler.pendingDelayMs)
        scheduler.elapse() // 23:00
        assertEquals(BlockDecision.Allow, decisions.last())
    }

    /** What the user had before the fix: one evaluation, at the arrival, and nothing at 22:00. */
    @Test
    fun counterfactual() {
        eveningDelayRule()
        assertEquals(BlockDecision.Allow, runBlocking { useCase.invoke(instagram) })
        clock.set(utcAt(22, 30))
        // Without a timer nothing re-asks: the decision on screen is still the 21:55 one. The engine
        // itself WOULD block at 22:30, which is the gap the timer closes.
        assertTrue(runBlocking { useCase.invoke(instagram) } is BlockDecision.Block)
        assertTrue(decisions.isEmpty())
    }

    /**
     * Decision 2 (groups spec 534-537): a pass earned under a Delay rule keeps lasting its sitting
     * when a stricter schedule starts. The boundary re-check goes through the grant gate, so the
     * engine's HARD_BLOCK is never reached while the grant stands; a fresh arrival after the sitting
     * ends meets it.
     */
    @Test
    fun `a pass earned before a stricter schedule starts keeps lasting its sitting`() {
        db.seedRule(aBlockRule(packageName = instagram, mode = BlockMode.DELAY.name, delaySeconds = 15))
        db.seedRule(
            aBlockRule(
                packageName = instagram,
                mode = BlockMode.HARD_BLOCK.name,
                scheduleStartMinute = 22 * 60,
                scheduleEndMinute = 23 * 60
            )
        )
        // 21:55: the Delay rule is met and waited out.
        assertEquals(BlockMode.DELAY, (runBlocking { useCase.invoke(instagram) } as BlockDecision.Block).mode)
        passthrough.grant(instagram)

        watch.rearm("rules")
        scheduler.elapse() // 22:00, the hard block's window opens
        assertEquals("the earned pass is untouched", BlockDecision.Allow, decisions.single())
        // ...and the engine alone would have hard-blocked, so the grant gate is what kept it.
        assertEquals(BlockMode.HARD_BLOCK, (runBlocking { useCase.invoke(instagram) } as BlockDecision.Block).mode)

        // The sitting ends (the user leaves); the next arrival inside the window is hard-blocked.
        passthrough.clear()
        clock.advanceBy(10 * minute)
        watch.onClockChanged("test_next_arrival")
        assertEquals(BlockMode.HARD_BLOCK, (decisions.last() as BlockDecision.Block).mode)
    }

    /** #63, end to end: rules with nothing on a clock arm no timer at all. */
    @Test
    fun `rules that need no clock post no timer`() {
        db.seedRule(aBlockRule(packageName = instagram, mode = BlockMode.DELAY.name))
        watch.rearm("rules")
        assertNull(scheduler.pendingDelayMs)
        assertEquals(0, scheduler.posts)
    }

    /**
     * #63 for the OTHER clock: a schedule-only rule gives its app no per-minute foreground tick.
     * Its one boundary timer is the whole cost.
     */
    @Test
    fun `a schedule-only rule starts no per-minute foreground clock`() {
        val rule = eveningDelayRule()
        assertTrue(CounterCacheRefresher.appEntryFor(rule)?.second?.needsForegroundTimeTick != true)
        watch.rearm("rules")
        assertEquals(1, scheduler.posts)
    }

    // -- the service glue the model above stands for --

    private val service = SourceScan.kotlinFile("com/astraedus/nudge/service/NudgeAccessibilityService.kt")

    @Test
    fun `the boundary re-check is the ordinary evaluation, so every grant is honoured`() {
        val code = SourceScan.code(service)
        val recheck = SourceScan.bodyOf(code, "private fun recheckForegroundAtBoundary()")
        assertTrue("goes through ScheduleBoundaryWatch.recheckTarget", "ScheduleBoundaryWatch.recheckTarget(" in recheck)
        assertTrue("spends the debounce like the Nuke re-check", "lastEvalTime = 0L" in recheck)
        assertTrue("goes through evaluateForegroundPackage", "evaluateForegroundPackage(front)" in recheck)
        assertTrue("never revokes a grant itself", "passthrough" !in recheck && ".clear(" !in recheck)
        val evaluate = SourceScan.bodyOf(code, "private fun evaluateForegroundPackage(packageName: String)")
        val grant = evaluate.indexOf("grantLetsThrough(packageName, passthrough)")
        val engine = evaluate.indexOf("evaluateBlockUseCase().invoke(packageName)")
        assertTrue("the grant gate runs before the engine (decision 2)", grant in 0 until engine)
    }

    @Test
    fun `the watch is wired to its triggers and torn down`() {
        val code = SourceScan.code(service)
        for (call in listOf(
            "boundaryWatch.rearm(\"rules\")",
            "boundaryWatch.rearm(\"global_on\")",
            "boundaryWatch.cancel()",
            "boundaryWatch.rearm(\"first_unlock\")",
            "boundaryWatch.onScreenOn()",
            "boundaryWatch.onUnlocked()",
            "boundaryWatch.onClockChanged(reason)",
            "onBoundary = { recheckForegroundAtBoundary() }",
            "Intent.ACTION_TIME_CHANGED",
            "Intent.ACTION_TIMEZONE_CHANGED",
            "Intent.ACTION_DATE_CHANGED",
            "teardown.step(\"unregister_time_change_receiver\")",
            "teardown.step(\"cancel_schedule_boundary_watch\")",
            "putExtra(BlockOverlayActivity.EXTRA_ENDS_AT, it)"
        )) {
            assertTrue("NudgeAccessibilityService must contain $call (#169/#170)", call in code)
        }
    }

    private companion object {
        val OWN: String = com.astraedus.nudge.BuildConfig.APPLICATION_ID
    }
}
