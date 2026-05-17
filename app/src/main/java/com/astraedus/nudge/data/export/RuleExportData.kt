package com.astraedus.nudge.data.export

/**
 * Data classes representing the JSON export format for Nudge rules.
 * Designed to be human-readable and portable between devices.
 */
data class NudgeExport(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val rules: List<ExportedRule>,
    val groups: List<ExportedGroup> = emptyList()
)

data class ExportedRule(
    val packageName: String?,
    val groupName: String?, // Resolved from groupId -> group name for portability
    val mode: String,
    val delaySeconds: Int,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean,
    val scheduleDays: String?,
    val scheduleStartMinute: Int?,
    val scheduleEndMinute: Int?,
    val inAppFeatures: String?,
    val grayscale: Boolean,
    val showCounter: Boolean,
    val autoKickAfter: Int?,
    val showTimeRemaining: Boolean,
    val autoKickCooldownSeconds: Int,
    val webDomains: String? = null
)

data class ExportedGroup(
    val name: String,
    val members: List<String> // package names
)
