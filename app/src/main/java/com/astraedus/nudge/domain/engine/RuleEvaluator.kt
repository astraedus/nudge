package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockMode
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
                grayscale = rule.grayscale,
                ruleName = buildRuleName(rule)
            )
        }
    }

    /**
     * Build a human-readable label describing what a rule does.
     * Examples: "Reels - Delay", "Hard Block (30 min/day)", "Shorts - Breathing (scheduled)"
     */
    private fun buildRuleName(rule: BlockRuleData): String {
        val parts = mutableListOf<String>()

        // Feature prefix (e.g. "Reels", "Reels, Shorts")
        val features = rule.inAppFeatures
        if (!features.isNullOrEmpty()) {
            parts += features.joinToString(", ") { feature ->
                feature.replaceFirstChar { it.uppercaseChar() }
            }
        }

        // Mode name
        val modeName = when (rule.mode) {
            BlockMode.HARD_BLOCK -> "Hard Block"
            BlockMode.DELAY -> "Delay"
            BlockMode.BREATHING -> "Breathing"
        }
        parts += modeName

        // Qualifiers
        val qualifiers = mutableListOf<String>()
        if (rule.scheduleDays != null) {
            qualifiers += "scheduled"
        }
        if (rule.dailyLimitMinutes != null) {
            qualifiers += "${rule.dailyLimitMinutes} min/day"
        }

        val label = parts.joinToString(" - ")
        return if (qualifiers.isNotEmpty()) {
            "$label (${qualifiers.joinToString(", ")})"
        } else {
            label
        }
    }
}
