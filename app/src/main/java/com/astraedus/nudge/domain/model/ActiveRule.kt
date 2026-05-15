package com.astraedus.nudge.domain.model

data class ActiveRule(
    val mode: BlockMode,
    val delaySeconds: Int,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean
)
