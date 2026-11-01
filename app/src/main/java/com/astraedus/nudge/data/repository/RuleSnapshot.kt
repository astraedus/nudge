package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.domain.model.GroupMembership
import kotlinx.coroutines.flow.first

/**
 * Every rule table one block evaluation reads, as one immutable value: the enabled rules (as rows,
 * for website evaluation, and already mapped to [BlockRuleData], for app evaluation), the groups,
 * and every group's members.
 *
 * Built ONCE per table change by [RuleSnapshotSource], not once per accessibility event: the entity
 * to domain mapping (schedule-day parsing, feature splitting, mode parsing) used to run on every
 * foreground evaluation along with one Room query per group. See spec section 5.7
 * (`docs/exec-plans/active/groups-categories-allowlist.md`) and `docs/architecture/rule-snapshot.md`.
 *
 * There is deliberately no `EMPTY` constant. An empty snapshot reads as "no rules", which is an
 * ALLOW for every app; the only way to get one is to read empty tables.
 */
class RuleSnapshot private constructor(
    /** Enabled rule rows, in the order the DAO returned them. */
    val enabledRules: List<BlockRule>,
    val groups: List<AppGroup>,
    /** [enabledRules] mapped once, same order. */
    val ruleData: List<BlockRuleData>,
    /** Members of groups that exist. */
    val memberships: List<GroupMembership>
) {
    companion object {
        fun of(
            enabledRules: List<BlockRule>,
            groups: List<AppGroup>,
            members: List<AppGroupMember>
        ): RuleSnapshot {
            // The foreign key cascades member rows away with their group, so this filter is a
            // no-op on a healthy database. It keeps the old per-group read's exact semantics (only
            // members of groups that exist) whatever the member table holds.
            val groupIds = groups.mapTo(HashSet()) { it.id }
            val groupNames = groups.associate { it.id to it.name }
            return RuleSnapshot(
                enabledRules = enabledRules,
                groups = groups,
                ruleData = enabledRules.map { rule ->
                    rule.toRuleData(
                        groupName = rule.groupId?.let { groupNames[it] },
                        groupNames = groupNames
                    )
                },
                memberships = members
                    .filter { it.groupId in groupIds }
                    .map { GroupMembership(groupId = it.groupId, packageName = it.packageName) }
            )
        }
    }
}

/**
 * The one entity-to-domain mapping for a rule. Moved here unchanged from
 * `EvaluateBlockUseCase.resolveActiveRules` so the snapshot and every future reader share it.
 *
 * [groupName] is not on the row; the caller that holds the groups table ([RuleSnapshot.of]) passes
 * it so a group rule's overlay label can name its group. [groupNames] (every group, by id) names an
 * All apps rule's allowed groups the same way; a stale id has no entry and so no name.
 */
fun BlockRule.toRuleData(
    groupName: String? = null,
    groupNames: Map<Long, String> = emptyMap()
): BlockRuleData {
    val exceptGroups = ExceptCsv.groupIds(exceptGroupIds)
    return BlockRuleData(
        id = id,
        packageName = packageName,
        groupId = groupId,
        mode = try { BlockMode.valueOf(mode) } catch (_: Exception) { BlockMode.HARD_BLOCK },
        delaySeconds = delaySeconds,
        dailyLimitMinutes = dailyLimitMinutes,
        enabled = enabled,
        scheduleDays = scheduleDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() },
        scheduleStartMinute = scheduleStartMinute,
        scheduleEndMinute = scheduleEndMinute,
        inAppFeatures = inAppFeatures?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
        grayscale = grayscale,
        webDomains = webDomains,
        tabVanish = tabVanish,
        followingSteer = followingSteer,
        keepPassOnShortTrips = keepPassOnShortTrips,
        groupName = groupName,
        allApps = allApps,
        exceptPackages = ExceptCsv.packages(exceptPackages),
        exceptGroupIds = exceptGroups,
        exceptGroupNames = exceptGroups.mapNotNull { groupNames[it] }
    )
}

/** This row's target. See [RuleTarget]: never re-derive it from which columns are null. */
fun BlockRule.target(): RuleTarget? = RuleTarget.of(
    packageName = packageName,
    groupId = groupId,
    allApps = allApps,
    exceptPackages = ExceptCsv.packages(exceptPackages),
    exceptGroupIds = ExceptCsv.groupIds(exceptGroupIds)
)

/**
 * Where block evaluation gets its rule tables from.
 *
 * Production binds [RuleSnapshotSource] (in memory once warm). [RepositoryRuleSnapshots] reads the
 * repository on every call; it is the cold-start path inside [RuleSnapshotSource], and what a
 * hand-built `EvaluateBlockUseCase` uses. Both build the answer with [RuleSnapshot.of], so the two
 * cannot disagree about what a row means; they can only differ in how fresh and how cheap it is.
 */
interface RuleSnapshots {
    /** Rules, groups and memberships: what app evaluation needs. */
    suspend fun current(): RuleSnapshot

    /** The enabled rule rows alone: all that website evaluation needs. */
    suspend fun enabledRules(): List<BlockRule>
}

/**
 * Reads the tables NOW, on every call: one query for the rules, one for the groups, one per group
 * for its members. This is exactly what `EvaluateBlockUseCase` did per evaluation before the
 * snapshot existed, kept call-for-call so the existing use-case tests (which stub those three
 * repository methods) exercise the same mapping unchanged.
 */
class RepositoryRuleSnapshots(private val repository: BlockRuleRepository) : RuleSnapshots {

    override suspend fun current(): RuleSnapshot {
        val rules = repository.getEnabledRules().first()
        val groups = repository.getAllGroups().first()
        val members = groups.flatMap { repository.getGroupMembers(it.id).first() }
        return RuleSnapshot.of(rules, groups, members)
    }

    override suspend fun enabledRules(): List<BlockRule> = repository.getEnabledRules().first()
}
