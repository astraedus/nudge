package com.astraedus.nudge.ui.overlay

import com.astraedus.nudge.ui.components.rule.RuleSummary

/**
 * The pure decisions behind the block screen's All apps additions (spec 3.9): which name the rule
 * line shows, whether there is an "Until" line, which allowed apps get an icon, and whether the
 * redirect bubble shows. Kept out of [BlockOverlayActivity] (not JVM-constructible) so they are
 * L1-testable.
 */
internal object OverlayBlockInfo {

    /** Most allowed-app icons the block screen offers. */
    const val MAX_ALLOWED_ICONS = 4

    /**
     * The rule name the rule line shows. An All apps block says what it leaves open
     * ("All apps except Spotify, Maps"); every other block shows the name the service sent.
     * [exceptNames] are group names first, then the installed except-app labels.
     */
    fun visibleRuleName(ruleName: String?, isAllApps: Boolean, exceptNames: List<String>): String? =
        if (isAllApps) RuleSummary.allAppsTitle(exceptNames) else ruleName

    /** "Until 8:42 AM" for a block that carries an end time, or null (no line) for one that does not. */
    fun untilLine(untilMs: Long?, formatClock: (Long) -> String): String? =
        untilMs?.let { RuleSummary.untilText(formatClock(it)) }

    /**
     * The packages to show under "Open an allowed app": [allowed] in list order, without the blocked
     * packages, without anything not launchable (which also covers not installed), without
     * duplicates, capped at [MAX_ALLOWED_ICONS].
     */
    fun allowedIconPackages(
        allowed: List<String>,
        blockedPackages: Set<String>,
        isLaunchable: (String) -> Boolean
    ): List<String> = allowed.asSequence()
        .filter { it.isNotBlank() && it !in blockedPackages }
        .distinct()
        .filter(isLaunchable)
        .take(MAX_ALLOWED_ICONS)
        .toList()

    /** The redirect bubble shows on every block except an All apps one (the allowed icons replace it). */
    fun showRedirectBubble(isAllApps: Boolean): Boolean = !isAllApps
}
