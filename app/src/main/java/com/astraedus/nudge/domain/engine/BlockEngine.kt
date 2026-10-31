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
     * Priority: NUKE > HARD_BLOCK > time budget exceeded > DELAY > HOLD > BREATHING > Allow
     *
     * @param nuked whether Nuke Mode covers [packageName] right now
     *   ([com.astraedus.nudge.domain.nuke.NukePolicy.isNuked], decided by the caller because it
     *   needs persisted state and the device's safety floor). When true it is the FIRST step and
     *   the only one: a hard block with no delay, no budget arithmetic and no rule consulted, so it
     *   overrides every mode and every limit rather than competing with them. Grants (passthrough,
     *   the daily pass, a cooldown) are not inputs here at all; the service skips all three for a
     *   nuked app before it asks. See `docs/architecture/nuke-mode.md`.
     *
     * [BlockMode.NONE] deliberately matches none of the block branches below, so a rule carrying
     * it yields Allow. It still participates in the time-budget check, which keys off
     * `dailyLimitMinutes` rather than the mode — an app-level NONE rule with a daily limit means
     * "don't gate this app, but stop me after N minutes", and its counter/overlay settings still
     * apply. This is what lets a feature-scoped rule (Shorts, Reels) block while its host app
     * stays open.
     */
    fun evaluate(
        packageName: String,
        activeRules: List<ActiveRule>,
        dailyUsageMs: Long,
        detectedFeature: String? = null,
        includeWholeAppRulesForFeature: Boolean = true,
        nuked: Boolean = false
    ): BlockDecision {
        if (nuked) {
            logger.i("block package=$packageName reason=nuke")
            return NUKE_DECISION
        }

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
                dailyLimitMinutes = minDailyLimit,
                tabVanish = unconditionalHardBlockRule.tabVanish
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
                dailyLimitMinutes = minDailyLimit,
                tabVanish = timeBudgetRule.tabVanish
            )
        }

        // The timed modes, strongest first. One ordered scan rather than a block per mode: these
        // branches were three copies of the same eight lines, and BlockMode.HOLD arriving as a
        // fourth copy is exactly how one of them ends up subtly different from its siblings.
        TIMED_MODES_STRONGEST_FIRST.forEach { mode ->
            val rule = applicableRules.firstOrNull { it.mode == mode }
            if (rule != null) {
                logger.i(
                    "block package=$packageName reason=${mode.name.lowercase()}_rule " +
                        "delaySeconds=${rule.delaySeconds} grayscale=$wantsGrayscale"
                )
                return BlockDecision.Block(
                    mode,
                    rule.delaySeconds,
                    wantsGrayscale,
                    ruleName = rule.ruleName,
                    dailyTimeRemainingMs = dailyTimeRemainingMs,
                    dailyLimitMinutes = minDailyLimit,
                    tabVanish = rule.tabVanish,
                    keepPassOnShortTrips = rule.keepPassOnShortTrips
                )
            }
        }

        logger.d("allow package=$packageName reason=no_matching_block_mode")
        return BlockDecision.Allow
    }

    companion object {
        /** What the overlay's rule footer and the logs call a Nuke block. */
        const val NUKE_RULE_NAME = "Nuke"

        /**
         * The one decision Nuke produces. A HARD_BLOCK (so every consumer that already understands a
         * hard block -- stats, the walk-away row, `enforceExhaustedBudget` -- handles it unchanged)
         * flagged [BlockDecision.Block.nuke] so the overlay can drop the daily pass and say why.
         * No grayscale, no daily-limit fields, no tab cover: Nuke is not a rule and carries none of
         * a rule's options.
         */
        val NUKE_DECISION = BlockDecision.Block(
            mode = BlockMode.HARD_BLOCK,
            ruleName = NUKE_RULE_NAME,
            nuke = true
        )

        /**
         * The modes that gate an app behind a duration, in the order a tie is broken when several
         * matching rules are active at once.
         *
         * [BlockMode.HOLD] sits beside [BlockMode.DELAY] because they are the same price in
         * wall-clock time (see `RuleWeakening.modeStrength`, which ranks them equal); the order
         * within the pair only decides which rule wins when a user has configured both for one app
         * at the same moment, and DELAY first keeps that answer identical to what it was before
         * HOLD existed.
         *
         * NOT `BlockMode.entries`: [BlockMode.NONE] gates nothing and [BlockMode.HARD_BLOCK] is
         * decided above, ahead of the daily budget.
         */
        private val TIMED_MODES_STRONGEST_FIRST =
            listOf(BlockMode.DELAY, BlockMode.HOLD, BlockMode.BREATHING)
    }
}
