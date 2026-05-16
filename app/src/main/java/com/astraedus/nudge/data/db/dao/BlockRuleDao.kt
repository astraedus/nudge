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
}
