package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import com.astraedus.nudge.domain.model.RuleTarget

/**
 * The two facts about a package that group and All apps coverage need beyond the rule tables.
 *
 * Both come from the device: [essential] from the one essentials list
 * ([com.astraedus.nudge.domain.packages.EssentialPackages], spec 5.4), [launchable] from
 * [com.astraedus.nudge.domain.packages.PackageFacts]. There is deliberately no default instance:
 * every caller says what it knows, because a defaulted "launchable, not essential" is how an
 * essential app ends up under an All apps rule.
 */
data class CoverageFacts(val essential: Boolean, val launchable: Boolean)

/**
 * Which rules cover a package (spec 4.1). The ONE coverage answer: block evaluation
 * ([RuleEvaluator]), browser routing ([WholeAppCoverage]), the Home count ([CoveredApps]), the All
 * apps editor's "Blocks N apps" and "Also blocked by", and App config's "Also covered by" all ask
 * this, so no two of them can disagree about what a rule covers.
 *
 * - **App rule:** the app it names. An explicit per-app rule is honoured even on an essential app
 *   (the user chose it, spec 4.2).
 * - **Group rule:** the group's members, except essentials.
 * - **All apps rule:** every launchable app except essentials, the rule's allowed apps and the
 *   current members of its allowed groups. A stale allowed group id has no members, so it allows
 *   nothing: it fails strict.
 *
 * "Covered" says nothing about mode, schedule or whether the rule is on; the engine applies those.
 * Being allowed by an All apps rule only means THAT rule does not cover the app. It never lifts
 * another rule: rules only add (spec 1, "Precedence in one sentence").
 */
object RuleCoverage {

    /** Does [rule] cover [packageName], a member of the groups [groupIdsOfPackage]? */
    fun covers(
        rule: BlockRuleData,
        packageName: String,
        groupIdsOfPackage: Set<Long>,
        facts: CoverageFacts
    ): Boolean = when (val target = RuleTarget.of(rule)) {
        null -> false
        is RuleTarget.App -> target.packageName == packageName
        is RuleTarget.Group -> !facts.essential && target.groupId in groupIdsOfPackage
        is RuleTarget.AllApps ->
            facts.launchable &&
                !facts.essential &&
                packageName !in target.exceptPackages &&
                target.exceptGroupIds.none { it in groupIdsOfPackage }
    }

    /** The groups [packageName] is a member of. */
    fun groupIdsOf(packageName: String, memberships: List<GroupMembership>): Set<Long> =
        memberships.filter { it.packageName == packageName }.mapTo(HashSet()) { it.groupId }

    /**
     * The ENABLED rules among [rules] that cover [packageName], in [rules] order. What "Also
     * blocked by" and "Also covered by" list (schedules not applied: a rule that blocks on weekends
     * still blocks the app).
     */
    fun coveringRules(
        packageName: String,
        rules: List<BlockRuleData>,
        memberships: List<GroupMembership>,
        facts: CoverageFacts
    ): List<BlockRuleData> {
        val groupIds = groupIdsOf(packageName, memberships)
        return rules.filter { it.enabled && covers(it, packageName, groupIds, facts) }
    }

    /**
     * Which device facts evaluating [rules] for a package in [groupIdsOfPackage] can consult, so a
     * caller on the hot path reads only what can matter: no essentials read for a package with only
     * its own rules, no launchable read without an All apps rule.
     */
    fun factsNeeded(rules: List<BlockRuleData>, groupIdsOfPackage: Set<Long>): FactsNeeded {
        var essential = false
        var launchable = false
        for (rule in rules) {
            when (val target = RuleTarget.of(rule)) {
                is RuleTarget.Group -> if (target.groupId in groupIdsOfPackage) essential = true
                is RuleTarget.AllApps -> {
                    essential = true
                    launchable = true
                }
                is RuleTarget.App, null -> Unit
            }
        }
        return FactsNeeded(essential = essential, launchable = launchable)
    }

    data class FactsNeeded(val essential: Boolean, val launchable: Boolean)
}
