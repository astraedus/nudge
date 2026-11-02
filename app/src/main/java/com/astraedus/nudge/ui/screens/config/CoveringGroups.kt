package com.astraedus.nudge.ui.screens.config

import androidx.compose.runtime.Immutable
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.groupResolverOf
import com.astraedus.nudge.data.repository.target
import com.astraedus.nudge.data.repository.toRuleData
import com.astraedus.nudge.domain.engine.CoverageFacts
import com.astraedus.nudge.domain.engine.RuleCoverage
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.ui.screens.groups.ownGroupRule
import com.astraedus.nudge.ui.components.rule.RuleSummary

/** One "Also covered by" row on App config: "Social · Delay 15s", opening that group. */
@Immutable
data class CoveringGroup(val groupId: Long, val label: String)

/**
 * The groups that cover [packageName] right now (spec 3.7): it is a member, and the group's own rule
 * is on. A group whose rule is off, or that has none, blocks nothing, so it is not listed: the
 * section answers "why is this app still blocked?", and only when that applies. Sorted by name.
 * [facts] are this package's device facts: its category (a category group's members) and whether
 * it is essential (never a member).
 */
fun coveringGroups(
    packageName: String,
    facts: CoverageFacts,
    groups: List<AppGroup>,
    members: List<AppGroupMember>,
    rules: List<BlockRule>
): List<CoveringGroup> = blockingGroupsOf(packageName, facts, groups, groupResolverOf(groups, members), rules)
    .map { (group, rule) ->
        CoveringGroup(group.id, "${group.name} · ${RuleSummary.groupSummaryText(rule)}")
    }

/** One "Also covered by" row for an All apps rule: its title and one summary line, opening the rule. */
@Immutable
data class CoveringAllApps(val ruleId: Long, val label: String)

/**
 * The All apps rules that cover [packageName] right now (spec 3.7): rules that are on and cover it
 * by [RuleCoverage], the ONE coverage answer (so an essential app, an app the rule allows, an app in
 * an allowed group and an app with no launcher entry are all left out, exactly as the engine leaves
 * them out). [facts] are the two device facts about THIS package. A schedule is not applied: a rule
 * that blocks at weekends still covers the app. Oldest rule first.
 */
fun coveringAllApps(
    packageName: String,
    groups: List<AppGroup>,
    members: List<AppGroupMember>,
    rules: List<BlockRule>,
    appLabels: Map<String, String>,
    facts: CoverageFacts
): List<CoveringAllApps> {
    val groupNames = groups.associate { it.id to it.name }
    val allApps = rules.filter { it.target() is RuleTarget.AllApps }.sortedBy { it.id }
    val rowById = allApps.associateBy { it.id }
    return RuleCoverage.coveringRules(
        packageName = packageName,
        rules = allApps.map { it.toRuleData(groupNames = groupNames) },
        membership = groupResolverOf(groups, members),
        facts = facts
    ).mapNotNull { data ->
        val rule = rowById[data.id] ?: return@mapNotNull null
        CoveringAllApps(
            ruleId = rule.id,
            label = "${RuleSummary.allAppsTitle(rule, groupNames, appLabels)} · " +
                RuleSummary.allAppsSummaryText(rule)
        )
    }
}

/**
 * The one answer to "which groups block this app?", for every screen that says so: App config's
 * "Also covered by" ([coveringGroups]) and Manage Apps' "In Social" line. Each group the app is a
 * member of ([GroupMembershipResolver], so by hand or by category, never "Removed by you", never an
 * essential app) whose own rule is on, with that rule, sorted by name.
 */
fun blockingGroupsOf(
    packageName: String,
    facts: CoverageFacts,
    groups: List<AppGroup>,
    membership: GroupMembershipResolver,
    rules: List<BlockRule>
): List<Pair<AppGroup, BlockRule>> {
    if (facts.essential) return emptyList()
    val memberOf = membership.groupIdsOf(packageName, facts.category)
    return groups
        .filter { it.id in memberOf }
        .mapNotNull { group ->
            val rule = ownGroupRule(group.id, rules)?.takeIf { it.enabled } ?: return@mapNotNull null
            group to rule
        }
        .sortedBy { (group, _) -> group.name.lowercase() }
}
