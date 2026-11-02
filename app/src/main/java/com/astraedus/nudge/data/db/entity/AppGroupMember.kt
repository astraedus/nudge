package com.astraedus.nudge.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One package's place in a group: an include ([excluded] false: the user added it), or a "Removed by
 * you" row ([excluded] true: a category app the user took out). A package has at most one row per
 * group (the primary key), so it is never both.
 *
 * A row is NOT "a member": membership is resolved from the group's categories, its includes and its
 * excludes ([com.astraedus.nudge.domain.groups.GroupMembershipResolver], spec 5.2). Read the rows
 * through `data/repository/GroupDefinitions.kt`, never as a member list.
 */
@Entity(
    tableName = "app_group_members",
    primaryKeys = ["groupId", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = AppGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AppGroupMember(
    val groupId: Long,
    val packageName: String,
    val excluded: Boolean = false
)
