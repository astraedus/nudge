package com.astraedus.nudge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_groups")
data class AppGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconName: String? = null,
    val color: Long = 0xFF1B6B5A,
    /**
     * The categories whose apps are members (spec 5.2), as a CSV of
     * [com.astraedus.nudge.domain.packages.AppCategory] names (`SOCIAL,VIDEO`). NULL = hand-picked
     * only. Read through [com.astraedus.nudge.domain.groups.AppCategoryCsv], never split by hand.
     */
    val categories: String? = null
)
