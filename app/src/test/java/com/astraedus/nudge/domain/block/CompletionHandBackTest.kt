package com.astraedus.nudge.domain.block

import com.astraedus.nudge.NudgeIdentity
import com.astraedus.nudge.domain.block.CompletionHandBack.Companion.WINDOW_MS
import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L1: which signal after a completed Delay means "the reveal landed on Home" (#119). */
class CompletionHandBackTest {

    private val clock = "com.android.deskclock"
    private val launcher = "com.android.launcher3"
    private val home = ForegroundSignal.Home(launcher)

    private fun armedAt(t: Long) = CompletionHandBack().apply { arm(clock, t) }

    @Test
    fun `#119 timing - Home 139ms after the grant is the reveal, hand back`() {
        // P119-A2: overlay finish() at 56.863, launcher classified as Home at 57.002.
        val handBack = armedAt(56_863L)
        assertEquals(clock, handBack.onSignal(home, 57_002L))
    }

    @Test
    fun `single-shot - a second Home is the user's own`() {
        val handBack = armedAt(0L)
        assertEquals(clock, handBack.onSignal(home, 100L))
        assertNull(handBack.onSignal(home, 200L))
    }

    @Test
    fun `the app's own window first means the reveal worked, and disarms`() {
        val handBack = armedAt(0L)
        assertNull(handBack.onSignal(ForegroundSignal.AppWindow(clock), 50L))
        assertNull("a later Home is a real trip home", handBack.onSignal(home, 100L))
    }

    @Test
    fun `another app's window first is the user going somewhere else, and disarms`() {
        val handBack = armedAt(0L)
        assertNull(handBack.onSignal(ForegroundSignal.AppWindow("org.telegram.messenger"), 50L))
        assertNull(handBack.onSignal(home, 100L))
    }

    @Test
    fun `Home after the window is a real departure`() {
        assertNull(armedAt(0L).onSignal(home, WINDOW_MS + 1))
        assertEquals(clock, armedAt(0L).onSignal(home, WINDOW_MS))
    }

    @Test
    fun `signals that say nothing about the reveal leave it armed`() {
        val handBack = armedAt(0L)
        val noise = listOf(
            ForegroundSignal.OwnUi(NudgeIdentity.APPLICATION_ID),
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.Transient("com.google.android.inputmethod.latin"),
            ForegroundSignal.AwarenessOverlay(NudgeIdentity.APPLICATION_ID),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground(clock)
        )
        noise.forEachIndexed { i, signal -> assertNull(signal.toString(), handBack.onSignal(signal, i.toLong())) }
        assertEquals(clock, handBack.onSignal(home, noise.size.toLong()))
    }

    @Test
    fun `noise past the window lets the arm expire`() {
        val handBack = armedAt(0L)
        assertNull(handBack.onSignal(ForegroundSignal.OwnUi(NudgeIdentity.APPLICATION_ID), WINDOW_MS + 1))
        assertNull(handBack.armedPackage)
    }

    @Test
    fun `disarm and an unarmed hand-back do nothing`() {
        assertNull(CompletionHandBack().onSignal(home, 0L))
        val handBack = armedAt(0L)
        handBack.disarm()
        assertNull(handBack.onSignal(home, 10L))
    }

    @Test
    fun `a Home from before the grant is never the reveal`() {
        // Monotonic clocks do not run backwards, but a signal stamped before the arm must not count.
        assertNull(armedAt(1_000L).onSignal(home, 999L))
    }
}
