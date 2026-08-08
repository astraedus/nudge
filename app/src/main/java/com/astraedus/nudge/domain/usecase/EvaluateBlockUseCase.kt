package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

class EvaluateBlockUseCase @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository,
    private val usageRepository: UsageRepository,
    private val blockEngine: BlockEngine,
    private val ruleEvaluator: RuleEvaluator,
    private val preferences: NudgePreferences,
    private val contentFilter: ContentFilter
) {

    /**
     * Evaluate whether a package should be blocked right now.
     * Combines rule resolution, daily usage lookup, and the block engine decision.
     *
     * @param detectedFeature If the accessibility service detected an in-app feature
     *   (e.g. "REELS", "SHORTS"), pass it here so the engine can match feature-level rules.
     * @param includeWholeAppRulesForFeature Whether feature evaluation should also consider
     *   whole-app rules. This is disabled after a whole-app delay has completed so in-app rules
     *   can still fire without looping the whole-app gate.
     */
    suspend fun invoke(
        packageName: String,
        detectedFeature: String? = null,
        includeWholeAppRulesForFeature: Boolean = true
    ): BlockDecision {
        val allRules = blockRuleRepository.getEnabledRules().first()
        val allGroups = blockRuleRepository.getAllGroups().first()

        // Convert entity rules to domain data classes
        val ruleDataList = allRules.map { rule ->
            BlockRuleData(
                id = rule.id,
                packageName = rule.packageName,
                groupId = rule.groupId,
                mode = try { BlockMode.valueOf(rule.mode) } catch (_: Exception) { BlockMode.HARD_BLOCK },
                delaySeconds = rule.delaySeconds,
                dailyLimitMinutes = rule.dailyLimitMinutes,
                enabled = rule.enabled,
                scheduleDays = rule.scheduleDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() },
                scheduleStartMinute = rule.scheduleStartMinute,
                scheduleEndMinute = rule.scheduleEndMinute,
                inAppFeatures = rule.inAppFeatures?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
                grayscale = rule.grayscale,
                webDomains = rule.webDomains
            )
        }

        // Build group memberships from all groups
        val memberships = mutableListOf<GroupMembership>()
        for (group in allGroups) {
            val members = blockRuleRepository.getGroupMembers(group.id).first()
            for (member in members) {
                memberships.add(GroupMembership(groupId = member.groupId, packageName = member.packageName))
            }
        }

        val activeRules = ruleEvaluator.resolveRulesForPackage(packageName, ruleDataList, memberships)
        val dailyUsageMs = dailyUsageMs(packageName)

        return blockEngine.evaluate(
            packageName = packageName,
            activeRules = activeRules,
            dailyUsageMs = dailyUsageMs,
            detectedFeature = detectedFeature,
            includeWholeAppRulesForFeature = includeWholeAppRulesForFeature
        )
    }

    /**
     * Evaluate whether a detected web domain should be blocked.
     * Checks all enabled rules that have webDomains configured and matches
     * the detected URL against them.
     *
     * @param urlBarText The text from the browser's URL bar
     * @return BlockDecision and the matching rule's associated packageName (for usage tracking)
     */
    suspend fun evaluateWebDomain(urlBarText: String): WebDomainBlockResult {
        val allRules = blockRuleRepository.getEnabledRules().first()

        // Find rules with webDomains that match the detected URL
        val matchingRules = allRules.filter { rule ->
            rule.webDomains != null && WebDomainMatcher.matches(urlBarText, rule.webDomains)
        }

        if (matchingRules.isEmpty()) {
            // No explicit per-rule web domain match. Fall through to the generic
            // content filter (bundled blocklist + keywords) if it is enabled.
            return evaluateContentFilter(urlBarText)
        }

        // Convert matching rules to ActiveRules for BlockEngine evaluation
        val activeRules = matchingRules.map { rule ->
            ActiveRule(
                mode = try { BlockMode.valueOf(rule.mode) } catch (_: Exception) { BlockMode.HARD_BLOCK },
                delaySeconds = rule.delaySeconds,
                dailyLimitMinutes = rule.dailyLimitMinutes,
                enabled = rule.enabled,
                scheduleDays = rule.scheduleDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() },
                scheduleStartMinute = rule.scheduleStartMinute,
                scheduleEndMinute = rule.scheduleEndMinute,
                inAppFeatures = null, // Web domain rules apply as whole-app rules
                grayscale = rule.grayscale,
                ruleName = buildWebDomainRuleName(rule.packageName, rule.mode)
            )
        }

        // Use the first matching rule's package for usage stats lookup
        val trackingPackage = matchingRules.first().packageName ?: "web"
        val dailyUsageMs = dailyUsageMs(trackingPackage)

        val decision = blockEngine.evaluate(
            packageName = trackingPackage,
            activeRules = activeRules,
            dailyUsageMs = dailyUsageMs
        )

        return WebDomainBlockResult(decision, trackingPackage)
    }

    /**
     * Generic content-filter evaluation: when enabled, blocks [urlBarText] if it
     * matches the bundled blocklist or a high-signal keyword, using the
     * user-configured content-filter mode. The rule name surfaced on the overlay
     * is intentionally generic ("Restricted content").
     */
    private suspend fun evaluateContentFilter(urlBarText: String): WebDomainBlockResult {
        if (!preferences.contentFilterEnabled.first()) {
            return WebDomainBlockResult(BlockDecision.Allow, null)
        }
        val strict = preferences.contentFilterStrictKeywords.first()
        if (!contentFilter.isBlocked(urlBarText, strict)) {
            return WebDomainBlockResult(BlockDecision.Allow, null)
        }

        val mode = try {
            BlockMode.valueOf(preferences.contentFilterMode.first())
        } catch (_: Exception) {
            BlockMode.HARD_BLOCK
        }

        // Track usage under a synthetic "web" package, consistent with how
        // web-domain rules without an associated app are tracked.
        val trackingPackage = "web"
        val dailyUsageMs = dailyUsageMs(trackingPackage)

        val activeRule = ActiveRule(
            mode = mode,
            delaySeconds = CONTENT_FILTER_DELAY_SECONDS,
            dailyLimitMinutes = null,
            enabled = true,
            inAppFeatures = null,
            grayscale = false,
            ruleName = "Restricted content"
        )

        val decision = blockEngine.evaluate(
            packageName = trackingPackage,
            activeRules = listOf(activeRule),
            dailyUsageMs = dailyUsageMs
        )

        return WebDomainBlockResult(decision, trackingPackage)
    }

    /**
     * Today's foreground time for [packageName], the number the daily budget is spent against.
     *
     * This MUST come from `UsageStatsManager` (issue #14). The obvious-looking alternative — the
     * Room `usage_events` table — cannot work: that table logs block/allow *decisions*, and its
     * `durationMs` column is never written, so summing it returns 0 for every package forever.
     * Feeding that 0 to [BlockEngine] made `dailyUsageMs >= limit` permanently false, so the
     * daily-limit HARD_BLOCK never fired and the "X left today" line on the overlay was pinned at
     * the full limit. `TimeRemainingHandler` already reads the correct source, which is why the
     * limit appeared to work — but only for rules that had opted into the time-remaining overlay.
     *
     * The read is a synchronous binder call, hence [Dispatchers.IO]; it returns 0 without throwing
     * when Usage Access has not been granted, which fails toward *allowing* the app. That is the
     * right direction: a permission the user has not granted must not manufacture a block.
     */
    private suspend fun dailyUsageMs(packageName: String): Long =
        withContext(Dispatchers.IO) { usageRepository.getDailyForegroundTimeMs(packageName) }

    private fun buildWebDomainRuleName(packageName: String?, mode: String): String {
        val modeName = when (mode) {
            "HARD_BLOCK" -> "Hard Block"
            "DELAY" -> "Delay"
            "BREATHING" -> "Breathing"
            else -> mode
        }
        return "Web - $modeName"
    }

    private companion object {
        // Default delay applied when content filter mode is DELAY (no per-rule
        // delay exists for the generic filter).
        const val CONTENT_FILTER_DELAY_SECONDS = 15
    }
}

data class WebDomainBlockResult(
    val decision: BlockDecision,
    val trackingPackage: String?
)
