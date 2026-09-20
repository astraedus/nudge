package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.logging.NudgeLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk-away write contract — the single source of the home screen's "Walked Away" tile and of
 * every number on the Willpower insight page.
 *
 * Device evidence that motivated pinning this (Pixel 3, 2026-08-20): across 11 clean walk-aways the
 * DB showed **two** `wasBlocked = 1` rows per attempt — the overlay-shown row written by
 * `NudgeAccessibilityService.handleDecision`, plus the walk-away row written here — and the home
 * screen read Blocked 9 / Walked Away 4 off exactly those rows -- the tile counting a walk-away
 * as two blocks, which was reported again on 1.17.2 and fixed there. The two-row shape itself is
 * deliberate; what reads it must exclude this row, which every "Blocked" count now does via
 * [com.astraedus.nudge.data.db.entity.isShownConfrontation] and its SQL mirror in `UsageEventDao`.
 * So the row shape is a cross-file contract and belongs in a test rather than in a comment.
 */
class RecordWalkAwayUseCaseTest {

    private val pkg = "com.google.android.calculator"

    // --- the row shape (pure) ------------------------------------------------------------------

    @Test
    fun `a walk-away is a blocked event flagged as changed-mind`() {
        val event = RecordWalkAwayUseCase.buildEvent(pkg, "DELAY")

        assertEquals(pkg, event.packageName)
        assertEquals("DELAY", event.blockMode)
        assertTrue("walk-away must count as a block attempt", event.wasBlocked)
        assertTrue("walk-away must be flagged, else it is indistinguishable from giving in", event.userChangedMind)
    }

    /**
     * Regression guard for the reported bug: "Walked Away" stayed at 0 while blocks kept counting.
     * A row with `userChangedMind = false` is invisible to `getChangedMindCount`, which is exactly
     * the symptom, so the flag is asserted independently of everything else on the row.
     */
    @Test
    fun `changed-mind flag is set for every block mode, not just hard blocks`() {
        // The device DB carried 13 historical walk-aways and every one of them was HARD_BLOCK;
        // DELAY walk-aways must produce an identically-flagged row.
        listOf("DELAY", "HARD_BLOCK", "BREATHING", "").forEach { mode ->
            val event = RecordWalkAwayUseCase.buildEvent(pkg, mode)
            assertTrue("mode=$mode must still flag userChangedMind", event.userChangedMind)
            assertTrue("mode=$mode must still count as blocked", event.wasBlocked)
        }
    }

    /**
     * The overlay reads its package from the intent and falls back to "" when the extra is missing.
     * A blank package must still produce a countable row: `getChangedMindCount` has no package
     * filter, so losing the row entirely would be strictly worse than an unattributed one.
     */
    @Test
    fun `a missing package still produces a countable walk-away`() {
        val event = RecordWalkAwayUseCase.buildEvent("", "DELAY")

        assertTrue(event.userChangedMind)
        assertEquals("", event.packageName)
    }

    @Test
    fun `timestamp is stamped so the row lands in today's bucket`() {
        val before = System.currentTimeMillis()
        val event = RecordWalkAwayUseCase.buildEvent(pkg, "DELAY")
        val after = System.currentTimeMillis()

        // getChangedMindCount filters `timestamp >= dayStart AND < dayEnd`; a zero/default stamp
        // would silently drop the walk-away out of the "Today" tile.
        assertTrue(event.timestamp in before..after)
    }

    // --- the write ----------------------------------------------------------------------------

    @Test
    fun `record inserts exactly one event`() = runTest {
        val repo = mockk<UsageRepository>()
        val captured = slot<UsageEvent>()
        coEvery { repo.logEvent(capture(captured)) } returns Unit

        RecordWalkAwayUseCase(repo, NudgeLog.NoOp).recordNow(pkg, "DELAY")

        coVerify(exactly = 1) { repo.logEvent(any()) }
        assertTrue(captured.captured.userChangedMind)
        assertTrue(captured.captured.wasBlocked)
        assertEquals("DELAY", captured.captured.blockMode)
    }

    /**
     * A failing insert must surface as a logged error, not as a thrown exception. The old code ran
     * the insert in an unstructured `CoroutineScope(Dispatchers.IO).launch { … }` with no handler,
     * so a Room failure would have reached the default uncaught-exception handler and killed the
     * process — taking the accessibility service, and therefore all blocking, down with it. Losing
     * one stat row must never do that.
     */
    @Test
    fun `an insert failure is reported to the logger, never swallowed silently`() = runTest {
        val repo = mockk<UsageRepository>()
        coEvery { repo.logEvent(any()) } throws IllegalStateException("db closed")
        val logger = RecordingLog()

        val useCase = RecordWalkAwayUseCase(repo, logger)
        useCase.record(pkg, "DELAY")

        // `record` hands off to the singleton scope; give it a moment to run and fail.
        val deadline = System.currentTimeMillis() + 2_000
        while (logger.errors.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals(1, logger.errors.size)
        assertTrue(logger.errors.single().contains("walk-away"))
    }

    @Test
    fun `a successful write is logged so a missing row is diagnosable from logcat`() = runTest {
        val repo = mockk<UsageRepository>()
        coEvery { repo.logEvent(any()) } returns Unit
        val logger = RecordingLog()

        RecordWalkAwayUseCase(repo, logger).recordNow(pkg, "DELAY")

        assertTrue(logger.infos.any { it.contains("walk-away recorded") && it.contains(pkg) })
    }

    private class RecordingLog : NudgeLog {
        val infos = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun d(message: String, throwable: Throwable?) = Unit
        override fun i(message: String, throwable: Throwable?) { infos += message }
        override fun w(message: String, throwable: Throwable?) = Unit
        override fun e(message: String, throwable: Throwable?) { errors += message }
    }
}
