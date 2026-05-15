package com.astraedus.nudge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "block_rules")
data class BlockRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String? = null,
    val groupId: Long? = null,
    val mode: String,
    val delaySeconds: Int = 15,
    val dailyLimitMinutes: Int? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
