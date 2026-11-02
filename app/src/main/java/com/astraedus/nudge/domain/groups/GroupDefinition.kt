package com.astraedus.nudge.domain.groups

import com.astraedus.nudge.domain.packages.AppCategory

/**
 * A group as the user defined it (spec 5.2): the categories whose apps belong, the apps added by
 * hand ([includes]) and the apps taken out by hand ([excludes], "Removed by you").
 *
 * This is the definition, not the member list. Who is a member right now depends on what is
 * installed and on each app's category, so it is resolved when asked
 * ([GroupMembershipResolver]); nothing is stored per installed app, and an app installed later joins
 * by itself.
 */
data class GroupDefinition(
    val id: Long,
    val categories: Set<AppCategory> = emptySet(),
    val includes: Set<String> = emptySet(),
    val excludes: Set<String> = emptySet()
)

/**
 * The categories a group may be built from, in the order the group editor offers them (spec 3.3):
 * the ones people block first, first. Never [AppCategory.UNDEFINED] (it would match every app with
 * no category), and there is no ACCESSIBILITY value to offer (blocking assistive apps by category
 * is never wanted, spec 6.4).
 */
val OFFERED_CATEGORIES: List<AppCategory> = listOf(
    AppCategory.SOCIAL,
    AppCategory.VIDEO,
    AppCategory.GAME,
    AppCategory.NEWS,
    AppCategory.AUDIO,
    AppCategory.IMAGE,
    AppCategory.MAPS,
    AppCategory.PRODUCTIVITY
)

/**
 * `app_groups.categories` <-> [AppCategory]. The one place the CSV is read or written.
 *
 * Reading skips unknown names (a backup from a newer Nudge, a hand-edited row) and
 * [AppCategory.UNDEFINED]: neither may ever match an app. Writing emits names in
 * [OFFERED_CATEGORIES] order, so the same set always produces the same text, and null for none (a
 * hand-picked group keeps the column NULL, exactly like every group before categories existed).
 */
object AppCategoryCsv {

    fun parse(csv: String?): Set<AppCategory> {
        if (csv.isNullOrBlank()) return emptySet()
        return csv.split(",").mapNotNullTo(LinkedHashSet()) { fromName(it) }
    }

    fun format(categories: Set<AppCategory>): String? =
        OFFERED_CATEGORIES.filter { it in categories }.takeIf { it.isNotEmpty() }?.joinToString(",")

    /** A category by its stored or exported name; null for unknown names and for UNDEFINED. */
    fun fromName(name: String): AppCategory? =
        AppCategory.entries.firstOrNull { it.name == name.trim() }?.takeIf { it in OFFERED_CATEGORIES }
}
