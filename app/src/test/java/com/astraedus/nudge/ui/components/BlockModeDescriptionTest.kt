package com.astraedus.nudge.ui.components

import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.components.rule.BLOCKING_MODES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L1 (pure unit) + a source scan. The sentence under the mode picker said "Completely blocks the
 * app." on the group and All apps editors, which cover many apps (screenshot review, PR 4). The
 * invariant is over every mode: a picker for many apps never says "the app".
 */
class BlockModeDescriptionTest {

    @Test
    fun `hard block reads plural for many apps and singular for one`() {
        assertEquals(
            "Completely blocks these apps. You can only go back to the home screen.",
            blockModeDescription(BlockMode.HARD_BLOCK, manyApps = true)
        )
        assertEquals(
            "Completely blocks the app. You can only go back to the home screen.",
            blockModeDescription(BlockMode.HARD_BLOCK, manyApps = false)
        )
    }

    @Test
    fun `no mode's sentence for many apps names a single app`() {
        for (mode in BLOCKING_MODES) {
            val text = blockModeDescription(mode, manyApps = true)
            assertFalse("$mode -> \"$text\"", text.contains("the app", ignoreCase = true))
        }
    }

    @Test
    fun `the group and All apps editors ask for the many-apps sentence`() {
        val root = File("src/main/java/com/astraedus/nudge/ui/screens")
        for (path in listOf("groups/GroupEditorScreen.kt", "allapps/AllAppsEditorScreen.kt")) {
            val source = File(root, path).readText()
            assertTrue(path, source.contains("blockModeDescription(form.mode, manyApps = true)"))
        }
    }
}
