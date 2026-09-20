package com.astraedus.nudge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.UsageEventKey
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageEventDao {

    @Insert
    suspend fun insert(event: UsageEvent)

    /** Batch insert for restore. Room wraps the list in ONE transaction. */
    @Insert
    suspend fun insertAll(events: List<UsageEvent>)

    /**
     * The whole table, oldest first, as a one-shot list -- for export. Not a `Flow`: an export is a
     * snapshot, and observing the table would keep the rows alive after the file is written.
     */
    @Query("SELECT * FROM usage_events ORDER BY timestamp ASC")
    suspend fun getAllForExport(): List<UsageEvent>

    /**
     * Dedup keys for rows in an inclusive timestamp window -- four columns, not whole rows, and
     * bounded to the span the import file actually covers. Restoring last week's backup must not
     * read years of history to answer a question about last week.
     */
    @Query(
        "SELECT packageName, timestamp, wasBlocked, userChangedMind FROM usage_events " +
            "WHERE timestamp >= :from AND timestamp <= :to"
    )
    suspend fun getKeysInRange(from: Long, to: Long): List<UsageEventKey>

    @Query("SELECT * FROM usage_events WHERE packageName = :pkg AND timestamp >= :since")
    fun getEventsForPackage(pkg: String, since: Long): Flow<List<UsageEvent>>

    @Query("SELECT COUNT(*) FROM usage_events WHERE userChangedMind = 1 AND timestamp >= :since AND timestamp < :until")
    fun getChangedMindCount(since: Long, until: Long): Flow<Int>

    /**
     * Confrontations the user was SHOWN in `[since, until)` — the number every "Blocked" surface
     * displays.
     *
     * `AND userChangedMind = 0` is the SQL mirror of
     * [com.astraedus.nudge.data.db.entity.isShownConfrontation], and it is the whole fix: a
     * walk-away writes a second row carrying `wasBlocked = 1`, so counting that column alone
     * reported every walk-away twice. There is deliberately no raw
     * "count the `wasBlocked` rows" query left to call.
     */
    @Query(
        "SELECT COUNT(*) FROM usage_events " +
            "WHERE wasBlocked = 1 AND userChangedMind = 0 AND timestamp >= :since AND timestamp < :until"
    )
    fun getShownCount(since: Long, until: Long): Flow<Int>

    @Query("SELECT * FROM usage_events WHERE timestamp >= :since")
    fun getEventsSince(since: Long): Flow<List<UsageEvent>>

    /**
     * The newest row id, or null when the table is empty.
     *
     * Exists purely as a CHANGE SIGNAL for the home-screen widgets: Room invalidates on any write
     * to `usage_events`, and `MAX(id)` over the primary key is the cheapest question we can ask
     * that re-emits when a block decision lands. The widgets re-read their real data themselves;
     * this only tells them there is something new to read.
     */
    @Query("SELECT MAX(id) FROM usage_events")
    fun observeLatestEventId(): Flow<Long?>

    /** All-time confrontations shown. Same predicate as [getShownCount], no window. */
    @Query("SELECT COUNT(*) FROM usage_events WHERE wasBlocked = 1 AND userChangedMind = 0")
    fun getAllTimeShownCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM usage_events WHERE userChangedMind = 1")
    fun getAllTimeChangedMindCount(): Flow<Int>

    @Query("DELETE FROM usage_events WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}
