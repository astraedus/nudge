package com.astraedus.nudge.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import kotlinx.coroutines.flow.Flow

/**
 * Member-set difference between two versions of a group: what to add and what to remove.
 * Pure so the set arithmetic is unit-testable without a database.
 */
internal data class MembershipChanges(val toAdd: Set<String>, val toRemove: Set<String>)

internal fun membershipChanges(old: Set<String>, new: Set<String>): MembershipChanges =
    MembershipChanges(toAdd = new - old, toRemove = old - new)

/**
 * An abstract class (not an interface) so the multi-table group writes can be `@Transaction`
 * methods (Room runs an `open` DAO method with a body inside a transaction). The rule statements live here too because a group and its rule must
 * commit or roll back together; Room allows `@Insert`/`@Update`/`@Query` on any table from any DAO.
 */
@Dao
abstract class AppGroupDao {

    @Query("SELECT * FROM app_groups")
    abstract fun getAll(): Flow<List<AppGroup>>

    @Query("SELECT * FROM app_groups WHERE id = :id")
    abstract fun getById(id: Long): Flow<AppGroup?>

    @Insert
    abstract suspend fun insert(group: AppGroup): Long

    @Update
    abstract suspend fun update(group: AppGroup)

    @Delete
    abstract suspend fun delete(group: AppGroup)

    @Query("SELECT * FROM app_group_members WHERE groupId = :groupId")
    abstract fun getMembersForGroup(groupId: Long): Flow<List<AppGroupMember>>

    /**
     * Every membership row in one query. The engine's rule snapshot (`RuleSnapshotSource`) watches
     * this instead of opening one [getMembersForGroup] query per group on every evaluation.
     */
    @Query("SELECT * FROM app_group_members")
    abstract fun getAllMembers(): Flow<List<AppGroupMember>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addMember(member: AppGroupMember)

    @Query("DELETE FROM app_group_members WHERE groupId = :groupId AND packageName = :packageName")
    abstract suspend fun removeMember(groupId: Long, packageName: String)

    @Query(
        """
        SELECT ag.* FROM app_groups ag
        INNER JOIN app_group_members agm ON ag.id = agm.groupId
        WHERE agm.packageName = :packageName
        """
    )
    abstract fun getGroupsForPackage(packageName: String): Flow<List<AppGroup>>

    @Query("SELECT packageName FROM app_group_members WHERE groupId = :groupId")
    abstract suspend fun getMemberPackages(groupId: Long): List<String>

    @Query("UPDATE app_groups SET name = :name WHERE id = :groupId")
    abstract suspend fun renameGroup(groupId: Long, name: String)

    @Query("DELETE FROM app_group_members WHERE groupId = :groupId")
    abstract suspend fun deleteMembersOfGroup(groupId: Long)

    @Query("DELETE FROM app_groups WHERE id = :groupId")
    abstract suspend fun deleteGroupById(groupId: Long)

    @Query("DELETE FROM block_rules WHERE groupId = :groupId")
    abstract suspend fun deleteRulesOfGroup(groupId: Long)

    @Insert
    abstract suspend fun insertGroupRule(rule: BlockRule): Long

    @Update
    abstract suspend fun updateGroupRule(rule: BlockRule)

    /**
     * Creates (groupId == null) or updates a group, replaces its member set and writes its rule,
     * all in one transaction. [rule] is stored with `groupId` = the group and `packageName` = null.
     */
    @Transaction
    open suspend fun saveGroupWithRule(
        groupId: Long?,
        name: String,
        members: Set<String>,
        rule: BlockRule
    ): Long {
        val id: Long
        val existing: Set<String>
        if (groupId == null) {
            id = insert(AppGroup(name = name))
            existing = emptySet()
        } else {
            id = groupId
            renameGroup(id, name)
            existing = getMemberPackages(id).toSet()
        }
        val changes = membershipChanges(existing, members)
        changes.toRemove.forEach { removeMember(id, it) }
        changes.toAdd.forEach { addMember(AppGroupMember(id, it)) }

        val groupRule = rule.copy(groupId = id, packageName = null)
        if (groupRule.id == 0L) insertGroupRule(groupRule) else updateGroupRule(groupRule)
        return id
    }

    /** Deletes the group, its member rows and every rule targeting it, in one transaction. */
    @Transaction
    open suspend fun deleteGroupAndRules(groupId: Long) {
        deleteRulesOfGroup(groupId)
        deleteMembersOfGroup(groupId)
        deleteGroupById(groupId)
    }
}
