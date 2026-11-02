package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.domain.packages.AppCategory

/**
 * The facts about a package that group and All apps coverage need beyond the rule tables.
 *
 * All come from the device: [essential] from the one essentials list
 * ([com.astraedus.nudge.domain.packages.EssentialPackages], spec 5.4), [launchable] and [category]
 * from [com.astraedus.nudge.domain.packages.PackageFacts]. [category] is null when it was not read,
 * which matches no category group (see [GroupMembershipResolver]); a caller reads it whenever
 * [RuleCoverage.categoryNeeded] says a rule's groups match by category. There is deliberately no
 * default for any of them: every caller says what it knows, because a defaulted "launchable, not
 * essential" is how an essential app ends up under an All apps rule, and a defaulted null category
 * is how a category group silently covers nothing.
 */
data class CoverageFacts(val essential: Boolean, val launchable: Boolean, val category: AppCategory?)

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

    /**
     * The groups [packageName] is a member of, by [membership] (spec 5.2) with [facts]' category.
     * Essentials are applied by [covers], per rule kind.
     */
    fun groupIdsOf(packageName: String, membership: GroupMembershipResolver, facts: CoverageFacts): Set<Long> =
        membership.groupIdsOf(packageName, facts.category)

    /**
     * Does evaluating [rules] need the package's category? True when a rule targets, or (All apps)
     * allows, a group that matches by category. Otherwise no group the rules reach can match by
     * category, and the hot path skips the read.
     */
    fun categoryNeeded(rules: List<BlockRuleData>, membership: GroupMembershipResolver): Boolean {
        if (!membership.usesCategories) return false
        return rules.any { rule ->
            when (val target = RuleTarget.of(rule)) {
                is RuleTarget.Group -> membership.usesCategories(listOf(target.groupId))
                is RuleTarget.AllApps -> membership.usesCategories(target.exceptGroupIds)
                is RuleTarget.App, null -> false
            }
        }
    }

    /**
     * The ENABLED rules among [rules] that cover [packageName], in [rules] order. What "Also
     * blocked by" and "Also covered by" list (schedules not applied: a rule that blocks on weekends
     * still blocks the app).
     */
    fun coveringRules(
        packageName: String,
        rules: List<BlockRuleData>,
        membership: GroupMembershipResolver,
        facts: CoverageFacts
    ): List<BlockRuleData> {
        val groupIds = groupIdsOf(packageName, membership, facts)
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
