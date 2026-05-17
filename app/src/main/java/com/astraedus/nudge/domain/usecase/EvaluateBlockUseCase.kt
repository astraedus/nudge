package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.BlockRuleData
import com.astraedus.nudge.domain.model.GroupMembership
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class EvaluateBlockUseCase @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository,
    private val usageRepository: UsageRepository,
    private val blockEngine: BlockEngine,
    private val ruleEvaluator: RuleEvaluator
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
        val dailyUsageMs = usageRepository.getDailyUsage(packageName).first()

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
            return WebDomainBlockResult(BlockDecision.Allow, null)
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
        val dailyUsageMs = usageRepository.getDailyUsage(trackingPackage).first()

        val decision = blockEngine.evaluate(
            packageName = trackingPackage,
            activeRules = activeRules,
            dailyUsageMs = dailyUsageMs
        )

        return WebDomainBlockResult(decision, trackingPackage)
    }

    private fun buildWebDomainRuleName(packageName: String?, mode: String): String {
        val modeName = when (mode) {
            "HARD_BLOCK" -> "Hard Block"
            "DELAY" -> "Delay"
            "BREATHING" -> "Breathing"
            else -> mode
        }
        return "Web - $modeName"
    }
}

data class WebDomainBlockResult(
    val decision: BlockDecision,
    val trackingPackage: String?
)
