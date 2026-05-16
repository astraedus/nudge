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
     * @param detectedFeature If non-null, feature-scoped rules whose [ActiveRule.inAppFeatures]
     *   list contains this feature will be considered. Whole-app rules are also considered unless
     *   [includeWholeAppRulesForFeature] is false.
     *
     * Priority: HARD_BLOCK > time budget exceeded > DELAY > BREATHING > Allow
     */
    fun evaluate(
        packageName: String,
        activeRules: List<ActiveRule>,
        dailyUsageMs: Long,
        detectedFeature: String? = null,
        includeWholeAppRulesForFeature: Boolean = true
    ): BlockDecision {
        logger.d(
            "evaluate package=$packageName rules=${activeRules.size} " +
                "dailyUsageMs=$dailyUsageMs detectedFeature=$detectedFeature " +
                "includeWholeAppRulesForFeature=$includeWholeAppRulesForFeature"
        )

        val applicableRules = activeRules
            .filter { it.enabled }
            .filter { scheduleEvaluator.isActiveNow(it) }
            .filter { rule ->
                if (detectedFeature != null) {
                    // In-app detection active: match rules that target this feature
                    // and, unless suppressed by passthrough, whole-app rules.
                    val features = rule.inAppFeatures
                    detectedFeature in (features ?: emptyList()) ||
                        (includeWholeAppRulesForFeature && (features == null || features.isEmpty()))
                } else {
                    // No in-app detection: only apply whole-app rules
                    rule.inAppFeatures == null || rule.inAppFeatures.isEmpty()
                }
            }

        if (applicableRules.isEmpty()) {
            logger.d("allow package=$packageName reason=no_applicable_rules")
            return BlockDecision.Allow
        }

        // Compute daily time remaining from the minimum daily limit among applicable rules
        val minDailyLimit = applicableRules.mapNotNull { it.dailyLimitMinutes }.minOrNull()
        val dailyTimeRemainingMs = if (minDailyLimit != null) {
            (minDailyLimit.toLong() * 60L * 1000L - dailyUsageMs).coerceAtLeast(0L)
        } else null

        // Check whether any applicable rule wants grayscale
        val wantsGrayscale = applicableRules.any { it.grayscale }

        // Check for unconditional HARD_BLOCK (no daily limit)
        val unconditionalHardBlockRule = applicableRules.firstOrNull {
            it.mode == BlockMode.HARD_BLOCK && it.dailyLimitMinutes == null
        }
        if (unconditionalHardBlockRule != null) {
            logger.i("block package=$packageName reason=unconditional_hard_block grayscale=$wantsGrayscale")
            return BlockDecision.Block(
                BlockMode.HARD_BLOCK,
                grayscale = wantsGrayscale,
                ruleName = unconditionalHardBlockRule.ruleName,
                dailyTimeRemainingMs = dailyTimeRemainingMs,
                dailyLimitMinutes = minDailyLimit
            )
        }

        // Check if any time budget is exceeded
        val timeBudgetRule = applicableRules.firstOrNull { rule ->
            rule.dailyLimitMinutes != null &&
                dailyUsageMs >= rule.dailyLimitMinutes.toLong() * 60L * 1000L
        }
        if (timeBudgetRule != null) {
            logger.i("block package=$packageName reason=time_budget_exceeded grayscale=$wantsGrayscale")
            val budgetRuleName = timeBudgetRule.ruleName?.let { "$it (limit reached)" }
            return BlockDecision.Block(
                BlockMode.HARD_BLOCK,
                grayscale = wantsGrayscale,
                ruleName = budgetRuleName,
                dailyTimeRemainingMs = dailyTimeRemainingMs,
                dailyLimitMinutes = minDailyLimit
            )
        }

        // Check for DELAY rules
        val delayRule = applicableRules.firstOrNull { it.mode == BlockMode.DELAY }
        if (delayRule != null) {
            logger.i(
                "block package=$packageName reason=delay_rule " +
                    "delaySeconds=${delayRule.delaySeconds} grayscale=$wantsGrayscale"
            )
            return BlockDecision.Block(
                BlockMode.DELAY,
                delayRule.delaySeconds,
                wantsGrayscale,
                ruleName = delayRule.ruleName,
                dailyTimeRemainingMs = dailyTimeRemainingMs,
                dailyLimitMinutes = minDailyLimit
            )
        }

        // Check for BREATHING rules
        val breathingRule = applicableRules.firstOrNull { it.mode == BlockMode.BREATHING }
        if (breathingRule != null) {
            logger.i(
                "block package=$packageName reason=breathing_rule " +
                    "delaySeconds=${breathingRule.delaySeconds} grayscale=$wantsGrayscale"
            )
            return BlockDecision.Block(
                BlockMode.BREATHING,
                breathingRule.delaySeconds,
                wantsGrayscale,
                ruleName = breathingRule.ruleName,
                dailyTimeRemainingMs = dailyTimeRemainingMs,
                dailyLimitMinutes = minDailyLimit
            )
        }

        logger.d("allow package=$packageName reason=no_matching_block_mode")
        return BlockDecision.Allow
    }
}
