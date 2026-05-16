package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.logging.NudgeLog
import javax.inject.Inject

class BlockEngine @Inject constructor(
    private val scheduleEvaluator: ScheduleEvaluator,
    private val logger: NudgeLog = NudgeLog.NoOp
) {

    /**
     * Evaluate whether a package should be blocked based on active rules and daily usage.
     *
     * @param detectedFeature If non-null, only rules whose [ActiveRule.inAppFeatures] list
     *   contains this feature (or whose inAppFeatures is null/empty, meaning whole-app block)
     *   will be considered.
     *
     * Priority: HARD_BLOCK > time budget exceeded > DELAY > BREATHING > Allow
     */
    fun evaluate(
        packageName: String,
        activeRules: List<ActiveRule>,
        dailyUsageMs: Long,
        detectedFeature: String? = null
    ): BlockDecision {
        logger.d(
            "evaluate package=$packageName rules=${activeRules.size} " +
                "dailyUsageMs=$dailyUsageMs detectedFeature=$detectedFeature"
        )

        val applicableRules = activeRules
            .filter { it.enabled }
            .filter { scheduleEvaluator.isActiveNow(it) }
            .filter { rule ->
                if (detectedFeature != null) {
                    // In-app detection active: match rules that target this feature
                    // or whole-app rules (inAppFeatures == null/empty)
                    val features = rule.inAppFeatures
                    features == null || features.isEmpty() || detectedFeature in features
                } else {
                    // No in-app detection: only apply whole-app rules
                    rule.inAppFeatures == null || rule.inAppFeatures.isEmpty()
                }
            }

        if (applicableRules.isEmpty()) {
            logger.d("allow package=$packageName reason=no_applicable_rules")
            return BlockDecision.Allow
        }

        // Check whether any applicable rule wants grayscale
        val wantsGrayscale = applicableRules.any { it.grayscale }

        // Check for unconditional HARD_BLOCK (no daily limit)
        val unconditionalHardBlock = applicableRules.any {
            it.mode == BlockMode.HARD_BLOCK && it.dailyLimitMinutes == null
        }
        if (unconditionalHardBlock) {
            logger.i("block package=$packageName reason=unconditional_hard_block grayscale=$wantsGrayscale")
            return BlockDecision.Block(BlockMode.HARD_BLOCK, grayscale = wantsGrayscale)
        }

        // Check if any time budget is exceeded
        val timeBudgetExceeded = applicableRules.any { rule ->
            rule.dailyLimitMinutes != null &&
                dailyUsageMs >= rule.dailyLimitMinutes.toLong() * 60L * 1000L
        }
        if (timeBudgetExceeded) {
            logger.i("block package=$packageName reason=time_budget_exceeded grayscale=$wantsGrayscale")
            return BlockDecision.Block(BlockMode.HARD_BLOCK, grayscale = wantsGrayscale)
        }

        // Check for DELAY rules
        val delayRule = applicableRules.firstOrNull { it.mode == BlockMode.DELAY }
        if (delayRule != null) {
            logger.i(
                "block package=$packageName reason=delay_rule " +
                    "delaySeconds=${delayRule.delaySeconds} grayscale=$wantsGrayscale"
            )
            return BlockDecision.Block(BlockMode.DELAY, delayRule.delaySeconds, wantsGrayscale)
        }

        // Check for BREATHING rules
        val breathingRule = applicableRules.firstOrNull { it.mode == BlockMode.BREATHING }
        if (breathingRule != null) {
            logger.i(
                "block package=$packageName reason=breathing_rule " +
                    "delaySeconds=${breathingRule.delaySeconds} grayscale=$wantsGrayscale"
            )
            return BlockDecision.Block(BlockMode.BREATHING, breathingRule.delaySeconds, wantsGrayscale)
        }

        logger.d("allow package=$packageName reason=no_matching_block_mode")
        return BlockDecision.Allow
    }
}
