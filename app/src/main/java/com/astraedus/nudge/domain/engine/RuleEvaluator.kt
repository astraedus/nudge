package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import javax.inject.Inject

class RuleEvaluator @Inject constructor() {

    /**
     * Resolve all rules that apply to a given package.
     *
     * A rule matches if:
     * - Its packageName equals the target package directly, OR
     * - Its groupId matches a group that contains the target package
     */
    fun resolveRulesForPackage(
        packageName: String,
        allRules: List<BlockRuleData>,
        groupMemberships: List<GroupMembership>
    ): List<ActiveRule> {
        val groupIdsForPackage = groupMemberships
            .filter { it.packageName == packageName }
            .map { it.groupId }
            .toSet()

        return allRules.filter { rule ->
            rule.packageName == packageName ||
                (rule.groupId != null && rule.groupId in groupIdsForPackage)
        }.map { rule ->
            ActiveRule(
                mode = rule.mode,
                delaySeconds = rule.delaySeconds,
                dailyLimitMinutes = rule.dailyLimitMinutes,
                enabled = rule.enabled,
                scheduleDays = rule.scheduleDays,
                scheduleStartMinute = rule.scheduleStartMinute,
                scheduleEndMinute = rule.scheduleEndMinute,
                inAppFeatures = rule.inAppFeatures,
                grayscale = rule.grayscale
            )
        }
    }
}
