package com.astraedus.nudge.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

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
    val packageName: String
)
