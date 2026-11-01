package com.astraedus.nudge.ui.components

import com.astraedus.nudge.ui.components.rule.BLOCKING_MODES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The compact four-segment mode picker (group editor, app default behaviour) draws each label in a
 * quarter of the screen on one line. A two-word label was clipped and drawn off-centre ("Hard Block"
 * read as "Hard", shifted left: device QA, groups PR 2). The invariant is over the whole class: every
 * mode the picker offers gets a one-word label.
 */
class BlockModeShortLabelTest {

    @Test
    fun `every mode in the picker has a one-word short label`() {
        for (mode in BLOCKING_MODES) {
            val label = blockModeShortLabel(mode)
            assertFalse("$mode -> \"$label\" has a space", label.contains(' '))
        }
    }

    @Test
    fun `short labels keep the full label's first word, so the two never disagree`() {
        for (mode in BLOCKING_MODES) {
            assertEquals(blockModeLabel(mode).substringBefore(' '), blockModeShortLabel(mode))
        }
    }
}
