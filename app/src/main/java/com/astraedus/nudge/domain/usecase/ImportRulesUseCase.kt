package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.ExportedRule
import com.astraedus.nudge.data.export.ImportResult
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.repository.BlockRuleRepository
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject

data class ImportOutcome(
    val importedCount: Int,
    val skippedCount: Int,
    val groupsCreated: Int,
    val error: String? = null
)

class ImportRulesUseCase @Inject constructor(
    private val repository: BlockRuleRepository,
    private val exporter: RuleExporter
) {

    /**
     * Parses and validates JSON without inserting. Returns ImportResult for preview.
     */
    fun preview(json: String): ImportResult {
        return exporter.importRules(json)
    }

    /**
     * Imports rules from a validated ImportResult into the database.
     * - Creates groups that don't exist yet (by name).
     * - Skips duplicate rules (same packageName + mode + schedule).
     * - Assigns new IDs to all imported rules.
     */
    suspend fun execute(result: ImportResult): ImportOutcome {
        if (result.error != null) {
            return ImportOutcome(0, 0, 0, error = result.error)
        }

        // Step 1: Resolve groups - find existing by name or create new ones
        val existingGroups = repository.getAllGroups().firstOrNull() ?: emptyList()
        val groupNameToId = existingGroups.associateBy({ it.name }, { it.id }).toMutableMap()
        var groupsCreated = 0

        for (exportedGroup in result.groups) {
            if (exportedGroup.name !in groupNameToId) {
                val newId = repository.createGroup(AppGroup(name = exportedGroup.name))
                groupNameToId[exportedGroup.name] = newId
                groupsCreated++
            }
            // Add members to group
            val groupId = groupNameToId[exportedGroup.name]!!
            val existingMembers = repository.getGroupMembers(groupId).firstOrNull()
                ?.map { it.packageName }?.toSet() ?: emptySet()

            for (memberPkg in exportedGroup.members) {
                if (memberPkg !in existingMembers) {
                    repository.addToGroup(AppGroupMember(groupId = groupId, packageName = memberPkg))
                }
            }
        }

        // Step 2: Get existing rules for duplicate detection
        val existingRules = repository.getAllRules().firstOrNull() ?: emptyList()

        // Step 3: Insert rules, skipping duplicates
        var imported = 0
        var skipped = 0

        for (exportedRule in result.rules) {
            val groupId = exportedRule.groupName?.let { groupNameToId[it] }

            if (isDuplicate(exportedRule, groupId, existingRules)) {
                skipped++
                continue
            }

            val blockRule = BlockRule(
                packageName = exportedRule.packageName,
                groupId = groupId,
                mode = exportedRule.mode,
                delaySeconds = exportedRule.delaySeconds,
                dailyLimitMinutes = exportedRule.dailyLimitMinutes,
                enabled = exportedRule.enabled,
                scheduleDays = exportedRule.scheduleDays,
                scheduleStartMinute = exportedRule.scheduleStartMinute,
                scheduleEndMinute = exportedRule.scheduleEndMinute,
                inAppFeatures = exportedRule.inAppFeatures,
                grayscale = exportedRule.grayscale,
                showCounter = exportedRule.showCounter,
                autoKickAfter = exportedRule.autoKickAfter,
                showTimeRemaining = exportedRule.showTimeRemaining,
                autoKickCooldownSeconds = exportedRule.autoKickCooldownSeconds
            )

            repository.addRule(blockRule)
            imported++
        }

        return ImportOutcome(
            importedCount = imported,
            skippedCount = skipped,
            groupsCreated = groupsCreated
        )
    }

    /**
     * A rule is considered a duplicate if an existing rule has the same:
     * - packageName (or groupId)
     * - mode
     * - scheduleDays + scheduleStartMinute + scheduleEndMinute
     */
    private fun isDuplicate(
        exported: ExportedRule,
        resolvedGroupId: Long?,
        existingRules: List<BlockRule>
    ): Boolean {
        return existingRules.any { existing ->
            val sameTarget = when {
                exported.packageName != null -> existing.packageName == exported.packageName
                resolvedGroupId != null -> existing.groupId == resolvedGroupId
                else -> false
            }
            sameTarget &&
                existing.mode == exported.mode &&
                existing.scheduleDays == exported.scheduleDays &&
                existing.scheduleStartMinute == exported.scheduleStartMinute &&
                existing.scheduleEndMinute == exported.scheduleEndMinute
        }
    }
}
