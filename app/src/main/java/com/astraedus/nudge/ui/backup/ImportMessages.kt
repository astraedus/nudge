package com.astraedus.nudge.ui.backup

import com.astraedus.nudge.domain.usecase.ImportOutcome
import com.astraedus.nudge.domain.usecase.ImportPreview

/**
 * Pure text builders for the import dialogs.
 *
 * They live outside the composable so the wording -- especially "we skipped N entries you had in
 * your backup", the one thing the user must not miss -- is JVM-testable rather than only verifiable
 * by eye on a device.
 */

/** Cap on how many per-entry reasons a dialog lists before collapsing the rest into a count. */
private const val MAX_LISTED_REASONS = 3

/**
 * Body text of the "Import backup" confirmation dialog, shown BEFORE anything is written.
 *
 * A file with no history reads EXACTLY as it did before history existed -- a rules-only backup from
 * an older Nudge must not sprout a line about a feature it knows nothing about.
 */
fun buildImportPreviewMessage(preview: ImportPreview): String = buildString {
    val result = preview.result
    append("Import ${result.rules.size} rule(s)")
    if (result.groups.isNotEmpty()) append(" and ${result.groups.size} group(s)")
    append("?\n\nDuplicate rules will be skipped.")
    if (result.history.isNotEmpty()) {
        append("\n\nIncludes ${result.history.size} history event(s) (${preview.newHistoryCount} new).")
    }
    if (result.invalidCount > 0) {
        append("\n\n")
        append(entriesCouldNotBeRead(result.invalidCount))
        append(" and will be left out:")
        appendReasons(result.invalidReasons)
    }
    if (result.settings != null) {
        // Warned BEFORE anything is written, because unlike rules and history this REPLACES what
        // is already on the device: custom block messages, the content filter, Strict Mode.
        append("\n\nAlso restores app settings, replacing this device's.")
    }
    if (result.invalidHistoryCount > 0) {
        append("\n\n")
        append(historyEntriesCouldNotBeRead(result.invalidHistoryCount))
        append(" and will be left out:")
        appendReasons(result.invalidHistoryReasons)
    }
    if (result.invalidSettingsCount > 0) {
        append("\n\n")
        append(settingsCouldNotBeRead(result.invalidSettingsCount))
        append(" and will be left out:")
        appendReasons(result.invalidSettingsReasons)
    }
}

/** Body text of the "Import Complete" dialog. */
fun buildImportOutcomeMessage(outcome: ImportOutcome): String = buildString {
    append("Imported: ${outcome.importedCount} rule(s)")
    if (outcome.groupsCreated > 0) append("\nGroups created: ${outcome.groupsCreated}")
    if (outcome.duplicateCount > 0) append("\nSkipped (duplicates): ${outcome.duplicateCount}")
    if (outcome.hasHistory) {
        append("\n\nHistory events restored: ${outcome.historyImportedCount}")
        if (outcome.historyDuplicateCount > 0) {
            append("\nHistory already present: ${outcome.historyDuplicateCount}")
        }
        if (outcome.historyInvalidCount > 0) {
            append("\nHistory could not be read: ${outcome.historyInvalidCount}")
        }
    }
    if (outcome.settingsApplied) append("\n\nApp settings restored")
    if (outcome.settingsInvalidCount > 0) {
        append("\nSettings that could not be read: ${outcome.settingsInvalidCount}")
    }
    if (outcome.invalidCount > 0) {
        append("\n\nSkipped (could not be read): ${outcome.invalidCount}")
        appendReasons(outcome.invalidReasons)
    }
}

/**
 * Whether the import touched history at all. A rules-only file stays silent about it: reporting
 * "History events restored: 0" on a v1 backup is noise that reads like a failure.
 */
private val ImportOutcome.hasHistory: Boolean
    get() = historyImportedCount > 0 || historyDuplicateCount > 0 || historyInvalidCount > 0

private fun StringBuilder.appendReasons(reasons: List<String>) {
    reasons.take(MAX_LISTED_REASONS).forEach { append("\n• $it") }
    val extra = reasons.size - MAX_LISTED_REASONS
    if (extra > 0) append("\n• ...and $extra more")
}

private fun entriesCouldNotBeRead(count: Int): String =
    if (count == 1) "1 entry could not be read" else "$count entries could not be read"

private fun historyEntriesCouldNotBeRead(count: Int): String =
    if (count == 1) "1 history event could not be read"
    else "$count history events could not be read"

private fun settingsCouldNotBeRead(count: Int): String =
    if (count == 1) "1 setting could not be read" else "$count settings could not be read"
