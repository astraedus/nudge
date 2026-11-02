package com.astraedus.nudge.data.repository

import com.astraedus.nudge.domain.groups.AppCategoryCsv
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.model.RuleTarget
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
     * Returns rules for a package: direct rules AND rules from groups the app was added to by hand.
     * A group's category members have no row, so this misses them: anything that needs "which
     * groups is this app in" resolves membership with [groupResolverOf] instead.
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

    /**
     * Insert one rule. Every write path checks the target invariant (exactly one of package, group,
     * All apps, spec 5.1) and throws on a row that targets two things or nothing: such a row would
     * be read as its narrowest target by the engine, which hides the caller bug instead of fixing
     * it. An All apps rule is written as [normalizedAllAppsRule], exactly as [saveAllAppsRule]
     * writes it.
     */
    suspend fun addRule(rule: BlockRule): Long = blockRuleDao.insert(checkedRow(rule))

    /**
     * Update one rule, with the same check as [addRule], except for one row shape that already exists
     * on phones: an app AND a group, which only an old hand-edited import could write. Throwing on
     * that would crash the switch that flips it, so it is written back as the app rule the engine
     * has always read it as ([legacyTwoTargetRepaired]); the behaviour does not change.
     */
    suspend fun updateRule(rule: BlockRule) =
        blockRuleDao.update(checkedRow(legacyTwoTargetRepaired(rule)))

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
     *
     * Written through [normalizedAllAppsRule], so the options an All apps rule never offers (spec
     * 3.4) can not ride in on a copied row or a hand-edited backup.
     */
    suspend fun saveAllAppsRule(rule: BlockRule): Long {
        require(rule.allApps && rule.packageName == null && rule.groupId == null) {
            "saveAllAppsRule needs an All apps rule with no package and no group"
        }
        val row = normalizedAllAppsRule(rule)
        return if (row.id == 0L) {
            blockRuleDao.insert(row)
        } else {
            blockRuleDao.update(row)
            row.id
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
     * Create (groupId == null) or update one group and its own rule, atomically: its name, its
     * checked [categories], the apps added by hand ([includes]) and "Removed by you" ([excludes]),
     * each replaced as a whole (spec 5.2). Returns the group id. The rule is written with groupId =
     * that id and packageName = null; rule.id == 0L inserts, otherwise updates.
     */
    suspend fun saveGroup(
        groupId: Long?,
        name: String,
        categories: Set<AppCategory>,
        includes: Set<String>,
        excludes: Set<String>,
        rule: BlockRule
    ): Long {
        // The DAO sets groupId and clears packageName, so the one way this row could target two
        // things is the All apps flag.
        require(!rule.allApps) { "saveGroup needs a group rule, not an All apps rule" }
        return appGroupDao.saveGroupWithRule(
            groupId = groupId,
            name = name.trim(),
            categories = AppCategoryCsv.format(categories),
            includes = includes,
            excludes = excludes,
            rule = rule
        )
    }

    /** Delete the group, every member row and every rule that targets it, in one transaction. */
    suspend fun deleteGroupWithRules(groupId: Long) = appGroupDao.deleteGroupAndRules(groupId)

    suspend fun renameGroup(groupId: Long, name: String) =
        appGroupDao.renameGroup(groupId, name.trim())

    private fun checkedRow(rule: BlockRule): BlockRule {
        require(RuleTarget.isWellFormed(rule.packageName, rule.groupId, rule.allApps)) {
            "A rule targets exactly one of an app, a group or all apps"
        }
        return if (rule.allApps) normalizedAllAppsRule(rule) else rule
    }
}

/**
 * A stored row with a package AND a group (and not All apps), rewritten as the app rule
 * [RuleTarget.of] reads it as (the package wins). Every other row is returned unchanged.
 */
internal fun legacyTwoTargetRepaired(rule: BlockRule): BlockRule =
    if (rule.packageName != null && rule.groupId != null && !rule.allApps) rule.copy(groupId = null) else rule

/**
 * The row an All apps rule is stored as. The editor offers mode, wait, days and times and the
 * except lists, nothing else (spec 3.4), so every other option is forced off here, where every
 * write path (editor, import, a copied row) passes:
 *  - keep the pass on short trips: off. One pass would otherwise reopen every covered app.
 *  - auto-kick, reminder, counter, time remaining, grayscale, web domains, in-app features,
 *    Following steer: cleared. They are per-app tools with no meaning across all apps.
 *  - daily limit: KEPT. It is not offered here, but one that arrives (an imported rule) only makes
 *    the rule stricter (each covered app gets that many minutes a day), and the editor keeps it on
 *    save, so the two layers agree.
 * The except lists are rewritten in their one canonical form ([ExceptCsv]).
 */
internal fun normalizedAllAppsRule(rule: BlockRule): BlockRule = rule.copy(
    keepPassOnShortTrips = false,
    autoKickAfter = null,
    autoKickAfterMinutes = null,
    remindAfterMinutes = null,
    showCounter = false,
    showTimeRemaining = false,
    grayscale = false,
    webDomains = null,
    webBlockMode = null,
    inAppFeatures = null,
    followingSteer = false,
    exceptPackages = ExceptCsv.ofPackages(ExceptCsv.packages(rule.exceptPackages)),
    exceptGroupIds = ExceptCsv.ofGroupIds(ExceptCsv.groupIds(rule.exceptGroupIds))
)
