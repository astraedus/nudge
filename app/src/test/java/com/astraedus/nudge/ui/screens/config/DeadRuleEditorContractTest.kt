package com.astraedus.nudge.ui.screens.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-scan guard for "the old rule editor stays deleted, and App config does not grow private
 * copies of the shared rule editor pieces again".
 *
 * `RuleEditorScreen` was a dead duplicate of App config: nothing navigated to it from a live
 * screen, and its private `InfoButton` / `TimeSelector` were copies of App config's. It was deleted
 * (groups spec, PR 0) and the pieces moved to `ui/components/rule/`, where the group editor and the
 * All apps editor reuse them. Reviving the editor, or re-declaring a piece privately in the screen,
 * puts the duplication straight back.
 */
class DeadRuleEditorContractTest {

    private fun mainRoot(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("src/main/java not found from working dir ${File("").absolutePath}")

    private fun mainSources(): List<File> =
        mainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no source file is or declares the rule editor screen`() {
        val offenders = mainSources().filter {
            it.nameWithoutExtension.startsWith("RuleEditor") ||
                it.readText().contains("RuleEditorScreen") ||
                it.readText().contains("RuleEditorViewModel")
        }
        assertEquals(
            "The dead rule editor is back. App config (UnifiedAppConfigScreen) is the one editor " +
                "for a single app's rule.",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }

    @Test
    fun `nav graph has no rule_editor route`() {
        val navGraph = mainSources().single { it.name == "NavGraph.kt" }.readText()
        assertFalse("NavGraph must not declare a rule_editor route", navGraph.contains("rule_editor"))
        assertTrue("App config route must remain", navGraph.contains("Screen.AppConfig.route"))
    }

    @Test
    fun `app config calls the shared rule pieces instead of declaring its own`() {
        val screen = mainSources().single { it.name == "UnifiedAppConfigScreen.kt" }.readText()
        listOf("InfoButton", "TimeSelector", "BLOCKING_MODES").forEach { name ->
            assertFalse(
                "$name belongs in ui/components/rule/, not as a private copy in the screen",
                Regex("""(fun|val)\s+$name\b""").containsMatchIn(screen)
            )
        }
        val shared = File(mainRoot(), "com/astraedus/nudge/ui/components/rule")
        listOf("InfoButton", "TimeSelector", "BlockModeSegmentedRow", "WaitTimeChips", "ScheduleSection")
            .forEach { assertTrue("$it.kt missing from ui/components/rule/", File(shared, "$it.kt").isFile) }
    }
}
