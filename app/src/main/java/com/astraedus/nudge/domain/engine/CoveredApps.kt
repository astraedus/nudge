package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.RuleTarget
import com.astraedus.nudge.domain.packages.AppCategory

/**
 * The apps a rule that is ON covers: what the Home tile "Active Apps" counts (spec 3.2), and what an
 * All apps rule's "Blocks N apps" counts when asked about that one rule (spec 3.4).
 *
 * Asked of the engine itself, not re-derived: a package is covered when
 * [RuleEvaluator.resolveRulesForPackage], the call every block evaluation makes, finds at least one
 * enabled rule for it. So an app with its own rule AND a place in a blocking group counts once, an
 * app whose only rule is off does not count, and a group whose rule is off covers nothing. The
 * inputs are a [com.astraedus.nudge.data.repository.RuleSnapshot]'s `ruleData` and `membership`,
 * the same values evaluation reads.
 *
 * The device facts come in whole, because a screen has the list in hand: [launchable] is the
 * installed apps with a launcher entry, each with its category (`InstalledAppsRepository`), which
 * is what a category group and an All apps rule can cover; [essentials] is the one essentials list
 * (`EssentialApps`). An essential app is never counted under a group or All apps rule, exactly as
 * the engine never covers it.
 */
object CoveredApps {

    private val evaluator = RuleEvaluator()

    /** Every covered package, deduplicated. */
    fun packages(
        ruleData: List<BlockRuleData>,
        membership: GroupMembershipResolver,
        launchable: Map<String, AppCategory>,
        essentials: Set<String>
    ): Set<String> {
        // Only a package named by a rule, added to a group by hand, or (with an All apps rule on, or
        // a category group) launchable can resolve to anything.
        val candidates = HashSet<String>()
        ruleData.mapNotNullTo(candidates) { it.packageName }
        membership.definitions.values.forEach { candidates += it.includes }
        if (membership.usesCategories ||
            ruleData.any { it.enabled && RuleTarget.of(it) is RuleTarget.AllApps }
        ) {
            candidates += launchable.keys
        }
        return candidates.filterTo(HashSet()) { pkg ->
            val facts = CoverageFacts(
                essential = pkg in essentials,
                launchable = pkg in launchable,
                category = launchable[pkg]
            )
            evaluator.resolveRulesForPackage(pkg, ruleData, membership, facts).any { it.enabled }
        }
    }
}
