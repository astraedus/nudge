package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.db.entity.BlockRule

/**
 * Pure policy for the group half of a backup import: does merging the file's groups into this
 * device's groups REDUCE protection?
 *
 * An import only ever adds members to a group that already exists here (matched by name,
 * [ImportedGroupMerge]), never removes one, so the one way it can weaken is a member JOINING a group
 * that an enabled All apps rule lists as allowed: that app stops being covered by the rule (spec
 * 5.6). The question is answered by [GroupWeakening] against [GroupUsage.of], the same pair the
 * group editor asks.
 *
 * Both sides arrive RESOLVED on this device (installed apps + essentials), so a merge whose new
 * categories bring in five apps under an All apps allow is weakening and one that brings in none is
 * not. A group in the file with no namesake here is created fresh, has a new id and so is allowed by
 * no existing rule: always free (it has no entry in [existing]).
 *
 * No Android imports — unit-testable on the JVM.
 */
object ImportedGroupsWeakening {

    /**
     * @param existing this device's groups by name, resolved, as they are now.
     * @param merged the same groups by name, resolved after [ImportedGroupMerge]. A name missing
     *   from [merged] is untouched.
     * @param rules every rule on this device, enabled or not ([GroupUsage.of] filters).
     */
    fun isWeakening(
        existing: Map<String, ResolvedGroup>,
        merged: Map<String, ResolvedGroup>,
        rules: List<BlockRule>
    ): Boolean =
        existing.any { (name, current) ->
            val after = merged[name] ?: return@any false
            GroupWeakening.isWeakening(current, after, GroupUsage.of(current.id, rules))
        }
}
