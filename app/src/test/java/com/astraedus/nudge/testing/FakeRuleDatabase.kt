package com.astraedus.nudge.testing

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.BlockRuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The three rule tables (`block_rules`, `app_groups`, `app_group_members`) in memory, shared by
 * [FakeBlockRuleDao] and [FakeAppGroupDao] exactly as the two Room DAOs share one database: a group
 * delete issued through the group DAO removes rows the rule DAO then no longer sees.
 *
 * Room behaviour it keeps, because tests lean on it:
 *  - an `@Insert` of id 0 takes the next id (never reused, like SQLite AUTOINCREMENT), and an
 *    insert of an id that already exists THROWS (`@Insert` is ABORT);
 *  - `@Update` / `@Delete` match on the primary key and do nothing when it is absent;
 *  - a member row's key is (groupId, packageName); `addMember` REPLACEs;
 *  - `app_group_members.groupId` is a foreign key: adding a member to a missing group throws, and
 *    deleting a group cascades to its members;
 *  - every Flow query re-emits after a write that changes its answer (Room's invalidation).
 *
 * Seed with [seedRule] / [seedGroup] (no repository validation, so a legacy or malformed row can
 * be seeded on purpose), drive the code under test through [repository], assert on [rules] /
 * [groups] / [members].
 */
class FakeRuleDatabase {
    internal val ruleTable = MutableStateFlow<List<BlockRule>>(emptyList())
    internal val groupTable = MutableStateFlow<List<AppGroup>>(emptyList())
    internal val memberTable = MutableStateFlow<List<AppGroupMember>>(emptyList())
    private var lastRuleId = 0L
    private var lastGroupId = 0L

    val ruleDao: FakeBlockRuleDao = FakeBlockRuleDao(this)
    val groupDao: FakeAppGroupDao = FakeAppGroupDao(this)

    /** The real repository over these tables. */
    val repository: BlockRuleRepository = BlockRuleRepository(ruleDao, groupDao)

    /** Every rule row, in id order. */
    val rules: List<BlockRule> get() = ruleTable.value

    /** Every group row, in id order. */
    val groups: List<AppGroup> get() = groupTable.value

    /** Every membership row. */
    val members: List<AppGroupMember> get() = memberTable.value

    fun ruleWithId(id: Long): BlockRule = rules.single { it.id == id }

    fun groupWithId(id: Long): AppGroup = groups.single { it.id == id }

    /** Writes [rule] straight into the table (id assigned when 0) and returns the stored row. */
    fun seedRule(rule: BlockRule): BlockRule {
        val id = insertRuleRow(rule)
        return ruleWithId(id)
    }

    /** Writes [group] (id assigned when 0) with [members] hand-added to it; returns the stored row. */
    fun seedGroup(group: AppGroup, vararg members: String): AppGroup {
        val id = insertGroupRow(group)
        members.forEach { putMember(AppGroupMember(groupId = id, packageName = it)) }
        return groupWithId(id)
    }

    /** Writes one membership row (REPLACE, as `addMember` does). */
    fun seedMember(member: AppGroupMember) = putMember(member)

    // ---- table primitives the two DAOs share ---------------------------------------------------

    internal fun <T> query(table: MutableStateFlow<List<T>>, select: (List<T>) -> List<T>): Flow<List<T>> =
        table.map(select).distinctUntilChanged()

    internal fun insertRuleRow(rule: BlockRule): Long {
        val id = if (rule.id == 0L) lastRuleId + 1 else rule.id
        check(ruleTable.value.none { it.id == id }) { "UNIQUE constraint failed: block_rules.id ($id)" }
        lastRuleId = maxOf(lastRuleId, id)
        ruleTable.value = (ruleTable.value + rule.copy(id = id)).sortedBy { it.id }
        return id
    }

    internal fun updateRuleRows(where: (BlockRule) -> Boolean, change: (BlockRule) -> BlockRule) {
        ruleTable.value = ruleTable.value.map { if (where(it)) change(it) else it }
    }

    internal fun deleteRuleRows(where: (BlockRule) -> Boolean) {
        ruleTable.value = ruleTable.value.filterNot(where)
    }

    internal fun insertGroupRow(group: AppGroup): Long {
        val id = if (group.id == 0L) lastGroupId + 1 else group.id
        check(groupTable.value.none { it.id == id }) { "UNIQUE constraint failed: app_groups.id ($id)" }
        lastGroupId = maxOf(lastGroupId, id)
        groupTable.value = (groupTable.value + group.copy(id = id)).sortedBy { it.id }
        return id
    }

    internal fun updateGroupRows(where: (AppGroup) -> Boolean, change: (AppGroup) -> AppGroup) {
        groupTable.value = groupTable.value.map { if (where(it)) change(it) else it }
    }

    /** Deletes the matching groups and, by the foreign key's ON DELETE CASCADE, their members. */
    internal fun deleteGroupRows(where: (AppGroup) -> Boolean) {
        val gone = groupTable.value.filter(where).map { it.id }.toSet()
        groupTable.value = groupTable.value.filterNot(where)
        deleteMemberRows { it.groupId in gone }
    }

    internal fun putMember(member: AppGroupMember) {
        check(groupTable.value.any { it.id == member.groupId }) {
            "FOREIGN KEY constraint failed: app_group_members.groupId ${member.groupId} has no group"
        }
        memberTable.value = memberTable.value
            .filterNot { it.groupId == member.groupId && it.packageName == member.packageName } + member
    }

    internal fun deleteMemberRows(where: (AppGroupMember) -> Boolean) {
        memberTable.value = memberTable.value.filterNot(where)
    }
}
