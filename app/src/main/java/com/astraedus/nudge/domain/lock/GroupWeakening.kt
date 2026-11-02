package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.groups.GroupMembershipResolver
import com.astraedus.nudge.domain.model.ExceptCsv
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.domain.model.RuleTarget

/**
 * Resolved membership of one group: the packages it covers on this device (includes + installed
 * category matches - "Removed by you" - essentials, [GroupMembershipResolver.membersOf]). Strict
 * Mode judges what a group COVERS, never how it is written down.
 */
data class ResolvedGroup(val id: Long, val members: Set<String>)

/** How enabled rules use a group, from the saved state. */
data class GroupUsage(val blocks: Boolean, val allowedBy: Boolean) {
    companion object {
        val UNUSED = GroupUsage(blocks = false, allowedBy = false)

        /**
         * blocks = an ENABLED rule targets this group (any mode: a NONE rule with a daily limit
         * still blocks after the limit, and erring toward the challenge is the safe side).
         * allowedBy = an ENABLED All apps rule lists this group as allowed: a member joining the
         * group then stops being covered by that rule. Read through [RuleTarget.of], the engine's
         * reading, so a corrupt row that also names an app or group is not an All apps rule here
         * either.
         */
        fun of(groupId: Long, rules: List<BlockRule>): GroupUsage =
            GroupUsage(
                blocks = rules.any { it.enabled && it.groupId == groupId },
                allowedBy = rules.any { it.enabled && allowsGroup(it, groupId) }
            )

        private fun allowsGroup(rule: BlockRule, groupId: Long): Boolean {
            val target = RuleTarget.of(
                packageName = rule.packageName,
                groupId = rule.groupId,
                allApps = rule.allApps,
                exceptPackages = emptySet(),
                exceptGroupIds = ExceptCsv.groupIds(rule.exceptGroupIds)
            )
            return target is RuleTarget.AllApps && groupId in target.exceptGroupIds
        }
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
    fun isWeakening(old: ResolvedGroup, new: ResolvedGroup, usage: GroupUsage): Boolean {
        val left = (old.members - new.members).isNotEmpty()
        val joined = (new.members - old.members).isNotEmpty()
        return (usage.blocks && left) || (usage.allowedBy && joined)
    }

    /**
     * The same question over two group DEFINITIONS (spec 5.2), resolved on this device first:
     * [installed] maps each installed app to its category, [essentials] are never members. So the
     * answer is about apps, not settings: unchecking a category no installed app is in removes
     * nobody and is free, unchecking one five apps are in is five members leaving, and "Removed by
     * you" on a category app is one member leaving.
     */
    fun isWeakening(
        old: GroupDefinition,
        new: GroupDefinition,
        usage: GroupUsage,
        installed: Map<String, AppCategory>,
        essentials: Set<String>
    ): Boolean = isWeakening(
        ResolvedGroup(old.id, GroupMembershipResolver.membersOf(old, installed, essentials)),
        ResolvedGroup(new.id, GroupMembershipResolver.membersOf(new, installed, essentials)),
        usage
    )
}
