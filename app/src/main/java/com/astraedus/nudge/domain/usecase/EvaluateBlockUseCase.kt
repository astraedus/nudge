package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
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
     */
    suspend fun invoke(packageName: String, detectedFeature: String? = null): BlockDecision {
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
                grayscale = rule.grayscale
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

        return blockEngine.evaluate(packageName, activeRules, dailyUsageMs, detectedFeature)
    }
}
