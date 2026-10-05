package com.astraedus.nudge.domain.redirect

/**
 * Every decision about the "redirect app": the ONE app the user picked to be offered on every
 * block screen instead of the app they were trying to open (a to-do list, Wikipedia, a reader).
 * Pure Kotlin, so the whole of it is a JVM test. See `docs/architecture/block-overlay-lifecycle.md`,
 * "The redirect app".
 *
 * The one rule everything here serves: **the redirect app must never be an app Nudge would block.**
 * Tapping it is a walk-away into that app; if that app is itself blocked, the user lands on a second
 * block screen offering the same bubble, which is a loop wearing a feature's clothes. So the same
 * excluded set filters the picker (you cannot choose one) AND re-checks the saved choice every
 * time a block screen renders (a rule or a Nuke entry added AFTER it was chosen turns the bubble
 * back into the empty state rather than into a door to another block).
 */
object RedirectAppPolicy {

    /**
     * The slice of a block rule this policy reads. A rule targets either one package or one group.
     */
    data class RuleTarget(val packageName: String?, val groupId: Long?, val enabled: Boolean)

    /** One app the picker can offer, before icons are attached. */
    data class Candidate(val packageName: String, val label: String)

    /**
     * Every package an ENABLED rule applies to: its own package, or every member of its group.
     *
     * Deliberately "has an enabled rule", not "is blocked right now". A rule scoped to 9-5, or one
     * that only caps a daily budget, opens freely at 8pm and would be a perfectly good destination
     * at 8pm, and a wrong one at 10am. A choice that is only valid some of the time is a bubble that
     * works on Tuesday and loops on Wednesday. A disabled rule blocks nothing ever, so it does not
     * count; re-enabling it is caught by the render-time re-check.
     *
     * @param groupMembers groupId -> member packages.
     */
    fun ruleTargets(rules: List<RuleTarget>, groupMembers: Map<Long, Set<String>>): Set<String> =
        rules.asSequence()
            .filter { it.enabled }
            .flatMap { rule ->
                val direct = listOfNotNull(rule.packageName?.takeIf { it.isNotBlank() })
                val grouped = rule.groupId?.let { groupMembers[it] }.orEmpty()
                (direct + grouped).asSequence()
            }
            .toSet()

    /**
     * The packages that can never be the redirect app.
     *
     * @param ownPackage Nudge itself. Opening Nudge from its own block screen is not a walk-away,
     *   and Nudge's own window is not a departure from the blocked app (it is `OwnUi`), so the
     *   arrival machinery would read it as still being there.
     * @param nukeList the Nuke list, whether or not Nuke is on right now: the same "valid only some
     *   of the time" reasoning as [ruleTargets]. Turning Nuke on must not arm a loop.
     * @param blockPackages the package(s) of the block currently on screen: the blocked app and, for
     *   a web block, the browser the user is sitting in. A browser with no rule of its own is a fine
     *   redirect from an app block, and a bypass from a web block in that same browser.
     */
    fun excluded(
        ownPackage: String,
        ruleTargets: Set<String>,
        nukeList: Set<String>,
        blockPackages: Set<String> = emptySet()
    ): Set<String> = buildSet {
        add(ownPackage)
        addAll(ruleTargets)
        addAll(nukeList)
        addAll(blockPackages)
    }.filterTo(mutableSetOf()) { it.isNotBlank() }

    fun isEligible(packageName: String, excluded: Set<String>): Boolean =
        packageName.isNotBlank() && packageName !in excluded

    /**
     * What the bubble shows: the saved package, or null for the EMPTY state.
     *
     * Null when nothing is saved, when the app is no longer launchable (uninstalled, disabled, its
     * launcher activity removed), or when it has since become an excluded app. Never throws, never
     * returns an excluded package: a stale choice degrades to "pick a better app", the one state
     * that cannot loop.
     */
    fun resolve(saved: String?, isLaunchable: Boolean, excluded: Set<String>): String? {
        val pkg = saved?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!isLaunchable) return null
        return pkg.takeIf { isEligible(it, excluded) }
    }

    /**
     * The picker's rows: eligible apps, matching [query] on label or package, alphabetical by
     * label, one row per package.
     */
    fun <T> pickerRows(
        installed: List<T>,
        excluded: Set<String>,
        query: String,
        candidate: (T) -> Candidate
    ): List<T> {
        val q = query.trim()
        return installed
            .asSequence()
            .map { it to candidate(it) }
            .filter { (_, c) -> isEligible(c.packageName, excluded) }
            .filter { (_, c) ->
                q.isEmpty() || c.label.contains(q, ignoreCase = true) ||
                    c.packageName.contains(q, ignoreCase = true)
            }
            .distinctBy { (_, c) -> c.packageName }
            .sortedBy { (_, c) -> c.label.lowercase() }
            .map { (item, _) -> item }
            .toList()
    }
}
