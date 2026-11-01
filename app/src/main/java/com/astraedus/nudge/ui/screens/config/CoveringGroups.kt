package com.astraedus.nudge.ui.screens.config

import androidx.compose.runtime.Immutable
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.ui.screens.groups.ownGroupRule
import com.astraedus.nudge.ui.components.rule.RuleSummary

/** One "Also covered by" row on App config: "Social · Delay 15s", opening that group. */
@Immutable
data class CoveringGroup(val groupId: Long, val label: String)

/**
 * The groups that cover [packageName] right now (spec 3.7): it is a member, and the group's own rule
 * is on. A group whose rule is off, or that has none, blocks nothing, so it is not listed: the
 * section answers "why is this app still blocked?", and only when that applies. Sorted by name.
 */
fun coveringGroups(
    packageName: String,
    groups: List<AppGroup>,
    members: List<AppGroupMember>,
    rules: List<BlockRule>
): List<CoveringGroup> {
    val memberOf = members.filter { it.packageName == packageName }.mapTo(HashSet()) { it.groupId }
    return groups
        .filter { it.id in memberOf }
        .mapNotNull { group ->
            val rule = ownGroupRule(group.id, rules)?.takeIf { it.enabled } ?: return@mapNotNull null
            group to rule
        }
        .sortedBy { (group, _) -> group.name.lowercase() }
        .map { (group, rule) ->
            CoveringGroup(group.id, "${group.name} · ${RuleSummary.groupSummaryText(rule)}")
        }
}
