package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.domain.groups.AppCategoryCsv
import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.groups.GroupMembershipResolver

/**
 * The group tables as definitions (spec 5.2): each group's categories (`app_groups.categories`), its
 * includes (member rows with `excluded` = 0) and its "Removed by you" (rows with `excluded` = 1).
 *
 * The ONE place the rows are read as a group. A member row is not a member (a category app is a
 * member with no row; a "Removed by you" row is a non-member), so every reader that used to list
 * rows goes through this and [GroupMembershipResolver]. Rows of a group that does not exist are
 * dropped (the foreign key cascades them away, so this is a no-op on a healthy database).
 */
fun groupDefinitionsOf(groups: List<AppGroup>, members: List<AppGroupMember>): List<GroupDefinition> {
    val rowsByGroup = members.groupBy { it.groupId }
    return groups.map { group -> group.definition(rowsByGroup[group.id].orEmpty()) }
}

/** [groupDefinitionsOf], ready to answer membership questions. */
fun groupResolverOf(groups: List<AppGroup>, members: List<AppGroupMember>): GroupMembershipResolver =
    GroupMembershipResolver(groupDefinitionsOf(groups, members))

/** This group's definition from its own member rows ([rows] of other groups are ignored). */
fun AppGroup.definition(rows: List<AppGroupMember>): GroupDefinition {
    val own = rows.filter { it.groupId == id }
    return GroupDefinition(
        id = id,
        categories = AppCategoryCsv.parse(categories),
        includes = own.filterNot { it.excluded }.mapTo(LinkedHashSet()) { it.packageName },
        excludes = own.filter { it.excluded }.mapTo(LinkedHashSet()) { it.packageName }
    )
}
