package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import com.astraedus.nudge.domain.model.RuleTarget

/**
 * Is a package covered, right now, by a whole-app GROUP or ALL APPS rule? (spec 5.5,
 * `docs/architecture/web-domain-blocking.md` "Browsers in a group")
 *
 * The one question that decides whether a BROWSER is evaluated as an app. A browser normally gets
 * website rules only, so "block every app in Social" left Chrome as the way out: open Chrome, go to
 * youtube.com. A browser this answers true for is evaluated as an app first, and only reaches
 * website evaluation if that allows it.
 *
 * Covered means at least one rule with ALL of:
 *  - enabled;
 *  - a group or All apps rule. DIRECT rules on a browser keep their web-only meaning (spec
 *    follow-up F5): a "Chrome: hard block" rule written before this existed only ever hosted website
 *    rules, and silently turning it into a whole-browser block is not this change;
 *  - whole-app (no in-app features). A feature rule is about a part of an app, never the browser;
 *  - it covers the package ([RuleCoverage]): its group contains it, or it is an All apps rule that
 *    does not allow it, and in both cases the package is not essential;
 *  - in its schedule now. A group that blocks 9 to 5 does not take Chrome's websites away at 8 pm.
 *
 * The mode is deliberately not checked. A NONE group rule (a daily limit, or grayscale alone) still
 * covers: the app evaluation it buys is what enforces the group's limit on the browser, and when it
 * allows, website evaluation runs exactly as before.
 *
 * Pure, and synchronous by construction: the service calls it on the accessibility hot path over
 * the rule snapshot it already holds in memory.
 */
object WholeAppCoverage {

    fun coversNow(
        packageName: String,
        rules: List<BlockRuleData>,
        memberships: List<GroupMembership>,
        facts: CoverageFacts,
        isInSchedule: (BlockRuleData) -> Boolean
    ): Boolean {
        val groupIds = RuleCoverage.groupIdsOf(packageName, memberships)
        return rules.any { rule ->
            rule.enabled &&
                RuleTarget.of(rule).let { it is RuleTarget.Group || it is RuleTarget.AllApps } &&
                rule.inAppFeatures.isNullOrEmpty() &&
                RuleCoverage.covers(rule, packageName, groupIds, facts) &&
                isInSchedule(rule)
        }
    }
}
