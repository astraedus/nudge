package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.engine.FirstUnlockWindow
import com.astraedus.nudge.domain.logging.NudgeLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
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
 */
class FirstUnlockStateCacheTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val today: LocalDate = LocalDate.now(zone)
    private val now: Long = at(today, 9, 0)

    private fun cache(persistedStamp: Long, dayStart: Int = 240): Pair<FirstUnlockStateCache, NudgePreferences> {
        val preferences = mockk<NudgePreferences>(relaxed = true)
        every { preferences.firstUnlockAt } returns flowOf(persistedStamp)
        every { preferences.dayStartMinute } returns flowOf(dayStart)
        coEvery { preferences.setFirstUnlockAt(any()) } just Runs
        return FirstUnlockStateCache(preferences, mockk<NudgeLog>(relaxed = true)) to preferences
    }

    @Test
    fun `a cold cache keeps the persisted anchor instead of re-stamping over it`() = runTest {
        // The 08:00 unlock is still today's anchor; a cache that reads 0 must not think otherwise.
        val (state, preferences) = cache(persistedStamp = at(today, 8, 0))

        val stamped = state.stampIfStale(now)

        assertEquals(FirstUnlockWindow.NO_STAMP, stamped)
        assertEquals(at(today, 8, 0), state.firstUnlockAt())
        coVerify(exactly = 0) { preferences.setFirstUnlockAt(any()) }
    }

    @Test
    fun `no anchor yet stamps this unlock`() = runTest {
        val (state, preferences) = cache(persistedStamp = FirstUnlockWindow.NO_STAMP)

        val stamped = state.stampIfStale(now)

        assertEquals(now, stamped)
        assertEquals(now, state.firstUnlockAt())
        coVerify(exactly = 1) { preferences.setFirstUnlockAt(now) }
    }

    @Test
    fun `yesterday's anchor is stale and is replaced`() = runTest {
        val (state, preferences) = cache(persistedStamp = at(today.minusDays(1), 8, 0))

        assertEquals(now, state.stampIfStale(now))
        coVerify(exactly = 1) { preferences.setFirstUnlockAt(now) }
    }

    @Test
    fun `a future anchor is discarded and replaced`() = runTest {
        // The clock moved backwards: the stored stamp describes a moment that has not happened.
        val (state, preferences) = cache(persistedStamp = now + 60 * 60_000L)

        assertEquals(now, state.stampIfStale(now))
        coVerify(exactly = 1) { preferences.setFirstUnlockAt(now) }
    }

    @Test
    fun `the persisted day start decides staleness, not the cold cache's default`() = runTest {
        // Day starts at 10:00, so at 09:00 the logical day began YESTERDAY at 10:00 and yesterday's
        // 12:00 stamp is inside it: fresh. A cache that fell back to the 04:00 default would call
        // that stamp stale (today's logical day would have begun at 04:00) and write a new anchor.
        val (state, preferences) = cache(
            persistedStamp = at(today.minusDays(1), 12, 0),
            dayStart = 10 * 60
        )

        assertEquals(FirstUnlockWindow.NO_STAMP, state.stampIfStale(now))
        coVerify(exactly = 0) { preferences.setFirstUnlockAt(any()) }
    }
}
