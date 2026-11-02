package com.astraedus.nudge.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.astraedus.nudge.data.db.entity.BlockRule
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockRuleDao {

    @Query("SELECT * FROM block_rules")
    fun getAll(): Flow<List<BlockRule>>

    @Query("SELECT * FROM block_rules WHERE enabled = 1")
    fun getEnabled(): Flow<List<BlockRule>>

    @Query("SELECT * FROM block_rules WHERE packageName = :pkg")
    fun getByPackage(pkg: String): Flow<List<BlockRule>>

    @Query("SELECT * FROM block_rules WHERE groupId = :groupId")
    fun getByGroupId(groupId: Long): Flow<List<BlockRule>>

    /** Every All apps rule (spec 3.2 "All apps" section, the Home lock card), on or off. */
    @Query("SELECT * FROM block_rules WHERE allApps = 1 ORDER BY id")
    fun getAllAppsRules(): Flow<List<BlockRule>>

    @Query("SELECT * FROM block_rules WHERE id = :id")
    suspend fun getById(id: Long): BlockRule?

    @Insert
    suspend fun insert(rule: BlockRule): Long

    @Update
    suspend fun update(rule: BlockRule)

    @Delete
    suspend fun delete(rule: BlockRule)

    @Query("DELETE FROM block_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM block_rules WHERE packageName = :packageName")
    suspend fun deleteByPackageName(packageName: String)

    /**
     * Switch a package's rules on or off, arming them when they come on (spec 5.3): the CASE leaves
     * `armedAt` untouched on a disable and sets it to [now] on an enable, so a first-unlock rule
     * switched on at 08:05 waits for tomorrow's unlock instead of firing on this morning's.
     */
    @Query(
        "UPDATE block_rules SET enabled = :enabled, " +
            "armedAt = CASE WHEN :enabled THEN :now ELSE armedAt END " +
            "WHERE packageName = :packageName"
    )
    suspend fun setEnabledForPackage(packageName: String, enabled: Boolean, now: Long)
}
