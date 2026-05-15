package com.astraedus.nudge.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import kotlinx.coroutines.flow.Flow

@Dao
interface AppGroupDao {

    @Query("SELECT * FROM app_groups")
    fun getAll(): Flow<List<AppGroup>>

    @Query("SELECT * FROM app_groups WHERE id = :id")
    fun getById(id: Long): Flow<AppGroup?>

    @Insert
    suspend fun insert(group: AppGroup): Long

    @Update
    suspend fun update(group: AppGroup)

    @Delete
    suspend fun delete(group: AppGroup)

    @Query("SELECT * FROM app_group_members WHERE groupId = :groupId")
    fun getMembersForGroup(groupId: Long): Flow<List<AppGroupMember>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMember(member: AppGroupMember)

    @Query("DELETE FROM app_group_members WHERE groupId = :groupId AND packageName = :packageName")
    suspend fun removeMember(groupId: Long, packageName: String)

    @Query(
        """
        SELECT ag.* FROM app_groups ag
        INNER JOIN app_group_members agm ON ag.id = agm.groupId
        WHERE agm.packageName = :packageName
        """
    )
    fun getGroupsForPackage(packageName: String): Flow<List<AppGroup>>
}
