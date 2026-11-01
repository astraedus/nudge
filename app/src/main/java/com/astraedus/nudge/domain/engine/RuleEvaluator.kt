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
                ruleName = buildRuleName(rule),
                tabVanish = rule.tabVanish,
                followingSteer = rule.followingSteer,
                keepPassOnShortTrips = rule.keepPassOnShortTrips
            )
        }
    }

    /**
     * Build a human-readable label describing what a rule does. The block overlay shows it as
     * "Rule: <name>".
     * Examples: "Reels - Delay", "Hard Block (30 min/day)", "Shorts - Breathing (scheduled)",
     * "Daily limit (30 min/day)" for a NONE rule that only caps usage.
     *
     * A GROUP rule is named after its group instead of its mode: "Social", "Social (scheduled)",
     * "Social (30 min/day)". The user made "Social"; an overlay over Instagram that says "Delay"
     * reads as a rule on Instagram itself, which they never wrote. A group rule whose group name did
     * not reach it (no group row) is [GROUP_FALLBACK_NAME], never a mode name, for the same reason.
     */
    internal fun buildRuleName(rule: BlockRuleData): String {
        val parts = mutableListOf<String>()

        // Feature prefix (e.g. "Reels", "Reels, Shorts")
        val features = rule.inAppFeatures
        if (!features.isNullOrEmpty()) {
            parts += features.joinToString(", ") { feature ->
                feature.replaceFirstChar { it.uppercaseChar() }
            }
        }

        // Mode name. A NONE rule blocks nothing itself, so the only way its label ever reaches an
        // overlay is the daily-limit path — call it what the user will actually be seeing.
        val modeName = when (rule.mode) {
            BlockMode.NONE -> if (rule.dailyLimitMinutes != null) "Daily limit" else "No block"
            BlockMode.HARD_BLOCK -> "Hard Block"
            BlockMode.DELAY -> "Delay"
            BlockMode.HOLD -> "Hold"
            BlockMode.BREATHING -> "Breathing"
        }
        parts += if (rule.groupId != null) {
            rule.groupName?.takeIf { it.isNotBlank() } ?: GROUP_FALLBACK_NAME
        } else {
            modeName
        }

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

    companion object {
        /** The label of a group rule whose group name is unknown. */
        const val GROUP_FALLBACK_NAME = "Group"
    }
}
