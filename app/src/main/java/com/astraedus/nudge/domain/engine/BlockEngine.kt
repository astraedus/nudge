package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.lightsoff.LightsOffClock
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
     * @param isLightsOffActive Whether a global "Lights Off" lockdown window is open right now.
     *   Computed by the caller (see `EvaluateBlockUseCase`) and passed in as a plain boolean so this
     *   engine stays pure — it never reads a clock or preferences itself.
     * @param isLightsOffWhitelisted Whether [packageName] is on the active Lights Off profile's
     *   allow-list. Note the system-critical safety floor (dialer / launcher / settings / emergency /
     *   keyboard / Nudge) is enforced EARLIER, in the accessibility service, and never reaches here.
     * @param lightsOffRuleName Label shown on the block overlay for a Lights Off block, e.g.
     *   `"Lights Off · until 7:00"`.
     *
     * Priority: Lights Off > HARD_BLOCK > time budget exceeded > DELAY > BREATHING > Allow
     */
    fun evaluate(
        packageName: String,
        activeRules: List<ActiveRule>,
        dailyUsageMs: Long,
        detectedFeature: String? = null,
        includeWholeAppRulesForFeature: Boolean = true,
        isLightsOffActive: Boolean = false,
        isLightsOffWhitelisted: Boolean = false,
        lightsOffRuleName: String = LightsOffClock.RULE_NAME
    ): BlockDecision {
        logger.d(
            "evaluate package=$packageName rules=${activeRules.size} " +
                "dailyUsageMs=$dailyUsageMs detectedFeature=$detectedFeature " +
                "includeWholeAppRulesForFeature=$includeWholeAppRulesForFeature " +
                "lightsOffActive=$isLightsOffActive lightsOffWhitelisted=$isLightsOffWhitelisted"
        )

        // STEP 0 — Lights Off inverts the whole model: during the window EVERY app is off unless the
        // user allow-listed it. It sits above the unconditional-hard-block step (and above the
        // schedule/feature filtering) so it OVERRIDES per-app rules rather than competing with them:
        // an app whose own rule would only DELAY or BREATHE is still hard-blocked, and an app with no
        // rule at all — the "new shiny app that leaks through per-app blocking" this feature exists
        // for — is blocked too. Always HARD_BLOCK: a softer Lights Off mode would let an app that a
        // per-app rule already hard-blocks through, i.e. weaken protection.
        if (isLightsOffActive && !isLightsOffWhitelisted) {
            logger.i("block package=$packageName reason=lights_off rule=$lightsOffRuleName")
            return BlockDecision.Block(
                BlockMode.HARD_BLOCK,
                ruleName = lightsOffRuleName
            )
        }

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
