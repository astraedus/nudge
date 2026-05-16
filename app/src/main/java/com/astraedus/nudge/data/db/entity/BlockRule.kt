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
    val createdAt: Long = System.currentTimeMillis(),
    // Schedule-based rules (Feature 1)
    val scheduleDays: String? = null,       // comma-separated: "1,2,3,4,5" (1=Mon..7=Sun). null = every day
    val scheduleStartMinute: Int? = null,   // minutes from midnight. 540 = 9:00 AM. null = no schedule
    val scheduleEndMinute: Int? = null,     // minutes from midnight. 1020 = 5:00 PM. null = no schedule
    // In-app feature blocking (Feature 2)
    val inAppFeatures: String? = null,      // comma-separated: "REELS,SHORTS". null = block whole app
    // Grayscale mode (Feature 3)
    val grayscale: Boolean = false,
    // Interaction counter overlay
    val showCounter: Boolean = false
)
