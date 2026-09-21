package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ExportRulesUseCase @Inject constructor(
    private val repository: BlockRuleRepository,
    private val usageRepository: UsageRepository,
    private val preferences: NudgePreferences,
    private val exporter: RuleExporter
) {

    /**
     * Exports EVERY rule with its on/off state, their groups, the FULL block/walk-away history and
     * the user's app settings to JSON.
     *
     * Every rule, not just the enabled ones ([#43](https://github.com/astraedus/nudge/issues/43)).
     * A switched-off rule is still part of the user's configuration -- it is how they park a rule
     * they intend to come back to -- and exporting only the enabled ones meant a restore silently
     * dropped it, on the app's only backup path. The `enabled` flag has always ridden in the file
     * and the importer has always read it (defaulting to `true`, which is what a pre-#43 file
     * carrying only enabled rules meant), so this is a change of WHICH rows are collected, not of
     * the format: files stay envelope version 1 in both directions.
     *
     * History is always included and has no toggle: it is the thing that makes the dashboard tiles
     * and both insight pages survive a device move, and an opt-in nobody finds is a backup nobody
     * has. Screen time is not included — it belongs to `UsageStatsManager` and re-derives itself on
     * whatever device the file lands on.
     *
     * Serialization runs OFF the main thread. Retention is not enforced anywhere
     * (`UsageRepository.cleanup` has no call site), so the row count is unbounded and a heavy user's
     * history can be tens of thousands of events — building that string on the UI thread is an ANR.
     */
    suspend fun invoke(): String = withContext(Dispatchers.Default) {
        val rules = repository.getAllRules().firstOrNull() ?: emptyList()
        val groups = repository.getAllGroups().firstOrNull() ?: emptyList()

        // Collect group members for each group
        val groupMembers = groups.associate { group ->
            group.id to (repository.getGroupMembers(group.id).firstOrNull() ?: emptyList())
        }

        val history = usageRepository.getAllEventsForExport()

        // Settings ride along for the same reason history does: a backup that restores a user's
        // rules but not their custom block messages or their Strict Mode difficulty has not
        // actually restored their Nudge. Device-local state is excluded -- see [ExportedSettings].
        val settings = preferences.exportableSettings()

        exporter.exportRules(rules, groups, groupMembers, history, settings)
    }
}
