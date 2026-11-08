package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.block.OverlayLifecycle.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3 (pure lifecycle decision): what the block screen does when the block it shows ENDS by the clock
 * (#170), and when "Use for 2 minutes" is tapped after that end.
 *
 * The ACTIVITY waits for `endsAt` and then asks [OverlayLifecycle.onBlockEnded]; the refusals are
 * the cases where closing would be the wrong screen closing on the wrong block, and every one is a
 * shape this repo has shipped as a bug before (#15 stale delivery, #26 walk-away pop, #8 a stopped
 * overlay acting).
 */
class OverlayLifecycleBlockEndTest {

    private fun delivered(): OverlayLifecycle = OverlayLifecycle().also { it.onDelivered(pendingOverlayId = 1L) }

    private fun OverlayLifecycle.end(
        token: Int = renderToken,
        atLeastStarted: Boolean = true,
        isFinishing: Boolean = false,
        isDestroyed: Boolean = false
    ) = onBlockEnded(token, atLeastStarted, isFinishing, isDestroyed)

    @Test
    fun `a block that ends while its screen is up marks the overlay inactive, then finishes`() {
        // Order is load-bearing: the service must stop believing a block is up BEFORE the finish
        // pops the app forward, or that arrival's evaluation is gated off as "overlay active".
        assertEquals(listOf(Effect.MarkOverlayInactive, Effect.Finish), delivered().end())
    }

    @Test
    fun `the end grants no pass, so a rule still in force is met again on the pop`() {
        val effects = delivered().end()
        assertTrue(Effect.GrantPassthrough !in effects)
        assertTrue(Effect.UseEmergencyPass !in effects)
    }

    @Test
    fun `an end timed for an earlier delivery does nothing to the block shown now`() {
        val lifecycle = delivered()
        val oldToken = lifecycle.renderToken
        lifecycle.onDelivered(pendingOverlayId = 2L) // #15: a new block arrived through onNewIntent
        assertEquals(emptyList<Effect>(), lifecycle.end(token = oldToken))
        assertEquals(listOf(Effect.MarkOverlayInactive, Effect.Finish), lifecycle.end())
    }

    @Test
    fun `after a walk-away the end does not finish again`() {
        val lifecycle = delivered()
        lifecycle.onWalkAwayRequested(attributedPackage = "com.instagram.android", blockMode = "DELAY", walkAwayPackage = "com.instagram.android")
        // A finish here would be #26's pop: the blocked app forward behind a walk-away.
        assertEquals(emptyList<Effect>(), lifecycle.end())
    }

    @Test
    fun `a block handed to its fullscreen replacement is the replacement's to end`() {
        val lifecycle = delivered()
        lifecycle.onRelaunchingFullscreen()
        assertEquals(emptyList<Effect>(), lifecycle.end())
    }

    @Test
    fun `a finishing, destroyed or stopped screen does not act on the end`() {
        assertEquals(emptyList<Effect>(), delivered().end(isFinishing = true))
        assertEquals(emptyList<Effect>(), delivered().end(isDestroyed = true))
        // Invariant #8 has already finished a stopped overlay; the end must not be a second finish.
        assertEquals(emptyList<Effect>(), delivered().end(atLeastStarted = false))
    }

    // -- "Use for 2 minutes" --

    @Test
    fun `the pass tapped while the block is in force is spent, then the screen finishes`() {
        assertEquals(listOf(Effect.UseEmergencyPass, Effect.Finish), delivered().onEmergencyPassTapped(inForce = true))
    }

    @Test
    fun `the pass tapped after the block ended is NOT spent, the screen closes like the end`() {
        val effects = delivered().onEmergencyPassTapped(inForce = false)
        assertEquals(listOf(Effect.MarkOverlayInactive, Effect.Finish), effects)
        assertTrue(Effect.UseEmergencyPass !in effects)
    }

    /** The tap and the screen's own close decide from the same pure helper and the same instant. */
    @Test
    fun `the tap decision follows BlockInForce at the block's end instant`() {
        val endsAt = 1_000_000L
        assertEquals(
            listOf(Effect.UseEmergencyPass, Effect.Finish),
            delivered().onEmergencyPassTapped(BlockInForce.isInForce(endsAt, endsAt - 1))
        )
        assertEquals(
            listOf(Effect.MarkOverlayInactive, Effect.Finish),
            delivered().onEmergencyPassTapped(BlockInForce.isInForce(endsAt, endsAt))
        )
        // No end on any clock: always in force, the pass behaves as it always did.
        assertEquals(
            listOf(Effect.UseEmergencyPass, Effect.Finish),
            delivered().onEmergencyPassTapped(BlockInForce.isInForce(null, Long.MAX_VALUE))
        )
    }
}
