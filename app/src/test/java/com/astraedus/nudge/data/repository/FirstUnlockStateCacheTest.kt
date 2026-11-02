package com.astraedus.nudge.data.repository

import com.astraedus.nudge.domain.engine.FirstUnlockWindow
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.testing.FakeNudgePreferences
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L1. The decision that writes the anchor (spec 5.3).
 *
 * The case worth naming is the first one: the in-memory stamp is [FirstUnlockWindow.NO_STAMP] for
 * the first moments of a process, and device QA found the consequence on the bench phone. The
 * unlock that created the cache saw `0`, decided there was no anchor, and re-stamped, moving the
 * window's end later. So every case here is about the PERSISTED value winning over a cold cache.
 *
 * The preferences are the real class over an in-memory store, not a mock: the cache reads them from
 * a background collector AND from the decision itself, and a mock being touched from both is a race
 * that says nothing about production (it made this file flaky before it was rewritten).
 */
class FirstUnlockStateCacheTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val today: LocalDate = LocalDate.now(zone)
    private val now: Long = at(today, 9, 0)

    private fun cache(
        persistedStamp: Long,
        dayStart: Int = FirstUnlockWindow.DEFAULT_DAY_START_MINUTE
    ) = FakeNudgePreferences {
        setFirstUnlockAt(persistedStamp)
        setDayStartMinute(dayStart)
    }

    @Test
    fun `a cold cache keeps the persisted anchor instead of re-stamping over it`() = runTest {
        // The 08:00 unlock is still today's anchor; a cache that reads 0 must not think otherwise.
        val preferences = cache(persistedStamp = at(today, 8, 0))
        val state = FirstUnlockStateCache(preferences, mockk<NudgeLog>(relaxed = true))

        val stamped = state.stampIfStale(now)

        assertEquals(FirstUnlockWindow.NO_STAMP, stamped)
        assertEquals(at(today, 8, 0), state.firstUnlockAt())
        assertEquals("nothing was written", at(today, 8, 0), preferences.firstUnlockAt.first())
    }

    @Test
    fun `recording keeps a fresh stamp that is already stored`() = runTest {
        // The reviewer's case, one level down: the write path must decide from the STORED value,
        // not from anything the caller believes, so a restart cannot re-stamp today.
        val preferences = cache(persistedStamp = at(today, 8, 0))

        val result = preferences.recordFirstUnlock(now, zone)

        assertEquals(false, result.written)
        assertEquals(at(today, 8, 0), result.stampMs)
        assertEquals(at(today, 8, 0), preferences.firstUnlockAt.first())
    }

    @Test
    fun `no anchor yet stamps this unlock`() = runTest {
        val preferences = cache(persistedStamp = FirstUnlockWindow.NO_STAMP)
        val state = FirstUnlockStateCache(preferences, mockk<NudgeLog>(relaxed = true))

        assertEquals(now, state.stampIfStale(now))
        assertEquals(now, preferences.firstUnlockAt.first())
    }

    @Test
    fun `yesterday's anchor is stale and is replaced`() = runTest {
        val preferences = cache(persistedStamp = at(today.minusDays(1), 8, 0))
        val state = FirstUnlockStateCache(preferences, mockk<NudgeLog>(relaxed = true))

        assertEquals(now, state.stampIfStale(now))
        assertEquals(now, preferences.firstUnlockAt.first())
    }

    @Test
    fun `a future anchor is discarded and replaced`() = runTest {
        // The clock moved backwards: the stored stamp describes a moment that has not happened.
        val preferences = cache(persistedStamp = now + 60 * 60_000L)
        val state = FirstUnlockStateCache(preferences, mockk<NudgeLog>(relaxed = true))

        assertEquals(now, state.stampIfStale(now))
        assertEquals(now, preferences.firstUnlockAt.first())
    }

    @Test
    fun `the persisted day start decides staleness, not the cold cache's default`() = runTest {
        // Day starts at 10:00, so at 09:00 the logical day began YESTERDAY at 10:00 and yesterday's
        // 12:00 stamp is inside it: fresh. A cache that fell back to the 04:00 default would call
        // that stamp stale (today's logical day would have begun at 04:00) and write a new anchor.
        val preferences = cache(
            persistedStamp = at(today.minusDays(1), 12, 0),
            dayStart = 10 * 60
        )
        val state = FirstUnlockStateCache(preferences, mockk<NudgeLog>(relaxed = true))

        assertEquals(FirstUnlockWindow.NO_STAMP, state.stampIfStale(now))
        assertEquals(at(today.minusDays(1), 12, 0), preferences.firstUnlockAt.first())
    }
}
