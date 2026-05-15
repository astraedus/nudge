package com.astraedus.nudge.domain.model

data class BlockRuleData(
    val id: Long,
    val packageName: String?,
    val groupId: Long?,
    val mode: BlockMode,
    val delaySeconds: Int,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean
)
