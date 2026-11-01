package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership

/**
 * Is a package covered, right now, by a whole-app GROUP rule? (spec 5.5,
 * `docs/architecture/web-domain-blocking.md` "Browsers in a group")
 *
 * The one question that decides whether a BROWSER is evaluated as an app. A browser normally gets
 * website rules only, so "block every app in Social" left Chrome as the way out: open Chrome, go to
 * youtube.com. A browser this answers true for is evaluated as an app first, and only reaches
 * website evaluation if that allows it.
 *
 * Covered means at least one rule with ALL of:
 *  - enabled;
 *  - a group rule (`groupId != null`). DIRECT rules on a browser keep their web-only meaning (spec
 *    follow-up F5): a "Chrome: hard block" rule written before this existed only ever hosted website
 *    rules, and silently turning it into a whole-browser block is not this change;
 *  - whole-app (no in-app features). A feature rule is about a part of an app, never the browser;
 *  - its group contains the package;
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
        isInSchedule: (BlockRuleData) -> Boolean
    ): Boolean {
        val groupIds = memberships
            .filter { it.packageName == packageName }
            .mapTo(HashSet()) { it.groupId }
        if (groupIds.isEmpty()) return false
        return rules.any { rule ->
            rule.enabled &&
                rule.groupId != null &&
                rule.groupId in groupIds &&
                rule.inAppFeatures.isNullOrEmpty() &&
                isInSchedule(rule)
        }
    }
}
