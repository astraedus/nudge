package com.astraedus.nudge.domain.model

sealed class BlockDecision {
    data object Allow : BlockDecision()
    data class Block(val mode: BlockMode, val delaySeconds: Int = 0) : BlockDecision()
}
