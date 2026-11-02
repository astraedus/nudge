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
import com.astraedus.nudge.domain.model.ExceptCsv
import kotlinx.coroutines.flow.Flow

/**
 * Member-row difference between two versions of a group: the rows to write (new, or flipped between
 * "added" and "Removed by you") and the packages whose row goes. A row is package -> `excluded`.
 * Pure so the arithmetic is unit-testable without a database.
 */
internal data class MemberRowChanges(val toWrite: Map<String, Boolean>, val toRemove: Set<String>)

internal fun memberRowChanges(old: Map<String, Boolean>, new: Map<String, Boolean>): MemberRowChanges =
    MemberRowChanges(
        toWrite = new.filter { (pkg, excluded) -> old[pkg] != excluded },
        toRemove = old.keys - new.keys
    )

/**
 * The member rows a group with [includes] and [excludes] stores: package -> `excluded`. The two sets
 * must not overlap (the primary key holds one row per package); a caller that lets them is a bug.
 */
internal fun memberRows(includes: Set<String>, excludes: Set<String>): Map<String, Boolean> {
    require(includes.none { it in excludes }) { "a package is either added or removed, not both" }
    return includes.associateWith { false } + excludes.associateWith { true }
}

/**
 * The `exceptGroupIds` rewrites that remove [groupId] from every rule in [rules] that lists it as
 * allowed: rule id -> the new CSV (null once the list is empty). Rules that do not list it are left
 * out, so nothing else is written. Pure so the CSV arithmetic is unit-testable without a database.
 */
internal fun exceptGroupIdsWithout(groupId: Long, rules: List<BlockRule>): Map<Long, String?> =
    rules.mapNotNull { rule ->
        val ids = ExceptCsv.groupIds(rule.exceptGroupIds)
        if (groupId in ids) rule.id to ExceptCsv.ofGroupIds(ids - groupId) else null
    }.toMap()

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

    /**
     * The groups [packageName] was ADDED to by hand (`excluded` = 0). Not its full membership: a
     * category group holds its category apps with no row (spec 5.2), and only
     * `GroupMembershipResolver` answers that.
     */
    @Query(
        """
        SELECT ag.* FROM app_groups ag
        INNER JOIN app_group_members agm ON ag.id = agm.groupId
        WHERE agm.packageName = :packageName AND agm.excluded = 0
        """
    )
    abstract fun getGroupsForPackage(packageName: String): Flow<List<AppGroup>>

    @Query("SELECT * FROM app_group_members WHERE groupId = :groupId")
    abstract suspend fun getMemberRows(groupId: Long): List<AppGroupMember>

    @Query("UPDATE app_groups SET name = :name WHERE id = :groupId")
    abstract suspend fun renameGroup(groupId: Long, name: String)

    @Query("UPDATE app_groups SET categories = :categories WHERE id = :groupId")
    abstract suspend fun setCategories(groupId: Long, categories: String?)

    @Query("DELETE FROM app_group_members WHERE groupId = :groupId")
    abstract suspend fun deleteMembersOfGroup(groupId: Long)

    @Query("DELETE FROM app_groups WHERE id = :groupId")
    abstract suspend fun deleteGroupById(groupId: Long)

    @Query("DELETE FROM block_rules WHERE groupId = :groupId")
    abstract suspend fun deleteRulesOfGroup(groupId: Long)

    /** Every rule that lists at least one allowed group (All apps rules, spec 3.4). */
    @Query("SELECT * FROM block_rules WHERE exceptGroupIds IS NOT NULL")
    abstract suspend fun getRulesWithExceptGroups(): List<BlockRule>

    @Query("UPDATE block_rules SET exceptGroupIds = :exceptGroupIds WHERE id = :ruleId")
    abstract suspend fun setExceptGroupIds(ruleId: Long, exceptGroupIds: String?)

    @Insert
    abstract suspend fun insertGroupRule(rule: BlockRule): Long

    @Update
    abstract suspend fun updateGroupRule(rule: BlockRule)

    /**
     * Creates (groupId == null) or updates a group: its name, its [categories] (CSV, null for none),
     * its member rows ([includes] added by hand, [excludes] "Removed by you", replaced as a whole)
     * and its rule, all in one transaction. [rule] is stored with `groupId` = the group and
     * `packageName` = null.
     */
    @Transaction
    open suspend fun saveGroupWithRule(
        groupId: Long?,
        name: String,
        categories: String?,
        includes: Set<String>,
        excludes: Set<String>,
        rule: BlockRule
    ): Long {
        val rows = memberRows(includes, excludes)
        val id: Long
        val existing: Map<String, Boolean>
        if (groupId == null) {
            id = insert(AppGroup(name = name, categories = categories))
            existing = emptyMap()
        } else {
            id = groupId
            renameGroup(id, name)
            setCategories(id, categories)
            existing = getMemberRows(id).associate { it.packageName to it.excluded }
        }
        val changes = memberRowChanges(existing, rows)
        changes.toRemove.forEach { removeMember(id, it) }
        changes.toWrite.forEach { (pkg, excluded) -> addMember(AppGroupMember(id, pkg, excluded)) }

        val groupRule = rule.copy(groupId = id, packageName = null)
        if (groupRule.id == 0L) insertGroupRule(groupRule) else updateGroupRule(groupRule)
        return id
    }

    /**
     * Deletes the group, its member rows and every rule targeting it, and takes its id off every
     * All apps rule's allowed groups, in one transaction. A stale id would allow nothing (the
     * engine fails strict), but left behind it is a dangling reference every reader of the list
     * (editor, block screen, Strict Mode, export) would have to know to ignore.
     */
    @Transaction
    open suspend fun deleteGroupAndRules(groupId: Long) {
        deleteRulesOfGroup(groupId)
        deleteMembersOfGroup(groupId)
        deleteGroupById(groupId)
        exceptGroupIdsWithout(groupId, getRulesWithExceptGroups())
            .forEach { (ruleId, csv) -> setExceptGroupIds(ruleId, csv) }
    }
}
