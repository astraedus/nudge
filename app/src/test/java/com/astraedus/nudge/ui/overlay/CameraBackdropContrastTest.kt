package com.astraedus.nudge.ui.overlay

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Legibility over the camera, checked as numbers (layer L1). The picture under a block screen is
 * unknown and moving, so every colour drawn straight onto the backdrop must pass WCAG AA (4.5:1)
 * against the scrim over the brightest frame (pure white) AND the darkest (pure black).
 */
class CameraBackdropContrastTest {

    private val scheme = cameraColorScheme(darkColorScheme())

    /** What text actually sits on: the scrim composited over a frame of [frame]. */
    private fun backdropOver(frame: Color): Color = CameraBackdropPalette.SCRIM.compositeOver(frame)

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Every role the block screens draw directly on their background (grep of ui/overlay + redirect). */
    private val roles = mapOf(
        "onBackground" to scheme.onBackground,
        "onSurface" to scheme.onSurface,
        "onSurfaceVariant" to scheme.onSurfaceVariant,
        "outline" to scheme.outline,
        "primary" to scheme.primary,
        "error" to scheme.error,
        "tertiary" to scheme.tertiary
    )

    @Test
    fun `every text role passes AA over the scrim on a white frame and a black one`() {
        for (frame in listOf(Color.White, Color.Black, Color(0xFF808080))) {
            val bg = backdropOver(frame)
            roles.forEach { (name, colour) ->
                val ratio = contrast(colour, bg)
                assertTrue("$name over the scrim on $frame is $ratio:1, needs 4.5", ratio >= 4.5)
            }
        }
    }

    /** A filled button: its label on its own fill, which is the camera palette's primary. */
    @Test
    fun `a filled button's label passes AA on its fill`() {
        val ratio = contrast(scheme.onPrimary, scheme.primary)
        assertTrue("onPrimary on primary is $ratio:1", ratio >= 4.5)
    }

    /** The worst case is the white frame; the scrim must darken it enough for white text at AAA. */
    @Test
    fun `the scrim alone takes a white frame below the AAA line for white text`() {
        val ratio = contrast(Color.White, backdropOver(Color.White))
        assertTrue("white text on a scrimmed white frame is $ratio:1, needs 7", ratio >= 7.0)
    }
}
