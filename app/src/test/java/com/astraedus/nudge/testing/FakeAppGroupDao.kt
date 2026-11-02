package com.astraedus.nudge.testing

import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [AppGroupDao] over [FakeRuleDatabase]'s tables. Only the abstract primitives are faked: the
 * DAO's `@Transaction` methods (`saveGroupWithRule`, `deleteGroupAndRules`) are inherited and run
 * their PRODUCTION bodies on top of these. Build one through [FakeRuleDatabase].
 */
class FakeAppGroupDao internal constructor(private val db: FakeRuleDatabase) : AppGroupDao() {

    override fun getAll(): Flow<List<AppGroup>> = db.query(db.groupTable) { it }

    override fun getById(id: Long): Flow<AppGroup?> =
        db.groupTable.map { rows -> rows.firstOrNull { it.id == id } }.distinctUntilChanged()

    override suspend fun insert(group: AppGroup): Long = db.insertGroupRow(group)

    override suspend fun update(group: AppGroup) = db.updateGroupRows({ it.id == group.id }) { group }

    override suspend fun delete(group: AppGroup) = db.deleteGroupRows { it.id == group.id }

    override fun getMembersForGroup(groupId: Long): Flow<List<AppGroupMember>> =
        db.query(db.memberTable) { rows -> rows.filter { it.groupId == groupId } }

    override fun getAllMembers(): Flow<List<AppGroupMember>> = db.query(db.memberTable) { it }

    override suspend fun addMember(member: AppGroupMember) = db.putMember(member)

    override suspend fun removeMember(groupId: Long, packageName: String) =
        db.deleteMemberRows { it.groupId == groupId && it.packageName == packageName }

    override fun getGroupsForPackage(packageName: String): Flow<List<AppGroup>> =
        combine(db.groupTable, db.memberTable) { groups, members ->
            val ids = members.filter { it.packageName == packageName && !it.excluded }.map { it.groupId }.toSet()
            groups.filter { it.id in ids }
        }.distinctUntilChanged()

    override suspend fun getMemberRows(groupId: Long): List<AppGroupMember> =
        db.members.filter { it.groupId == groupId }

    override suspend fun renameGroup(groupId: Long, name: String) =
        db.updateGroupRows({ it.id == groupId }) { it.copy(name = name) }

    override suspend fun setCategories(groupId: Long, categories: String?) =
        db.updateGroupRows({ it.id == groupId }) { it.copy(categories = categories) }

    override suspend fun deleteMembersOfGroup(groupId: Long) = db.deleteMemberRows { it.groupId == groupId }

    override suspend fun deleteGroupById(groupId: Long) = db.deleteGroupRows { it.id == groupId }

    override suspend fun deleteRulesOfGroup(groupId: Long) = db.deleteRuleRows { it.groupId == groupId }

    override suspend fun getRulesWithExceptGroups(): List<BlockRule> = db.rules.filter { it.exceptGroupIds != null }

    override suspend fun setExceptGroupIds(ruleId: Long, exceptGroupIds: String?) =
        db.updateRuleRows({ it.id == ruleId }) { it.copy(exceptGroupIds = exceptGroupIds) }

    override suspend fun insertGroupRule(rule: BlockRule): Long = db.insertRuleRow(rule)

    override suspend fun updateGroupRule(rule: BlockRule) = db.updateRuleRows({ it.id == rule.id }) { rule }
}
