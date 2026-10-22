package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.block.OverlayLifecycle.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #71: a block screen found in multi-window hands its block to a fullscreen replacement.
 *
 * The replacement adopts the SAME pending overlay (it is started by the activity, not through the
 * service's launch gate), and the old instance is destroyed AFTER the replacement resumes. So the
 * old instance must go quietly: a `MarkOverlayInactive` or `ReportOverlayDismissed` from it would
 * strip the live, fullscreen block of its protection, the issue #36 shape.
 */
class OverlayLifecycleFullscreenRelaunchTest {

    private val overlayId = 42L

    private fun delivered(): OverlayLifecycle =
        OverlayLifecycle().apply { onDelivered(overlayId) }

    @Test
    fun `relaunching emits exactly one RelaunchFullscreen, however many signals report it`() {
        val lifecycle = delivered()
        assertEquals(listOf(Effect.RelaunchFullscreen), lifecycle.onRelaunchingFullscreen())
        assertTrue(lifecycle.handedOff)
        assertEquals(emptyList<Effect>(), lifecycle.onRelaunchingFullscreen())
    }

    @Test
    fun `a handed-off instance stops without finishing or marking the overlay inactive`() {
        val lifecycle = delivered()
        lifecycle.onRelaunchingFullscreen()
        assertEquals(
            emptyList<Effect>(),
            lifecycle.onStopped(isFinishing = true, isChangingConfigurations = false)
        )
        assertEquals(
            emptyList<Effect>(),
            lifecycle.onStopped(isFinishing = false, isChangingConfigurations = false)
        )
    }

    @Test
    fun `a handed-off instance is destroyed without dismissing the replacement's overlay`() {
        val lifecycle = delivered()
        lifecycle.onRelaunchingFullscreen()
        val effects = lifecycle.onDestroyed()
        assertFalse(effects.contains(Effect.MarkOverlayInactive))
        assertFalse(effects.any { it is Effect.ReportOverlayDismissed })
    }

    /** Counterfactual: the same destroy without the hand-off is the ordinary dismissal. */
    @Test
    fun `without the hand-off the destroy still dismisses the overlay it adopted`() {
        val effects = delivered().onDestroyed()
        assertTrue(effects.contains(Effect.MarkOverlayInactive))
        assertTrue(effects.contains(Effect.ReportOverlayDismissed(overlayId)))
    }

    @Test
    fun `a failed relaunch makes this instance the block again`() {
        val lifecycle = delivered()
        lifecycle.onRelaunchingFullscreen()
        lifecycle.onRelaunchFailed()
        assertFalse(lifecycle.handedOff)
        val effects = lifecycle.onDestroyed()
        assertTrue(effects.contains(Effect.MarkOverlayInactive))
        assertTrue(effects.contains(Effect.ReportOverlayDismissed(overlayId)))
    }
}
