package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.export.ExportedGroup
import com.astraedus.nudge.domain.groups.AppCategoryCsv
import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.packages.AppCategory

/**
 * Pure merge of a backup's groups into this device's group DEFINITIONS (spec 5.2). The one function
 * both the import write and the Strict Mode gate ([ImportedGroupsWeakening]) use, so the gate
 * judges exactly what the write does.
 *
 * Rules only add. An import never removes a member of an existing group:
 *  - categories are unioned;
 *  - an incoming include is written as an include (an existing "Removed by you" row for it flips to
 *    an include: that adds protection);
 *  - an incoming exclude is written only when it cannot remove a current or future member here: the
 *    package is not an include here AND (the group has no categories, OR the package is installed
 *    and its category is not in the group's categories). Otherwise it is skipped. Judged against the
 *    categories the group had BEFORE the entry, so a new group (no categories yet) keeps the
 *    excludes it was exported with.
 *
 * A file without the definition fields (an old backup) contributes its `members` as includes. A file
 * with them contributes `categories` (unknown names and UNDEFINED skipped), `includes` and
 * `excluded` and IGNORES `members`: those are the source device's resolution, and importing them as
 * includes would freeze category apps into hand-picked rows.
 */
object ImportedGroupMerge {

    /** Folds [entries] (one group name, in file order; the merge is cumulative) into [current]. */
    fun merge(
        current: GroupDefinition,
        entries: List<ExportedGroup>,
        installed: Map<String, AppCategory>
    ): GroupDefinition = entries.fold(current) { def, entry -> mergeOne(def, entry, installed) }

    private fun mergeOne(
        current: GroupDefinition,
        entry: ExportedGroup,
        installed: Map<String, AppCategory>
    ): GroupDefinition {
        val incomingCategories = if (entry.hasDefinition) {
            entry.categories.mapNotNullTo(LinkedHashSet(), AppCategoryCsv::fromName)
        } else {
            emptySet()
        }
        val incomingIncludes = if (entry.hasDefinition) entry.includes else entry.members
        val incomingExcludes = if (entry.hasDefinition) entry.excluded else emptyList()

        val includes = current.includes + incomingIncludes
        val addedExcludes = incomingExcludes.filter { pkg ->
            pkg !in includes && (
                current.categories.isEmpty() ||
                    installed[pkg]?.let { it !in current.categories } == true
                )
        }
        return current.copy(
            categories = current.categories + incomingCategories,
            includes = includes,
            excludes = (current.excludes - includes) + addedExcludes
        )
    }
}
