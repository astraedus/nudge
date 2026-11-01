package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership

/**
 * The apps a rule that is ON covers: what the Home tile "Active Apps" counts (spec 3.2).
 *
 * Asked of the engine itself, not re-derived: a package is covered when
 * [RuleEvaluator.resolveRulesForPackage], the call every block evaluation makes, finds at least one
 * enabled rule for it. So an app with its own rule AND a place in a blocking group counts once, an
 * app whose only rule is off does not count, and a group whose rule is off covers nothing. The
 * inputs are a [com.astraedus.nudge.data.repository.RuleSnapshot]'s `ruleData` and `memberships`,
 * the same mapping evaluation reads.
 *
 * Essentials are not taken out: the engine does not take them out of groups yet (the picker hides
 * them; PR 4/6 adds the resolver), and this count must say what the engine does.
 */
object CoveredApps {

    private val evaluator = RuleEvaluator()

    /** Every covered package, deduplicated. */
    fun packages(ruleData: List<BlockRuleData>, memberships: List<GroupMembership>): Set<String> {
        // Only a package named by a rule, or in a group, can resolve to anything.
        val candidates = ruleData.mapNotNullTo(HashSet()) { it.packageName } +
            memberships.map { it.packageName }
        return candidates.filterTo(HashSet()) { pkg ->
            evaluator.resolveRulesForPackage(pkg, ruleData, memberships).any { it.enabled }
        }
    }
}
