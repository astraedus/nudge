package com.astraedus.nudge.ui.screens.home

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.RuleSnapshot
import com.astraedus.nudge.domain.engine.CoveredApps

/**
 * The Home tile "Active Apps": how many apps a rule that is on covers (spec 3.2), counting an app
 * with its own rule and a blocking group once.
 *
 * It used to count the packages named by enabled rule rows, which missed every app a group covers.
 * Now the rows go through [RuleSnapshot.of], the mapping block evaluation reads, and
 * [CoveredApps] asks the engine's own resolver. [rules] may hold disabled rows; only enabled ones
 * cover anything.
 */
fun activeAppsCount(
    rules: List<BlockRule>,
    groups: List<AppGroup>,
    members: List<AppGroupMember>
): Int {
    val snapshot = RuleSnapshot.of(rules.filter { it.enabled }, groups, members)
    return CoveredApps.packages(snapshot.ruleData, snapshot.memberships).size
}
