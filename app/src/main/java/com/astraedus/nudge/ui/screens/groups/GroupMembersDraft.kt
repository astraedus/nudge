package com.astraedus.nudge.ui.screens.groups

import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.groups.OFFERED_CATEGORIES
import com.astraedus.nudge.domain.packages.AppCategory

/**
 * Who is in a group, as the group editor edits it (spec 3.3, 5.2): the checked categories, the apps
 * added by hand ([includes]) and "Removed by you" ([excludes]). Pure, so every edit the screen makes
 * is a JVM-tested function of this value and the device facts.
 *
 * The device facts come in per call: [installed] maps each installed app to its category, and
 * [essentials] are never members, and [browsers] join only by hand (a browser's category never
 * brings it in: Chrome says SOCIAL, and checking Social must not take the whole web). Membership is always [GroupMembershipResolver]'s answer, so the
 * editor shows exactly the apps the engine blocks.
 *
 * [includes] and [excludes] never overlap (the database holds one row per app).
 */
data class GroupMembersDraft(
    val categories: Set<AppCategory> = emptySet(),
    val includes: Set<String> = emptySet(),
    val excludes: Set<String> = emptySet()
) {
    fun definition(groupId: Long): GroupDefinition = GroupDefinition(groupId, categories, includes, excludes)

    /** The group's members on this device. */
    fun members(installed: Map<String, AppCategory>, essentials: Set<String>, browsers: Set<String>): Set<String> =
        GroupMembershipResolver.membersOf(definition(NO_ID), installed, essentials, browsers)

    /** Would [category] alone bring this app in? (UNDEFINED, unknown and a [browser] never do.) */
    fun matchesCategory(category: AppCategory?, browser: Boolean): Boolean =
        GroupMembershipResolver.matchesCategory(definition(NO_ID), category, browser)

    /**
     * "Removed by you" as the screen lists it: removed apps that are installed and that a checked
     * category would otherwise bring in. A removed app whose category is no longer checked is kept
     * (checking it again keeps it out) but not listed: it would not be in the group anyway.
     */
    fun removedByYou(
        installed: Map<String, AppCategory>,
        essentials: Set<String>,
        browsers: Set<String>
    ): Set<String> =
        excludes.filterTo(LinkedHashSet()) { pkg ->
            pkg !in essentials && installed[pkg]?.let { matchesCategory(it, pkg in browsers) } == true
        }

    /** Check or uncheck [category]. Never UNDEFINED, never one the editor does not offer. */
    fun toggleCategory(category: AppCategory): GroupMembersDraft {
        if (category !in OFFERED_CATEGORIES) return this
        return copy(categories = if (category in categories) categories - category else categories + category)
    }

    /**
     * The ✕ on a member. An app its category brings in would come straight back, so it moves to
     * "Removed by you"; an app that is only in by hand just stops being added.
     */
    fun remove(packageName: String, category: AppCategory?, browser: Boolean): GroupMembersDraft =
        if (matchesCategory(category, browser)) {
            copy(includes = includes - packageName, excludes = excludes + packageName)
        } else {
            copy(includes = includes - packageName)
        }

    /** "Add back" on a "Removed by you" row: its category brings it in again. */
    fun addBack(packageName: String): GroupMembersDraft = copy(excludes = excludes - packageName)

    /**
     * The picker returned [picked], out of [pickable] (installed apps minus essentials). A picked app
     * that was removed comes back (its "Removed by you" row goes); a picked app no checked category
     * brings in is added by hand; a member the user unpicked is removed as by its ✕. A member the
     * picker could not show (an essential app, or one since uninstalled) is left exactly as it is:
     * dropping it unseen would skip the Strict Mode check a visible removal gets.
     */
    fun afterPick(
        picked: Set<String>,
        pickable: Set<String>,
        installed: Map<String, AppCategory>,
        essentials: Set<String>,
        browsers: Set<String>
    ): GroupMembersDraft {
        val current = members(installed, essentials, browsers)
        var next = this
        pickable.forEach { pkg ->
            val category = installed[pkg]
            val browser = pkg in browsers
            when {
                pkg in picked && pkg !in current -> {
                    next = next.addBack(pkg)
                    if (!next.matchesCategory(category, browser)) next = next.copy(includes = next.includes + pkg)
                }
                pkg !in picked && pkg in current -> next = next.remove(pkg, category, browser)
            }
        }
        return next
    }

    /**
     * The count on [category]'s chip: the installed apps it brings in, minus essentials, browsers and
     * "Removed by you". The same whether it is checked or not, so the number says what checking it does.
     */
    fun categoryCount(
        category: AppCategory,
        installed: Map<String, AppCategory>,
        essentials: Set<String>,
        browsers: Set<String>
    ): Int =
        installed.count { (pkg, appCategory) ->
            appCategory == category && pkg !in essentials && pkg !in browsers && pkg !in excludes
        }

    /**
     * The chips, one per [OFFERED_CATEGORIES] entry in that order, ALWAYS all of them: a category
     * with no app on this phone yet ("Games 0") is still offered, because checking it is how someone
     * blocks the next game before they install it. Hiding the empty ones (the first build) made that
     * impossible, and made a chip vanish once every app in it was "Removed by you".
     */
    fun chips(installed: Map<String, AppCategory>, essentials: Set<String>, browsers: Set<String>): List<CategoryChip> =
        OFFERED_CATEGORIES.map { category ->
            CategoryChip(
                category = category,
                count = categoryCount(category, installed, essentials, browsers),
                checked = category in categories
            )
        }

    companion object {
        /** The id a definition is resolved under when only its apps matter. */
        private const val NO_ID = 0L

        fun of(definition: GroupDefinition) =
            GroupMembersDraft(definition.categories, definition.includes, definition.excludes)
    }
}
