package com.astraedus.nudge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.astraedus.nudge.data.db.entity.UsageEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageEventDao {

    @Insert
    suspend fun insert(event: UsageEvent)

    @Query("SELECT * FROM usage_events WHERE packageName = :pkg AND timestamp >= :since")
    fun getEventsForPackage(pkg: String, since: Long): Flow<List<UsageEvent>>

    @Query("SELECT SUM(durationMs) FROM usage_events WHERE packageName = :pkg AND timestamp >= :since")
    fun getTotalDurationForPackage(pkg: String, since: Long): Flow<Long?>

    @Query("SELECT * FROM usage_events WHERE timestamp >= :dayStart AND timestamp < :dayEnd")
    fun getEventsForDay(dayStart: Long, dayEnd: Long): Flow<List<UsageEvent>>

    @Query("SELECT COUNT(*) FROM usage_events WHERE userChangedMind = 1 AND timestamp >= :since AND timestamp < :until")
    fun getChangedMindCount(since: Long, until: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM usage_events WHERE wasBlocked = 1 AND timestamp >= :since AND timestamp < :until")
    fun getBlockedCount(since: Long, until: Long): Flow<Int>

    @Query("DELETE FROM usage_events WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}
