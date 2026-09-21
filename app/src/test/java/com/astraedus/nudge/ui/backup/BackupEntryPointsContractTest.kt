package com.astraedus.nudge.ui.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where backup can be reached from, and the shape every entry point has to have.
 *
 * Backup used to live ONLY in the Active Rules overflow menu, and Active Rules is itself reachable
 * only by tapping a stat card on the dashboard -- a QA agent searching exhaustively for it
 * concluded the feature did not exist (docs/BACKLOG.md, v1.12.0 QA). Data portability is what
 * people reach for before wiping or switching a phone, so it now also sits in Settings, and this
 * pins that rather than trusting it to survive the next screen refactor.
 *
 * Source-level on purpose, in the same spirit as the single-writer guard in [BackupImportGateTest]:
 * "a screen stopped offering this" and "a screen opened a picker but rendered no dialogs" are not
 * values a unit test can observe.
 */
class BackupEntryPointsContractTest {

    private val mainSources = listOf(File("src/main/java"), File("app/src/main/java"))
        .firstOrNull { it.exists() }
        ?: error("main sources not found from working dir ${File("").absolutePath}")

    private fun source(path: String): String = File(mainSources, path).readText()

    private val settingsScreen = "com/astraedus/nudge/ui/screens/settings/SettingsScreen.kt"
    private val activeRulesScreen = "com/astraedus/nudge/ui/screens/rules/ActiveRulesScreen.kt"

    @Test
    fun `settings offers saving, sharing and restoring a backup`() {
        val settings = source(settingsScreen)

        listOf("backup.saveBackup()", "backup.shareBackup()", "backup.importBackup()").forEach {
            assertTrue("Settings must still offer $it", settings.contains(it))
        }
    }

    @Test
    fun `the active rules menu offers the same three`() {
        val activeRules = source(activeRulesScreen)

        listOf("backup.saveBackup()", "backup.shareBackup()", "backup.importBackup()").forEach {
            assertTrue("the Active Rules menu must still offer $it", activeRules.contains(it))
        }
    }

    /**
     * Discovered, not listed: any screen that wires the backup actions must also render the
     * dialogs. Opening a file picker and then showing the user nothing -- no preview, no result, no
     * Strict Mode challenge on a weakening import -- is the failure a per-screen checklist misses.
     */
    @Test
    fun `every screen that opens a backup picker also renders the backup dialogs`() {
        val entryPoints = mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("rememberBackupActions(") }
            .filterNot { it.name == "BackupActions.kt" } // where it is declared
            .toList()

        assertTrue("backup must be reachable from at least two screens", entryPoints.size >= 2)
        entryPoints.forEach { file ->
            assertTrue(
                "${file.name} opens backup pickers but renders no backup dialogs",
                file.readText().contains("BackupDialogs(")
            )
        }
    }

    /**
     * One owner of the backup use cases. A screen that reached for `ImportRulesUseCase` itself
     * would be a second import path with its own idea of the Strict Mode gate, and every
     * behavioural test here would still pass.
     */
    @Test
    fun `only the backup ViewModel touches the export and import use cases`() {
        val owners = mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.parentFile?.name == "usecase" } // the use cases themselves
            .filter {
                val text = it.readText()
                text.contains("ExportRulesUseCase") || text.contains("ImportRulesUseCase")
            }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(listOf("BackupViewModel.kt"), owners)
    }
}
