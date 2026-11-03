package com.astraedus.nudge.ui.components.rule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5, source-level: the three editors wire the first-unlock row the same way (spec 3.5/5.6).
 *
 * The day-start write and the "Starts at your next first unlock." line are three near-identical
 * snippets in three ViewModels, because the row appears in three editors. A behavioural test for
 * each would need three full ViewModel harnesses to prove one policy; this pins the policy where it
 * actually lives (one use case, one sentence) and proves no editor drifted out of it. The
 * behavioural half is `UnifiedAppConfigViewModelTest`'s day-start cases plus the use case's own
 * tests.
 */
class FirstUnlockEditorWiringContractTest {

    private val viewModels = listOf(
        "ui/screens/config/UnifiedAppConfigViewModel.kt",
        "ui/screens/groups/GroupEditorViewModel.kt",
        "ui/screens/allapps/AllAppsEditorViewModel.kt"
    )

    private fun source(relative: String): String =
        listOf(File("app/src/main/java/com/astraedus/nudge/$relative"),
            File("src/main/java/com/astraedus/nudge/$relative"))
            .first { it.exists() }
            .readText()

    @Test
    fun `every editor writes the day start through the gated use case`() {
        viewModels.forEach { path ->
            val text = source(path)
            assertTrue(
                "$path must ask the use case before writing (spec 5.6)",
                text.contains("setDayStartMinuteUseCase.requiresUnlock(minute)")
            )
            assertTrue(
                "$path must write through the use case, never the preferences directly",
                text.contains("setDayStartMinuteUseCase.execute(minute)")
            )
            assertTrue(
                "$path must challenge a weakening change",
                text.contains("strictModeGate.run(prompt = \"Change when your day starts\"")
            )
            assertTrue(
                "$path must not call setDayStartMinute on the preferences itself",
                !text.contains("nudgePreferences.setDayStartMinute(")
            )
        }
    }

    @Test
    fun `every editor reads the two live first-unlock facts`() {
        viewModels.forEach { path ->
            val text = source(path)
            assertTrue(
                "$path must show the shared day start (spec 3.5)",
                text.contains("nudgePreferences.dayStartMinute.collect")
            )
            assertTrue(
                "$path must recompute the start line when the day's unlock arrives",
                text.contains("nudgePreferences.firstUnlockAt.collect")
            )
            assertTrue(
                "$path must use the one pure arming rule",
                text.contains("RuleArming.startsAtNextUnlock(")
            )
        }
    }
}
