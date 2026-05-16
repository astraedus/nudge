package com.astraedus.nudge.domain.model

sealed class BlockDecision {
    data object Allow : BlockDecision()
    data class Block(
        val mode: BlockMode,
        val delaySeconds: Int = 0,
        val grayscale: Boolean = false,
        val ruleName: String? = null,
        val dailyTimeRemainingMs: Long? = null,
        val dailyLimitMinutes: Int? = null
    ) : BlockDecision()
}
