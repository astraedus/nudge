package com.astraedus.nudge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_events")
data class UsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val wasBlocked: Boolean = false,
    val blockMode: String? = null
)
