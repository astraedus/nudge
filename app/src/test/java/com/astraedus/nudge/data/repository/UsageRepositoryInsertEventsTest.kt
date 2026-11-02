package com.astraedus.nudge.data.repository

import com.astraedus.nudge.testing.FakeUsageEventDao
import com.astraedus.nudge.testing.FakeUsageRepository
import com.astraedus.nudge.testing.aUsageEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private val dao = FakeUsageEventDao()
    private val repository = FakeUsageRepository(events = dao)

    private fun importedEvent(i: Int) = aUsageEvent(
        packageName = "app.$i",
        timestamp = 1_000L + i,
        wasBlocked = true,
        blockMode = "DELAY"
    )

    @Test
    fun `a large import is a single DAO call, never chunked`() = runTest {
        repository.insertEvents((1..1_200).map { importedEvent(it) })

        assertEquals(1, dao.insertAllBatches.size)
        assertEquals(1_200, dao.insertAllBatches.single().size)
        assertEquals(1_200, dao.events.size)
    }

    @Test
    fun `an empty import makes no DAO call at all`() = runTest {
        repository.insertEvents(emptyList())

        assertTrue(dao.insertAllBatches.isEmpty())
    }
}
