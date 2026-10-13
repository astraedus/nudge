package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.export.ExportedHistoryEvent
import com.astraedus.nudge.data.export.ExportedRule
import com.astraedus.nudge.data.export.HistoryMerge
import com.astraedus.nudge.data.export.ImportResult
import com.astraedus.nudge.data.export.RuleExporter
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.lock.ImportedSettingsWeakening
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What an import actually did. The two "skipped" counts are deliberately separate: a duplicate was
 * understood and intentionally not re-added, an invalid entry could not be read at all -- only the
 * second one means the user lost something from their backup, so the UI must not merge them.
 */
data class ImportOutcome(
    val importedCount: Int,
    /** Rules already present in the DB (same target + mode + schedule), so not re-added. */
    val duplicateCount: Int,
    val groupsCreated: Int,
    /** Entries in the file that could not be parsed and were skipped (see [ImportResult]). */
    val invalidCount: Int = 0,
    /** One human-readable reason per skipped entry, in file order. */
    val invalidReasons: List<String> = emptyList(),
    /** History events written to `usage_events`. */
    val historyImportedCount: Int = 0,
    /** History events already present (same package + timestamp + flags), so not re-added. */
    val historyDuplicateCount: Int = 0,
    /** History events in the file that could not be read at all. */
    val historyInvalidCount: Int = 0,
    /** True when the file carried app settings and they were written to this device. */
    val settingsApplied: Boolean = false,
    /** Individual settings in the file that could not be read and were left out. */
    val settingsInvalidCount: Int = 0,
    val error: String? = null
)

/**
 * What an import WOULD do, shown to the user before anything is written.
 *
 * [newHistoryCount] is separate from `result.history.size` because history merges: restoring the
 * same backup twice is legitimate and must read as "0 new", not as a threat to double every stat.
 */
data class ImportPreview(
    val result: ImportResult,
    val newHistoryCount: Int
)

class ImportRulesUseCase @Inject constructor(
    private val repository: BlockRuleRepository,
    private val usageRepository: UsageRepository,
    private val preferences: NudgePreferences,
    private val exporter: RuleExporter
) {

    /**
     * Whether applying [result]'s settings would REDUCE this device's protection — the question the
     * caller must ask before writing anything, because an import that weakens protection has to
     * pass the Strict Mode unlock challenge exactly like every other weakening action.
     *
     * An export file is plain, hand-editable JSON. Without this gate, `"strictModeEnabled": false`
     * typed into a text editor would be a one-tap way out of the commitment lock.
     *
     * Reads the LIVE preferences rather than anything captured at preview time, for the same reason
     * [execute] re-derives the new-history count instead of trusting the preview's: the user may
     * have changed a setting between seeing the dialog and confirming it.
     */
    suspend fun weakensProtection(result: ImportResult): Boolean {
        val incoming = result.settings ?: return false
        return ImportedSettingsWeakening.isWeakening(preferences.exportableSettings(), incoming)
    }

    /**
     * Parses and validates JSON without inserting, and asks the DB how much of the file's history
     * is actually new.
     *
     * Suspending and off-main: history is unbounded (retention is not enforced), so parsing a
     * heavy user's backup on the UI thread is an ANR.
     */
    suspend fun preview(json: String): ImportPreview {
        val result = withContext(Dispatchers.Default) { exporter.importRules(json) }
        if (result.error != null) return ImportPreview(result, newHistoryCount = 0)
        return ImportPreview(result, newHistoryCount = newHistoryEvents(result.history).size)
    }

    /**
     * Imports rules from a validated ImportResult into the database.
     * - Creates groups that don't exist yet (by name).
     * - Skips duplicate rules (same packageName + mode + schedule).
     * - Assigns new IDs to all imported rules.
     * - Applies the file's app settings, if it carries any, in ONE transaction.
     * - MERGES usage history, skipping events already present (see [HistoryMerge]).
     * - Carries forward the entries the parser had to skip, so the UI can report them.
     *
     * The CALLER is responsible for having passed [weakensProtection] through the Strict Mode gate
     * first: this method writes.
     */
    suspend fun execute(result: ImportResult): ImportOutcome {
        if (result.error != null) {
            return ImportOutcome(
                importedCount = 0,
                duplicateCount = 0,
                groupsCreated = 0,
                invalidCount = result.invalidCount,
                invalidReasons = result.invalidReasons,
                historyInvalidCount = result.invalidHistoryCount,
                settingsInvalidCount = result.invalidSettingsCount,
                error = result.error
            )
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
        var duplicates = 0

        for (exportedRule in result.rules) {
            val groupId = exportedRule.groupName?.let { groupNameToId[it] }

            if (isDuplicate(exportedRule, groupId, existingRules)) {
                duplicates++
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
                autoKickCooldownSeconds = exportedRule.autoKickCooldownSeconds,
                webDomains = exportedRule.webDomains,
                autoKickAfterMinutes = exportedRule.autoKickAfterMinutes,
                remindAfterMinutes = exportedRule.remindAfterMinutes,
                webBlockMode = exportedRule.webBlockMode
            )

            repository.addRule(blockRule)
            imported++
        }

        // Step 4: Apply the file's app settings, if it carries any. After the rules (which protect
        // the user) and before history (which only feeds statistics). All-or-nothing: one DataStore
        // transaction writes every carried key or none -- see [NudgePreferences.applyImportedSettings].
        //
        // Any weakening this payload represents was gated BEFORE we got here (see
        // [weakensProtection]); by this point the user has either not been weakening anything or has
        // already typed the unlock challenge.
        result.settings?.let { preferences.applyImportedSettings(it) }

        // Step 5: Merge usage history. Deliberately last -- rules are what protect the user, and a
        // history restore must never be able to stand between them and their rules.
        val fresh = newHistoryEvents(result.history)
        if (fresh.isNotEmpty()) {
            usageRepository.insertEvents(
                withContext(Dispatchers.Default) { fresh.map(HistoryMerge::toEntity) }
            )
        }

        return ImportOutcome(
            importedCount = imported,
            duplicateCount = duplicates,
            groupsCreated = groupsCreated,
            invalidCount = result.invalidCount,
            invalidReasons = result.invalidReasons,
            historyImportedCount = fresh.size,
            historyDuplicateCount = result.history.size - fresh.size,
            historyInvalidCount = result.invalidHistoryCount,
            settingsApplied = result.settings != null,
            settingsInvalidCount = result.invalidSettingsCount
        )
    }

    /**
     * The events in [history] that are not already stored.
     *
     * Only the file's own timestamp window is read back, so restoring one week of a backup does not
     * pull an entire history into memory. Used by BOTH [preview] and [execute] -- the count the
     * user is shown and the rows actually written come from the same policy, and execute re-reads
     * rather than trusting the preview's number.
     */
    private suspend fun newHistoryEvents(
        history: List<ExportedHistoryEvent>
    ): List<ExportedHistoryEvent> {
        val range = HistoryMerge.timestampRange(history) ?: return emptyList()
        val existing = usageRepository.getEventKeysInRange(range.first, range.last).toHashSet()
        return withContext(Dispatchers.Default) { HistoryMerge.selectNew(history, existing) }
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
