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

    // --- All apps rules (spec 3.4) ---

    /** Every All apps rule, on or off, oldest first. */
    fun getAllAppsRules(): Flow<List<BlockRule>> = blockRuleDao.getAllAppsRules()

    /** One rule by id, or null if it is gone (deleted from another screen, say). */
    suspend fun getRule(id: Long): BlockRule? = blockRuleDao.getById(id)

    /**
     * Create (rule.id == 0L) or update one All apps rule; returns its id. The rule must be an All
     * apps rule and nothing else: `allApps` set, no package, no group. Anything else is a caller
     * bug and throws, rather than writing a row that targets two things.
     */
    suspend fun saveAllAppsRule(rule: BlockRule): Long {
        require(rule.allApps && rule.packageName == null && rule.groupId == null) {
            "saveAllAppsRule needs an All apps rule with no package and no group"
        }
        return if (rule.id == 0L) {
            blockRuleDao.insert(rule)
        } else {
            blockRuleDao.update(rule)
            rule.id
        }
    }

    // --- Group operations ---

    fun getAllGroups(): Flow<List<AppGroup>> = appGroupDao.getAll()

    suspend fun createGroup(group: AppGroup): Long = appGroupDao.insert(group)

    suspend fun addToGroup(member: AppGroupMember) = appGroupDao.addMember(member)

    suspend fun removeFromGroup(groupId: Long, packageName: String) =
        appGroupDao.removeMember(groupId, packageName)

    fun getGroupMembers(groupId: Long): Flow<List<AppGroupMember>> =
        appGroupDao.getMembersForGroup(groupId)

    /** Every group's members, one query. See [RuleSnapshotSource]. */
    fun getAllGroupMembers(): Flow<List<AppGroupMember>> = appGroupDao.getAllMembers()

    fun getGroupRules(groupId: Long): Flow<List<BlockRule>> = blockRuleDao.getByGroupId(groupId)

    /**
     * Create (groupId == null) or update one group, its full member set and its own rule,
     * atomically. Returns the group id. The rule is written with groupId = that id and
     * packageName = null; rule.id == 0L inserts, otherwise updates. Members are replaced as a set.
     */
    suspend fun saveGroup(
        groupId: Long?,
        name: String,
        members: Set<String>,
        rule: BlockRule
    ): Long = appGroupDao.saveGroupWithRule(groupId, name.trim(), members, rule)

    /** Delete the group, every member row and every rule that targets it, in one transaction. */
    suspend fun deleteGroupWithRules(groupId: Long) = appGroupDao.deleteGroupAndRules(groupId)

    suspend fun renameGroup(groupId: Long, name: String) =
        appGroupDao.renameGroup(groupId, name.trim())
}
