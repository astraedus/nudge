package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import com.astraedus.nudge.domain.model.RuleTarget

/**
 * The apps a rule that is ON covers: what the Home tile "Active Apps" counts (spec 3.2), and what an
 * All apps rule's "Blocks N apps" counts when asked about that one rule (spec 3.4).
 *
 * Asked of the engine itself, not re-derived: a package is covered when
 * [RuleEvaluator.resolveRulesForPackage], the call every block evaluation makes, finds at least one
 * enabled rule for it. So an app with its own rule AND a place in a blocking group counts once, an
 * app whose only rule is off does not count, and a group whose rule is off covers nothing. The
 * inputs are a [com.astraedus.nudge.data.repository.RuleSnapshot]'s `ruleData` and `memberships`,
 * the same mapping evaluation reads.
 *
 * The device facts come in as sets, because a screen has the whole list in hand: [launchable] is the
 * installed apps with a launcher entry (`InstalledAppsRepository`), [essentials] the one essentials
 * list (`EssentialApps`). An essential app is never counted under a group or All apps rule, exactly
 * as the engine never covers it.
 */
object CoveredApps {

    private val evaluator = RuleEvaluator()

    /** Every covered package, deduplicated. */
    fun packages(
        ruleData: List<BlockRuleData>,
        memberships: List<GroupMembership>,
        launchable: Set<String>,
        essentials: Set<String>
    ): Set<String> {
        // Only a package named by a rule, in a group, or (with an All apps rule on) launchable can
        // resolve to anything.
        val candidates = HashSet<String>()
        ruleData.mapNotNullTo(candidates) { it.packageName }
        memberships.mapTo(candidates) { it.packageName }
        if (ruleData.any { it.enabled && RuleTarget.of(it) is RuleTarget.AllApps }) {
            candidates += launchable
        }
        return candidates.filterTo(HashSet()) { pkg ->
            val facts = CoverageFacts(essential = pkg in essentials, launchable = pkg in launchable)
            evaluator.resolveRulesForPackage(pkg, ruleData, memberships, facts).any { it.enabled }
        }
    }
}
