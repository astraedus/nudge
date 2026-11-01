package com.astraedus.nudge.ui.components.rule

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.target
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.model.RuleTarget

/** The stock icon of an All apps rule, in the same 40dp tonal container a group's folder uses. */
val AllAppsIcon: ImageVector get() = Icons.Default.Apps

/**
 * One All apps rule as a list row: its title ("All apps except Spotify, Maps"), its mode and
 * schedule in one line, and whether it is on. Manage Apps' "All apps" section, App config's
 * "Also covered by" and the group editor's "Allowed during" all build their rows from here, so one
 * rule never reads two ways on two screens.
 */
@Immutable
data class AllAppsRuleItem(
    val ruleId: Long,
    val title: String,
    val summary: String,
    val enabled: Boolean,
    val rule: BlockRule
)

/**
 * The All apps rules among [rules] as rows, oldest first (by id). [groups] names the rule's allowed
 * groups; [appLabels] (package to launcher label) names its allowed apps, and a package that is not
 * in it (not installed) is left out of the title, as the editor hides it.
 */
fun allAppsRuleItems(
    rules: List<BlockRule>,
    groups: List<AppGroup>,
    appLabels: Map<String, String>
): List<AllAppsRuleItem> {
    val groupNames = groups.associate { it.id to it.name }
    return rules
        .filter { it.target() is RuleTarget.AllApps }
        .sortedBy { it.id }
        .map { rule ->
            AllAppsRuleItem(
                ruleId = rule.id,
                title = RuleSummary.allAppsTitle(rule, groupNames, appLabels),
                summary = RuleSummary.allAppsSummaryText(rule),
                enabled = rule.enabled,
                rule = rule
            )
        }
}

/**
 * The All apps rules, on or off, that list [groupId] as allowed: the group editor's "Allowed
 * during" (spec 3.3). A rule that is off still shows: the group is allowed there for when it is on.
 */
fun allAppsRulesAllowingGroup(
    groupId: Long,
    rules: List<BlockRule>,
    groups: List<AppGroup>,
    appLabels: Map<String, String>
): List<AllAppsRuleItem> = allAppsRuleItems(
    rules = rules.filter { groupId in ExceptCsv.groupIds(it.exceptGroupIds) },
    groups = groups,
    appLabels = appLabels
)
