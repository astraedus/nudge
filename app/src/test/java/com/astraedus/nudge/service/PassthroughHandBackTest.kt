package com.astraedus.nudge.service

import com.astraedus.nudge.domain.block.CompletionHandBack
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.sitting.SittingEndCause
import com.astraedus.nudge.domain.sitting.SittingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1, through the real [PassthroughManager] and its real `SittingTracker`: a completed Delay whose
 * overlay finished onto the home screen hands back to the app and keeps the pass (#119), and every
 * other Home still revokes exactly as before.
 */
class PassthroughHandBackTest {

    private val clock = "com.android.deskclock"
    private val home = ForegroundSignal.Home("com.android.launcher3")

    private val handedBack = mutableListOf<String>()
    private val manager = PassthroughManager().apply {
        setHandBackReaction { pkg -> handedBack += pkg; true }
    }

    /** What BlockOverlayActivity does on timer completion, at monotonic time [t]. */
    private fun completeDelay(t: Long, webDomain: String? = null) =
        manager.grant(clock, webDomain = webDomain, completedAtMs = t)

    @Test
    fun `#119 - the reveal landing on Home brings the app back and keeps the pass`() {
        completeDelay(56_863L)
        val event = manager.onForegroundSignal(home, 57_002L)

        assertEquals(listOf(clock), handedBack)
        assertEquals(SittingEvent.Unchanged, event)
        assertTrue("the pass the user waited for survives", manager.isGranted(clock))

        // ...and the app arriving is the same sitting, still let through.
        manager.onForegroundSignal(ForegroundSignal.AppWindow(clock), 57_400L)
        assertTrue(manager.shouldSkipForegroundEvaluation(clock))
    }

    @Test
    fun `#119 counterfactual - the same stream without the completion stamp revokes as WENT_HOME`() {
        manager.grant(clock)
        val event = manager.onForegroundSignal(home, 57_002L)
        assertEquals(SittingEvent.Ended(clock, SittingEndCause.WENT_HOME), event)
        assertFalse(manager.isGranted(clock))
        assertTrue(handedBack.isEmpty())
    }

    @Test
    fun `a hand-back that could not start applies the Home as it always did`() {
        manager.setHandBackReaction { false }
        completeDelay(0L)
        assertEquals(SittingEvent.Ended(clock, SittingEndCause.WENT_HOME), manager.onForegroundSignal(home, 100L))
        assertFalse(manager.isGranted(clock))
    }

    @Test
    fun `no reaction registered (service not connected) is the old behaviour`() {
        manager.setHandBackReaction(null)
        completeDelay(0L)
        manager.onForegroundSignal(home, 100L)
        assertFalse(manager.isGranted(clock))
    }

    @Test
    fun `the reveal into the app then a real Home revokes`() {
        completeDelay(0L)
        manager.onForegroundSignal(ForegroundSignal.AppWindow(clock), 120L)
        manager.onForegroundSignal(home, 600L)
        assertTrue(handedBack.isEmpty())
        assertFalse(manager.isGranted(clock))
    }

    @Test
    fun `a Home after the window is a real trip home`() {
        completeDelay(0L)
        manager.onForegroundSignal(home, CompletionHandBack.WINDOW_MS + 1)
        assertTrue(handedBack.isEmpty())
        assertFalse(manager.isGranted(clock))
    }

    @Test
    fun `a web block never hands back - the browser's launch intent is not the page`() {
        completeDelay(0L, webDomain = "reddit.com")
        manager.onForegroundSignal(home, 100L)
        assertTrue(handedBack.isEmpty())
        assertFalse(manager.isGranted(clock))
    }

    @Test
    fun `a new block launched after the completion owns the next Home`() {
        completeDelay(0L)
        manager.cancelHandBack()
        manager.onForegroundSignal(home, 100L)
        assertTrue(handedBack.isEmpty())
    }

    @Test
    fun `a revoked grant cannot be handed back`() {
        completeDelay(0L)
        manager.clear()
        assertNull(manager.handBackPending)
        manager.onForegroundSignal(home, 100L)
        assertTrue(handedBack.isEmpty())
    }

    @Test
    fun `a later grant for another app re-arms for that app only`() {
        completeDelay(0L)
        manager.grant("org.telegram.messenger", completedAtMs = 500L)
        assertEquals("org.telegram.messenger", manager.handBackPending)
        manager.onForegroundSignal(home, 600L)
        assertEquals(listOf("org.telegram.messenger"), handedBack)
    }

    @Test
    fun `a grant without a completion stamp disarms an older arm`() {
        completeDelay(0L)
        manager.grant(clock)
        assertNull(manager.handBackPending)
    }
}
