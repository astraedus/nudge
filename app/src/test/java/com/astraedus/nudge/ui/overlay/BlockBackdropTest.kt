package com.astraedus.nudge.ui.overlay

import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Show the camera on block screens" as pure decisions (layer L3): which screens try the camera,
 * and when the camera is actually drawn. The rule under test is the same as MIRROR's: the camera is
 * a picture, never a gate. Every path that is not a working camera is the screen's own PLAIN
 * background, so the setting can only ever change what is drawn behind the screen.
 */
class BlockBackdropTest {

    private val bools = listOf(true, false)

    @Test
    fun `the camera is drawn only with the setting on, a screen that wants it, and a working camera`() {
        for (screen in OverlayScreen.entries) for (setting in bools) for (granted in bools) for (front in bools)
            for (failed in bools) {
                val expected = if (setting && screen != OverlayScreen.MIRROR && granted && front && !failed) {
                    Backdrop.CAMERA
                } else {
                    Backdrop.PLAIN
                }
                assertEquals(
                    "screen=$screen setting=$setting granted=$granted front=$front failed=$failed",
                    expected,
                    BlockBackdrop.backdropFor(
                        settingOn = setting,
                        screen = screen,
                        permissionGranted = granted,
                        hasFrontCamera = front,
                        cameraFailed = failed
                    )
                )
            }
    }

    @Test
    fun `with the setting off every screen is plain, whatever the camera could do`() {
        for (screen in OverlayScreen.entries) {
            assertFalse("$screen", BlockBackdrop.wantsCamera(settingOn = false, screen = screen))
            assertEquals(
                "$screen",
                Backdrop.PLAIN,
                BlockBackdrop.backdropFor(false, screen, permissionGranted = true, hasFrontCamera = true, cameraFailed = false)
            )
        }
    }

    /**
     * The screens the brief names (hard block, daily limit reached and schedule are HARD_BLOCK or
     * the rule's own mode; the kicked-out re-entry is the auto-kick cooldown, a DELAY; Nuke) all
     * want it, and so do HOLD and BREATHING. MIRROR alone does not: its body IS the camera already.
     */
    @Test
    fun `every screen wants the camera with the setting on, except Mirror`() {
        val wanting = OverlayScreen.entries.filter { BlockBackdrop.wantsCamera(settingOn = true, screen = it) }
        assertEquals(OverlayScreen.entries - OverlayScreen.MIRROR, wanting)
    }

    @Test
    fun `no permission, no front camera or a failure each give the plain screen on their own`() {
        for (screen in OverlayScreen.entries - OverlayScreen.MIRROR) {
            assertEquals(Backdrop.CAMERA, BlockBackdrop.backdropFor(true, screen, true, true, false))
            assertEquals(Backdrop.PLAIN, BlockBackdrop.backdropFor(true, screen, false, true, false))
            assertEquals(Backdrop.PLAIN, BlockBackdrop.backdropFor(true, screen, true, false, false))
            assertEquals(Backdrop.PLAIN, BlockBackdrop.backdropFor(true, screen, true, true, true))
        }
    }

    /** Derived from the enum, so a new BlockMode fails here until it is given a screen. */
    @Test
    fun `every block mode maps to the screen the activity renders, and Nuke only splits a hard block`() {
        for (mode in BlockMode.entries) {
            val plain = OverlayScreen.of(mode, nuke = false)
            val nuked = OverlayScreen.of(mode, nuke = true)
            when (mode) {
                BlockMode.NONE -> {
                    assertNull(plain)
                    assertNull(nuked)
                }
                BlockMode.HARD_BLOCK -> {
                    assertEquals(OverlayScreen.HARD_BLOCK, plain)
                    assertEquals(OverlayScreen.NUKE, nuked)
                }
                else -> {
                    assertEquals("$mode", mode.name, plain?.name)
                    assertEquals("nuke must not change $mode", plain, nuked)
                }
            }
        }
    }

    @Test
    fun `every screen is reachable from some block mode`() {
        val reached = BlockMode.entries.flatMap { listOf(OverlayScreen.of(it, false), OverlayScreen.of(it, true)) }
            .filterNotNull().toSet()
        assertTrue(reached.containsAll(OverlayScreen.entries))
    }
}
