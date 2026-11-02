package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.ExportedGroup

/**
 * Pure policy for the group half of a backup import: does merging the file's groups into this
 * device's groups REDUCE protection?
 *
 * An import only ever adds members to a group that already exists here (matched by name), never
 * removes one, so the one way it can weaken is a member JOINING a group that an enabled All apps
 * rule lists as allowed: that app stops being covered by the rule (spec 5.6). The question is
 * answered by [GroupWeakening] against [GroupUsage.of], the same pair the group editor asks, on the
 * merged result.
 *
 * A group in the file with no namesake here is created fresh, has a new id and so is allowed by no
 * existing rule: always free. So is everything else an import does with groups.
 *
 * No Android imports — unit-testable on the JVM.
 */
object ImportedGroupsWeakening {

    /**
     * @param existing this device's groups by name, the SAME name-to-group map the import merges
     *   into (so the gate judges exactly the groups the write will touch).
     * @param incoming the file's groups. A name may appear more than once; the merge is cumulative.
     * @param rules every rule on this device, enabled or not ([GroupUsage.of] filters).
     */
    fun isWeakening(
        existing: Map<String, ResolvedGroup>,
        incoming: List<ExportedGroup>,
        rules: List<BlockRule>
    ): Boolean =
        incoming.groupBy { it.name }.any { (name, entries) ->
            val current = existing[name] ?: return@any false
            val merged = current.copy(members = current.members + entries.flatMap { it.members })
            GroupWeakening.isWeakening(current, merged, GroupUsage.of(current.id, rules))
        }
}
