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
 * [essentials] are never members. Membership is always [GroupMembershipResolver]'s answer, so the
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
    fun members(installed: Map<String, AppCategory>, essentials: Set<String>): Set<String> =
        GroupMembershipResolver.membersOf(definition(NO_ID), installed, essentials)

    /** Would [category] alone bring this app in? (UNDEFINED and unknown never do.) */
    fun matchesCategory(category: AppCategory?): Boolean =
        GroupMembershipResolver.matchesCategory(definition(NO_ID), category)

    /**
     * "Removed by you" as the screen lists it: removed apps that are installed and that a checked
     * category would otherwise bring in. A removed app whose category is no longer checked is kept
     * (checking it again keeps it out) but not listed: it would not be in the group anyway.
     */
    fun removedByYou(installed: Map<String, AppCategory>, essentials: Set<String>): Set<String> =
        excludes.filterTo(LinkedHashSet()) { pkg ->
            pkg !in essentials && installed[pkg]?.let(::matchesCategory) == true
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
    fun remove(packageName: String, category: AppCategory?): GroupMembersDraft =
        if (matchesCategory(category)) {
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
        essentials: Set<String>
    ): GroupMembersDraft {
        val current = members(installed, essentials)
        var next = this
        pickable.forEach { pkg ->
            val category = installed[pkg]
            when {
                pkg in picked && pkg !in current -> {
                    next = next.addBack(pkg)
                    if (!next.matchesCategory(category)) next = next.copy(includes = next.includes + pkg)
                }
                pkg !in picked && pkg in current -> next = next.remove(pkg, category)
            }
        }
        return next
    }

    /**
     * The count on [category]'s chip: the installed apps it brings in, minus essentials and "Removed
     * by you". The same whether it is checked or not, so the number says what checking it does.
     */
    fun categoryCount(category: AppCategory, installed: Map<String, AppCategory>, essentials: Set<String>): Int =
        installed.count { (pkg, appCategory) ->
            appCategory == category && pkg !in essentials && pkg !in excludes
        }

    /**
     * The chips to show, in [OFFERED_CATEGORIES] order: every category with an app on this phone,
     * and every checked one (so a checked category with no app yet can still be unchecked).
     */
    fun offeredCategories(installed: Map<String, AppCategory>, essentials: Set<String>): List<AppCategory> =
        OFFERED_CATEGORIES.filter { it in categories || categoryCount(it, installed, essentials) > 0 }

    companion object {
        /** The id a definition is resolved under when only its apps matter. */
        private const val NO_ID = 0L

        fun of(definition: GroupDefinition) =
            GroupMembersDraft(definition.categories, definition.includes, definition.excludes)
    }
}
