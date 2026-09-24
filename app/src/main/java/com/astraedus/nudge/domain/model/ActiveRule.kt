package com.astraedus.nudge.domain.model

data class ActiveRule(
    val mode: BlockMode,
    val delaySeconds: Int,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean,
    val scheduleDays: List<Int>? = null,
    val scheduleStartMinute: Int? = null,
    val scheduleEndMinute: Int? = null,
    val inAppFeatures: List<String>? = null,
    val grayscale: Boolean = false,
    val ruleName: String? = null,
    /** See [com.astraedus.nudge.data.db.entity.BlockRule.tabVanish]. */
    val tabVanish: Boolean = true,
    /** See [com.astraedus.nudge.data.db.entity.BlockRule.followingSteer]. */
    val followingSteer: Boolean = false
)
