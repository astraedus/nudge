package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.repository.BlockRuleRepository
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject

class ExportRulesUseCase @Inject constructor(
    private val repository: BlockRuleRepository,
    private val exporter: RuleExporter
) {

    /**
     * Exports all enabled rules and their associated groups to a JSON string.
     */
    suspend fun invoke(): String {
        val rules = repository.getEnabledRules().firstOrNull() ?: emptyList()
        val groups = repository.getAllGroups().firstOrNull() ?: emptyList()

        // Collect group members for each group
        val groupMembers = groups.associate { group ->
            group.id to (repository.getGroupMembers(group.id).firstOrNull() ?: emptyList())
        }

        return exporter.exportRules(rules, groups, groupMembers)
    }
}
