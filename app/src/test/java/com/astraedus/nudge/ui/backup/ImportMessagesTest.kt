package com.astraedus.nudge.ui.backup

import com.astraedus.nudge.data.export.ExportedGroup
import com.astraedus.nudge.data.export.ExportedHistoryEvent
import com.astraedus.nudge.data.export.ExportedRule
import com.astraedus.nudge.data.export.ExportedSettings
import com.astraedus.nudge.data.export.ImportResult
import com.astraedus.nudge.domain.usecase.ImportOutcome
import com.astraedus.nudge.domain.usecase.ImportPreview
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-facing half of [issue #20](https://github.com/astraedus/nudge/issues/20). Skipping a bad
 * rule instead of discarding the backup is only safe if the user is TOLD -- a silent skip is quiet
 * data loss with extra steps. These pin the wording so a refactor cannot drop the disclosure.
 *
 * Extended for usage history: history has its own lines, its own counts, and -- for a rules-only
 * backup -- no lines at all.
 */
class ImportMessagesTest {

    private fun rule(pkg: String) = ExportedRule(
        packageName = pkg,
        groupName = null,
        mode = "DELAY",
        delaySeconds = 15,
        dailyLimitMinutes = null,
        enabled = true,
        scheduleDays = null,
        scheduleStartMinute = null,
        scheduleEndMinute = null,
        inAppFeatures = null,
        grayscale = false,
        showCounter = false,
        autoKickAfter = null,
        showTimeRemaining = false,
        autoKickCooldownSeconds = 60
    )

    private fun historyEvent(ts: Long) = ExportedHistoryEvent(
        packageName = "com.app1",
        timestamp = ts,
        wasBlocked = true,
        blockMode = "DELAY",
        userChangedMind = false
    )

    private fun preview(result: ImportResult, newHistoryCount: Int = 0) =
        ImportPreview(result, newHistoryCount)

    // --- Preview ---------------------------------------------------------------------------

    @Test
    fun `preview warns about entries that will be left out before anything is written`() {
        val message = buildImportPreviewMessage(
            preview(
                ImportResult(
                    rules = listOf(rule("com.app1")),
                    groups = emptyList(),
                    version = 1,
                    invalidCount = 2,
                    invalidReasons = listOf(
                        "Rule 2: Unknown block mode: TELEPORT",
                        "Rule 5: No value for mode"
                    )
                )
            )
        )

        assertTrue(message.contains("Import 1 rule(s)"))
        assertTrue(message.contains("2 entries could not be read"))
        assertTrue(message.contains("TELEPORT"))
    }

    @Test
    fun `preview says nothing about invalid entries when there are none`() {
        val message = buildImportPreviewMessage(
            preview(ImportResult(rules = listOf(rule("com.app1")), groups = emptyList(), version = 1))
        )

        assertFalse(message.contains("could not be read"))
        assertTrue(message.contains("Duplicate rules will be skipped."))
    }

    /**
     * Regression: the group count used to be concatenated with a dangling `else` branch, so a file
     * WITH groups silently lost the "?" and the duplicate warning off the end of the sentence.
     */
    @Test
    fun `preview keeps the full sentence when the file contains groups`() {
        val message = buildImportPreviewMessage(
            preview(
                ImportResult(
                    rules = listOf(rule("com.app1")),
                    groups = listOf(ExportedGroup("Social", listOf("com.instagram.android"))),
                    version = 1
                )
            )
        )

        assertTrue(message.contains("Import 1 rule(s) and 1 group(s)?"))
        assertTrue(message.contains("Duplicate rules will be skipped."))
    }

    // --- Preview: history ------------------------------------------------------------------

    @Test
    fun `preview reports both how much history the file holds and how much of it is new`() {
        val message = buildImportPreviewMessage(
            preview(
                ImportResult(
                    rules = listOf(rule("com.app1")),
                    groups = emptyList(),
                    version = 1,
                    history = (1..40L).map(::historyEvent)
                ),
                newHistoryCount = 12
            )
        )

        assertTrue(message.contains("40 history event(s)"))
        assertTrue(message.contains("(12 new)"))
    }

    /**
     * A backup from before history existed must read EXACTLY as it used to -- no line about a
     * feature the file knows nothing about, and certainly not "0 history events".
     */
    @Test
    fun `preview of a rules-only file never mentions history`() {
        val message = buildImportPreviewMessage(
            preview(ImportResult(rules = listOf(rule("com.app1")), groups = emptyList(), version = 1))
        )

        assertFalse(message.lowercase().contains("history"))
    }

    @Test
    fun `preview reports unreadable history separately from unreadable rules`() {
        val message = buildImportPreviewMessage(
            preview(
                ImportResult(
                    rules = listOf(rule("com.app1")),
                    groups = emptyList(),
                    version = 1,
                    invalidCount = 1,
                    invalidReasons = listOf("Rule 2: Unknown block mode: TELEPORT"),
                    history = listOf(historyEvent(1)),
                    invalidHistoryCount = 4,
                    invalidHistoryReasons = listOf(
                        "History event 3: \"timestamp\" is missing or is not a number"
                    )
                ),
                newHistoryCount = 1
            )
        )

        assertTrue(message.contains("1 entry could not be read"))
        assertTrue(message.contains("4 history events could not be read"))
        assertTrue(message.contains("timestamp"))
    }

    // --- Outcome ---------------------------------------------------------------------------

    @Test
    fun `outcome reports unreadable entries separately from duplicates`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(
                importedCount = 4,
                duplicateCount = 2,
                groupsCreated = 1,
                invalidCount = 3,
                invalidReasons = listOf("Rule 2: bad", "Rule 7: bad", "Group 1: bad")
            )
        )

        assertTrue(message.contains("Imported: 4 rule(s)"))
        assertTrue(message.contains("Groups created: 1"))
        assertTrue(message.contains("Skipped (duplicates): 2"))
        assertTrue(message.contains("Skipped (could not be read): 3"))
    }

    @Test
    fun `outcome stays quiet about counts that are zero`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(importedCount = 3, duplicateCount = 0, groupsCreated = 0)
        )

        assertTrue(message.contains("Imported: 3 rule(s)"))
        assertFalse(message.contains("duplicates"))
        assertFalse(message.contains("could not be read"))
        assertFalse(message.contains("Groups created"))
        assertFalse("a rules-only import must not mention history", message.contains("History"))
    }

    @Test
    fun `a long list of reasons is collapsed instead of filling the dialog`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(
                importedCount = 1,
                duplicateCount = 0,
                groupsCreated = 0,
                invalidCount = 9,
                invalidReasons = (1..9).map { "Rule $it: bad" }
            )
        )

        assertTrue(message.contains("Skipped (could not be read): 9"))
        assertTrue(message.contains("...and 6 more"))
        assertFalse("only the first few reasons are listed", message.contains("Rule 9: bad"))
    }

    // --- Outcome: history ------------------------------------------------------------------

    @Test
    fun `outcome reports restored, duplicate and unreadable history as three distinct numbers`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(
                importedCount = 2,
                duplicateCount = 0,
                groupsCreated = 0,
                historyImportedCount = 118,
                historyDuplicateCount = 40,
                historyInvalidCount = 3
            )
        )

        assertTrue(message.contains("History events restored: 118"))
        assertTrue(message.contains("History already present: 40"))
        assertTrue(message.contains("History could not be read: 3"))
    }

    /**
     * Re-importing the same backup is a legitimate thing to do, and the dialog must say so plainly
     * rather than reading like a failure: 0 restored, all of it already present.
     */
    @Test
    fun `an idempotent re-import still reports the history it recognised`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(
                importedCount = 0,
                duplicateCount = 3,
                groupsCreated = 0,
                historyImportedCount = 0,
                historyDuplicateCount = 158
            )
        )

        assertTrue(message.contains("History events restored: 0"))
        assertTrue(message.contains("History already present: 158"))
        assertFalse(message.contains("could not be read"))
    }

    // --- Settings ----------------------------------------------------------------------------

    /**
     * Settings are the one payload that REPLACES what is already on the device -- rules and history
     * only ever add. So the preview has to say so before anything is written, not after.
     */
    @Test
    fun `preview warns that settings will replace this device's`() {
        val message = buildImportPreviewMessage(
            preview(
                ImportResult(
                    rules = listOf(rule("com.app1")),
                    groups = emptyList(),
                    version = 1,
                    settings = ExportedSettings(strictModeEnabled = true)
                )
            )
        )

        assertTrue(message.contains("app settings"))
        assertTrue(message.contains("replacing this device's"))
    }

    /** A backup from before settings existed must read EXACTLY as it always did. */
    @Test
    fun `preview of a file without settings never mentions them`() {
        val message = buildImportPreviewMessage(
            preview(ImportResult(rules = listOf(rule("com.app1")), groups = emptyList(), version = 1))
        )

        assertFalse(message.lowercase().contains("setting"))
    }

    @Test
    fun `preview reports unreadable settings separately from rules and history`() {
        val message = buildImportPreviewMessage(
            preview(
                ImportResult(
                    rules = listOf(rule("com.app1")),
                    groups = emptyList(),
                    version = 1,
                    invalidCount = 1,
                    invalidReasons = listOf("Rule 2: Unknown block mode: TELEPORT"),
                    invalidHistoryCount = 2,
                    invalidHistoryReasons = listOf("History event 3: \"timestamp\" is not a number"),
                    settings = ExportedSettings(strictModeEnabled = true),
                    invalidSettingsCount = 1,
                    invalidSettingsReasons = listOf("Setting \"contentFilterMode\": unknown block mode: TELEPORT")
                )
            )
        )

        assertTrue(message.contains("1 entry could not be read"))
        assertTrue(message.contains("2 history events could not be read"))
        assertTrue(message.contains("1 setting could not be read"))
        assertTrue(message.contains("contentFilterMode"))
    }

    @Test
    fun `outcome confirms settings were restored`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(
                importedCount = 2,
                duplicateCount = 0,
                groupsCreated = 0,
                settingsApplied = true,
                settingsInvalidCount = 2
            )
        )

        assertTrue(message.contains("App settings restored"))
        assertTrue(message.contains("Settings that could not be read: 2"))
    }

    @Test
    fun `outcome of a file without settings never mentions them`() {
        val message = buildImportOutcomeMessage(
            ImportOutcome(importedCount = 3, duplicateCount = 0, groupsCreated = 0)
        )

        assertFalse(message.lowercase().contains("setting"))
    }
}
