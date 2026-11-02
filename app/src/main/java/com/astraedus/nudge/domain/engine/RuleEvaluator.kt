package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.AllAppsBlock
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.model.RuleTarget
import javax.inject.Inject

class RuleEvaluator @Inject constructor() {

    /**
     * Every rule that covers [packageName] ([RuleCoverage], spec 4.1), as [ActiveRule]s, schedules
     * not yet applied (the engine applies those).
     *
     * [facts] are the device facts group and All apps coverage need; the caller reads only those
     * [RuleCoverage.factsNeeded] says can matter. An All apps rule's [ActiveRule.allApps] carries its
     * allowed apps (its own, then the CURRENT members of its allowed groups) for the block screen.
     */
    fun resolveRulesForPackage(
        packageName: String,
        allRules: List<BlockRuleData>,
        membership: GroupMembershipResolver,
        facts: CoverageFacts
    ): List<ActiveRule> {
        val groupIdsForPackage = RuleCoverage.groupIdsOf(packageName, membership, facts)

        return allRules.filter { rule ->
            RuleCoverage.covers(rule, packageName, groupIdsForPackage, facts)
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
                keepPassOnShortTrips = rule.keepPassOnShortTrips,
                allApps = allAppsBlockOf(rule, membership),
                firstUnlockMinutes = rule.firstUnlockMinutes,
                armedAt = rule.armedAt
            )
        }
    }

    /**
     * What an All apps rule's block screen shows; null for any other rule.
     *
     * An allowed group contributes its hand-added apps (minus "Removed by you") here. Its CATEGORY
     * members need the installed apps, which the engine does not hold (nothing per app is stored,
     * spec 5.2), so the block screen resolves them on the device from [AllAppsBlock.exceptGroupIds]
     * through the same [AllAppsBlock.allowedPackagesOf]. Coverage itself never depended on this list:
     * a category member of an allowed group is not covered, through [RuleCoverage].
     */
    private fun allAppsBlockOf(
        rule: BlockRuleData,
        membership: GroupMembershipResolver
    ): AllAppsBlock? {
        val target = RuleTarget.of(rule) as? RuleTarget.AllApps ?: return null
        val exceptPackages = target.exceptPackages.toList()
        val liveGroupIds = target.exceptGroupIds.filter { it in membership.definitions }
        return AllAppsBlock(
            exceptPackages = exceptPackages,
            exceptGroupNames = rule.exceptGroupNames,
            exceptGroupIds = liveGroupIds,
            // No device facts: each group's hand-added apps minus "Removed by you".
            allowedPackages = AllAppsBlock.allowedPackagesOf(exceptPackages, liveGroupIds) { id ->
                membership.membersOf(id, installed = emptyMap(), essentials = emptySet(), browsers = emptySet())
            }
        )
    }

    /**
     * Build a human-readable label describing what a rule does. The block overlay shows it as
     * "Rule: <name>".
     * Examples: "Reels - Delay", "Hard Block (30 min/day)", "Shorts - Breathing (scheduled)",
     * "Daily limit (30 min/day)" for a NONE rule that only caps usage.
     *
     * An All apps rule is [ALL_APPS_NAME] ("All apps (scheduled)"); the block screen replaces it
     * with "All apps except Spotify, Maps" from [AllAppsBlock].
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
        parts += when (RuleTarget.of(rule)) {
            is RuleTarget.Group -> rule.groupName?.takeIf { it.isNotBlank() } ?: GROUP_FALLBACK_NAME
            // The block screen spells out the allowed apps by label (it has the PackageManager);
            // this is the name logs and every other reader see.
            is RuleTarget.AllApps -> ALL_APPS_NAME
            is RuleTarget.App, null -> modeName
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

        /** The label of an All apps rule in the engine (the block screen adds the allowed names). */
        const val ALL_APPS_NAME = "All apps"
    }
}
