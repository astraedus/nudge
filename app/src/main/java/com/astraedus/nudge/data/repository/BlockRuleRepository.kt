package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockRuleRepository @Inject constructor(
    private val blockRuleDao: BlockRuleDao,
    private val appGroupDao: AppGroupDao
) {

    fun getAllRules(): Flow<List<BlockRule>> = blockRuleDao.getAll()

    fun getEnabledRules(): Flow<List<BlockRule>> = blockRuleDao.getEnabled()

    /**
     * Returns rules for a package: direct rules AND rules from groups containing this package.
     */
    fun getRulesForPackage(packageName: String): Flow<List<BlockRule>> {
        val directRules = blockRuleDao.getByPackage(packageName)
        val groupRules = appGroupDao.getGroupsForPackage(packageName)

        return combine(directRules, groupRules, blockRuleDao.getAll()) { direct, groups, allRules ->
            val groupIds = groups.map { it.id }.toSet()
            val fromGroups = allRules.filter { it.groupId != null && it.groupId in groupIds }
            (direct + fromGroups).distinctBy { it.id }
        }
    }

    suspend fun addRule(rule: BlockRule): Long = blockRuleDao.insert(rule)

    suspend fun updateRule(rule: BlockRule) = blockRuleDao.update(rule)

    suspend fun deleteRule(id: Long) = blockRuleDao.deleteById(id)

    suspend fun deleteDirectRulesForPackage(packageName: String) =
        blockRuleDao.deleteByPackageName(packageName)

    suspend fun setEnabledForPackage(packageName: String, enabled: Boolean) =
        blockRuleDao.setEnabledForPackage(packageName, enabled)

    // --- Group operations ---

    fun getAllGroups(): Flow<List<AppGroup>> = appGroupDao.getAll()

    suspend fun createGroup(group: AppGroup): Long = appGroupDao.insert(group)

    suspend fun addToGroup(member: AppGroupMember) = appGroupDao.addMember(member)

    suspend fun removeFromGroup(groupId: Long, packageName: String) =
        appGroupDao.removeMember(groupId, packageName)

    fun getGroupMembers(groupId: Long): Flow<List<AppGroupMember>> =
        appGroupDao.getMembersForGroup(groupId)
}
