package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import javax.inject.Inject

class BlockEngine @Inject constructor() {

    /**
     * Evaluate whether a package should be blocked based on active rules and daily usage.
     *
     * Priority: HARD_BLOCK > time budget exceeded > DELAY > BREATHING > Allow
     */
    fun evaluate(
        packageName: String,
        activeRules: List<ActiveRule>,
        dailyUsageMs: Long
    ): BlockDecision {
        val enabledRules = activeRules.filter { it.enabled }
        if (enabledRules.isEmpty()) return BlockDecision.Allow

        // Check for unconditional HARD_BLOCK (no daily limit)
        val unconditionalHardBlock = enabledRules.any {
            it.mode == BlockMode.HARD_BLOCK && it.dailyLimitMinutes == null
        }
        if (unconditionalHardBlock) return BlockDecision.Block(BlockMode.HARD_BLOCK)

        // Check if any time budget is exceeded
        val timeBudgetExceeded = enabledRules.any { rule ->
            rule.dailyLimitMinutes != null &&
                dailyUsageMs >= rule.dailyLimitMinutes.toLong() * 60L * 1000L
        }
        if (timeBudgetExceeded) return BlockDecision.Block(BlockMode.HARD_BLOCK)

        // Check for DELAY rules
        val delayRule = enabledRules.firstOrNull { it.mode == BlockMode.DELAY }
        if (delayRule != null) return BlockDecision.Block(BlockMode.DELAY, delayRule.delaySeconds)

        // Check for BREATHING rules
        val breathingRule = enabledRules.firstOrNull { it.mode == BlockMode.BREATHING }
        if (breathingRule != null) return BlockDecision.Block(BlockMode.BREATHING, breathingRule.delaySeconds)

        return BlockDecision.Allow
    }
}
