package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.db.entity.BlockRule

/**
 * Resolved membership of one group: the packages it covers. (Once category groups exist, PR 4, the
 * caller passes includes + category matches - exclusions; this type stays "what it covers".)
 */
data class GroupDefinition(val id: Long, val members: Set<String>)

/** How enabled rules use a group, from the saved state. */
data class GroupUsage(val blocks: Boolean, val allowedBy: Boolean) {
    companion object {
        val UNUSED = GroupUsage(blocks = false, allowedBy = false)

        /**
         * blocks = an ENABLED rule targets this group (any mode: a NONE rule with a daily limit
         * still blocks after the limit, and erring toward the challenge is the safe side).
         * allowedBy = always false until All apps rules exist (PR 4).
         */
        fun of(groupId: Long, rules: List<BlockRule>): GroupUsage =
            GroupUsage(
                blocks = rules.any { it.enabled && it.groupId == groupId },
                allowedBy = false
            )
    }
}

/**
 * Pure-Kotlin decision on whether a group edit WEAKENS protection (Strict Mode gates those behind
 * the unlock challenge). No Android imports -- unit-testable on the JVM.
 */
object GroupWeakening {

    /**
     * Spec 5.6: a member LEAVING while the group blocks is weakening; a member JOINING while an
     * enabled All apps rule allows the group is weakening; both uses = any change; unused = free.
     * Delete = [new] with no members.
     */
    fun isWeakening(old: GroupDefinition, new: GroupDefinition, usage: GroupUsage): Boolean {
        val left = (old.members - new.members).isNotEmpty()
        val joined = (new.members - old.members).isNotEmpty()
        return (usage.blocks && left) || (usage.allowedBy && joined)
    }
}
