package com.astraedus.nudge.data.repository

import android.content.Context
import com.astraedus.nudge.data.db.dao.UsageEventDao
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.domain.engine.TimeTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the atomicity contract of [UsageRepository.insertEvents]: the whole import lands in
 * EXACTLY ONE DAO call, because one Room `@Insert` call is one transaction. History dedup on
 * re-import is by key MEMBERSHIP ([com.astraedus.nudge.data.export.HistoryMerge.selectNew]),
 * so a partially-persisted import would make every later re-import of the same file skip a
 * whole key group — the unstored copy of a legitimate intra-file duplicate would be
 * permanently unrecoverable. Splitting this into multiple DAO calls (the old 500-row
 * chunking) reintroduces exactly that crash window.
 */
class UsageRepositoryInsertEventsTest {

    private val dao = mockk<UsageEventDao>()
    private val repository = UsageRepository(
        context = mockk<Context>(relaxed = true),
        usageEventDao = dao,
        timeTracker = mockk<TimeTracker>(relaxed = true),
        screenTimeProvider = mockk<ScreenTimeProvider>(relaxed = true)
    )

    private fun event(i: Int) = UsageEvent(
        packageName = "app.$i",
        timestamp = 1_000L + i,
        wasBlocked = true,
        blockMode = "DELAY",
        userChangedMind = false
    )

    @Test
    fun `a large import is a single DAO call, never chunked`() = runTest {
        val captured = mutableListOf<List<UsageEvent>>()
        coEvery { dao.insertAll(capture(captured)) } returns Unit

        val events = (1..1_200).map { event(it) }
        repository.insertEvents(events)

        coVerify(exactly = 1) { dao.insertAll(any()) }
        assertEquals(1_200, captured.single().size)
    }

    @Test
    fun `an empty import makes no DAO call at all`() = runTest {
        repository.insertEvents(emptyList())

        coVerify(exactly = 0) { dao.insertAll(any()) }
    }
}
