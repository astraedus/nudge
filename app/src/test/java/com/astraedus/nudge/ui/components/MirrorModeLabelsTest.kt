package com.astraedus.nudge.ui.components

import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.ui.components.rule.BLOCKING_MODES
import com.astraedus.nudge.ui.components.rule.MIRROR_CAMERA_OFF_NOTE
import com.astraedus.nudge.ui.components.rule.shouldRequestCameraFor
import com.astraedus.nudge.ui.components.rule.showsWaitTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words and editor rules for MIRROR, and the class invariants over every mode, so the next
 * mode added cannot be missing from the picker, unnamed, or stuck without a duration.
 */
class MirrorModeLabelsTest {

    @Test
    fun `MIRROR reads as Mirror with a plain one-line description`() {
        assertEquals("Mirror", blockModeLabel(BlockMode.MIRROR))
        assertEquals("Mirror", blockModeShortLabel(BlockMode.MIRROR))
        assertEquals(
            "Shows your front camera for the wait, before the app opens.",
            blockModeDescription(BlockMode.MIRROR, manyApps = false)
        )
        assertEquals(
            "Shows your front camera for the wait, before they open.",
            blockModeDescription(BlockMode.MIRROR, manyApps = true)
        )
    }

    /** The class: the picker offers every mode that gates an app, and never NONE. */
    @Test
    fun `the picker offers every gating mode exactly once`() {
        assertEquals(BlockMode.entries.filter { it != BlockMode.NONE }.toSet(), BLOCKING_MODES.toSet())
        assertEquals(BLOCKING_MODES.size, BLOCKING_MODES.distinct().size)
        assertFalse(BlockMode.NONE in BLOCKING_MODES)
    }

    @Test
    fun `every mode has a distinct non-blank label and description`() {
        val labels = BlockMode.entries.map { blockModeLabel(it) }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals("labels must not collide", labels.size, labels.distinct().size)
        BlockMode.entries.forEach { mode ->
            listOf(true, false).forEach { many ->
                assertTrue("$mode description", blockModeDescription(mode, manyApps = many).isNotBlank())
            }
        }
    }

    @Test
    fun `the editor shows the wait time for exactly the modes that have one`() {
        BlockMode.entries.forEach { mode -> assertEquals(mode.toString(), mode.usesDuration, showsWaitTime(mode)) }
        assertTrue(showsWaitTime(BlockMode.MIRROR))
    }

    @Test
    fun `picking Mirror asks for the camera only when it is not granted, other modes never do`() {
        assertTrue(shouldRequestCameraFor(BlockMode.MIRROR, granted = false))
        assertFalse(shouldRequestCameraFor(BlockMode.MIRROR, granted = true))
        BlockMode.entries.filter { it != BlockMode.MIRROR }.forEach { mode ->
            assertFalse("$mode must never ask for the camera", shouldRequestCameraFor(mode, granted = false))
        }
    }

    @Test
    fun `the camera-off note says what happens instead, plainly`() {
        assertEquals("Camera is off, so Mirror shows a countdown instead.", MIRROR_CAMERA_OFF_NOTE)
    }
}
