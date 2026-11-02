package com.astraedus.nudge.testing

import android.content.Context
import com.astraedus.nudge.data.db.dao.UsageEventDao
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.UsageEventKey
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The REAL [UsageRepository] over an in-memory `usage_events` table and an in-memory screen-time
 * source, for JVM tests.
 *
 * ```
 * val screenTime = FakeScreenTime().apply { todayMs["com.instagram.android"] = 20 * 60_000L }
 * val usage = FakeUsageRepository(screenTime = screenTime)
 * ```
 *
 * The one mock left in here is the platform edge: [ScreenTimeProvider] wraps `UsageStatsManager`
 * and is a concrete class, so it answers from [FakeScreenTime] through a mockk. The [Context] the
 * repository keeps is a STRICT mock it never reads: anything that starts reading it fails loudly.
 */
fun FakeUsageRepository(
    events: FakeUsageEventDao = FakeUsageEventDao(),
    screenTime: FakeScreenTime = FakeScreenTime()
): UsageRepository {
    val provider = mockk<ScreenTimeProvider> {
        every { getPerAppSessionStats(any(), any()) } answers { screenTime.read() }
    }
    return UsageRepository(mockk<Context>(), events, TimeTracker(), provider)
}

/** Today's per-app foreground time, as `ScreenTimeProvider` would report it from UsageStats. */
class FakeScreenTime {
    /** Package to milliseconds in the foreground today. Unlisted packages read as 0. */
    val todayMs: MutableMap<String, Long> = mutableMapOf()

    /**
     * How many times anything asked for screen time. For a "must not even read usage" check, which
     * the old strict mocks expressed as `verify(exactly = 0)`.
     */
    var reads: Int = 0
        private set

    internal fun read(): Map<String, ScreenTimeProvider.SessionStats> {
        reads++
        return todayMs.mapValues { (_, ms) -> ScreenTimeProvider.SessionStats(totalMs = ms, sessionCount = 1) }
    }
}

/**
 * [UsageEventDao] over an in-memory table. Ids autoincrement like Room's. Every `insertAll` call
 * is also kept whole in [insertAllBatches], for tests that pin how a write is batched.
 */
class FakeUsageEventDao(vararg seed: UsageEvent) : UsageEventDao {
    private val table = MutableStateFlow<List<UsageEvent>>(emptyList())
    private var lastId = 0L

    /** Every row, in insertion order. */
    val events: List<UsageEvent> get() = table.value

    /** Each `insertAll` call's argument, in call order. */
    val insertAllBatches: MutableList<List<UsageEvent>> = mutableListOf()

    init {
        seed.forEach { put(it) }
    }

    private fun put(event: UsageEvent) {
        val id = if (event.id == 0L) lastId + 1 else event.id
        check(table.value.none { it.id == id }) { "UNIQUE constraint failed: usage_events.id ($id)" }
        lastId = maxOf(lastId, id)
        table.value = table.value + event.copy(id = id)
    }

    private fun <T> query(select: (List<UsageEvent>) -> T): Flow<T> = table.map(select).distinctUntilChanged()

    override suspend fun insert(event: UsageEvent) = put(event)

    override suspend fun insertAll(events: List<UsageEvent>) {
        insertAllBatches += events.toList()
        events.forEach { put(it) }
    }

    override suspend fun getAllForExport(): List<UsageEvent> = events.sortedBy { it.timestamp }

    override suspend fun getKeysInRange(from: Long, to: Long): List<UsageEventKey> =
        events.filter { it.timestamp in from..to }
            .map { UsageEventKey(it.packageName, it.timestamp, it.wasBlocked, it.userChangedMind) }

    override fun getEventsForPackage(pkg: String, since: Long): Flow<List<UsageEvent>> =
        query { rows -> rows.filter { it.packageName == pkg && it.timestamp >= since } }

    override fun getChangedMindCount(since: Long, until: Long): Flow<Int> =
        query { rows -> rows.count { it.userChangedMind && it.timestamp >= since && it.timestamp < until } }

    override fun getShownCount(since: Long, until: Long): Flow<Int> =
        query { rows ->
            rows.count { it.wasBlocked && !it.userChangedMind && it.timestamp >= since && it.timestamp < until }
        }

    override fun getEventsSince(since: Long): Flow<List<UsageEvent>> =
        query { rows -> rows.filter { it.timestamp >= since } }

    override fun observeLatestEventId(): Flow<Long?> = query { rows -> rows.maxOfOrNull { it.id } }

    override fun getAllTimeShownCount(): Flow<Int> = query { rows -> rows.count { it.wasBlocked && !it.userChangedMind } }

    override fun getAllTimeChangedMindCount(): Flow<Int> = query { rows -> rows.count { it.userChangedMind } }

    override suspend fun deleteOlderThan(timestamp: Long) {
        table.value = table.value.filterNot { it.timestamp < timestamp }
    }
}
