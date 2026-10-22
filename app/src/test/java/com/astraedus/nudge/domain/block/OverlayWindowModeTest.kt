package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.block.OverlayWindowMode.Decision
import com.astraedus.nudge.domain.block.OverlayWindowMode.RELAUNCH_SETTLE_MS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #71: the block screen dragged into a floating window (Huawei, Android 10) left the blocked
 * app usable behind it. [OverlayWindowMode.decide] is the whole rule; the activity only feeds it
 * `isInMultiWindowMode`, `isFinishing`, the hand-off flag and the relaunch timestamp.
 */
class OverlayWindowModeTest {

    private fun decide(
        inMultiWindow: Boolean,
        isFinishing: Boolean = false,
        relaunchInFlight: Boolean = false,
        msSinceOwnRelaunch: Long? = null
    ) = OverlayWindowMode.decide(inMultiWindow, isFinishing, relaunchInFlight, msSinceOwnRelaunch)

    @Test
    fun `a fullscreen block screen is left alone`() {
        assertEquals(Decision.STAY, decide(inMultiWindow = false))
        assertEquals(Decision.STAY, decide(inMultiWindow = false, msSinceOwnRelaunch = 10))
    }

    @Test
    fun `the reported case, a block screen dragged into a floating window, is relaunched fullscreen`() {
        assertEquals(Decision.RELAUNCH_FULLSCREEN, decide(inMultiWindow = true))
    }

    @Test
    fun `a block screen the user already finished with is never relaunched`() {
        assertEquals(Decision.STAY, decide(inMultiWindow = true, isFinishing = true))
    }

    @Test
    fun `a second signal while the relaunch is in flight does not start a second replacement`() {
        // onMultiWindowModeChanged and onResume both report the same windowed state.
        assertEquals(Decision.STAY, decide(inMultiWindow = true, relaunchInFlight = true))
    }

    @Test
    fun `a relaunch that comes up windowed again gives up instead of looping`() {
        assertEquals(Decision.GIVE_UP, decide(inMultiWindow = true, msSinceOwnRelaunch = 0))
        assertEquals(
            Decision.GIVE_UP,
            decide(inMultiWindow = true, msSinceOwnRelaunch = RELAUNCH_SETTLE_MS - 1)
        )
    }

    @Test
    fun `the relaunched screen dragged out again later is relaunched again`() {
        assertEquals(
            Decision.RELAUNCH_FULLSCREEN,
            decide(inMultiWindow = true, msSinceOwnRelaunch = RELAUNCH_SETTLE_MS)
        )
        assertEquals(
            Decision.RELAUNCH_FULLSCREEN,
            decide(inMultiWindow = true, msSinceOwnRelaunch = 60_000)
        )
    }

    @Test
    fun `a clock that went backwards is not read as a fresh relaunch`() {
        // elapsedRealtime never goes backwards, but a negative gap is not evidence of a loop.
        assertEquals(Decision.RELAUNCH_FULLSCREEN, decide(inMultiWindow = true, msSinceOwnRelaunch = -5))
    }

    /**
     * The loop guard simulated over a device that opens EVERY new task windowed: one relaunch, then
     * a give-up, never a third start. Counterfactual: without the settle window the same device
     * relaunches on every resume, forever.
     */
    @Test
    fun `a platform that always opens the block windowed gets one relaunch, not a loop`() {
        fun starts(rule: (msSinceOwnRelaunch: Long?) -> Decision): Int {
            var starts = 0
            var msSince: Long? = null
            repeat(10) {
                if (rule(msSince) != Decision.RELAUNCH_FULLSCREEN) return starts
                starts++
                msSince = 50 // the replacement resumes ~50ms later, windowed again
            }
            return starts
        }
        assertEquals(1, starts { decide(inMultiWindow = true, msSinceOwnRelaunch = it) })
        assertEquals(
            "counterfactual: a rule without the settle window loops",
            10,
            starts { Decision.RELAUNCH_FULLSCREEN }
        )
    }
}
