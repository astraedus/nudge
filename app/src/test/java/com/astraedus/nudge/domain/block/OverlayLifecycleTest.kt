package com.astraedus.nudge.domain.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [OverlayLifecycle] driven directly, with no `BlockLaunchGuard` behind it — the pure
 * state machine in isolation. `OverlayLifecycleGuardTest` (a sibling file) owns the version of
 * these scenarios driven against the real guard; nothing here duplicates that.
 *
 * The effect lists returned are asserted EXACTLY, not with "contains", wherever this class's own
 * KDoc says the order is load-bearing — an out-of-order list here is one of the three shipped
 * lifecycle-ordering bugs (#8, #15, #26) happening again, silently, under a green suite.
 */
class OverlayLifecycleTest {

    private val attributedPackage = "com.instagram.android"
    private val walkAwayPackage = "com.android.chrome"
    private val blockMode = "DELAY"

    // ── onDelivered ──

    @Test
    fun `a delivery cancels any fail-safe left by the previous delivery`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onDelivered(pendingOverlayId = 1L)

        assertEquals(listOf(OverlayLifecycle.Effect.CancelPendingFailSafe), effects)
    }

    @Test
    fun `a delivery adopts the guard's pending overlay id as its own, and the next delivery adopts the next id`() {
        val lifecycle = OverlayLifecycle()

        lifecycle.onDelivered(pendingOverlayId = 5L)
        assertEquals(5L, lifecycle.overlayId)

        lifecycle.onDelivered(pendingOverlayId = 9L)
        assertEquals(9L, lifecycle.overlayId)
    }

    /**
     * Issue #15's shape: the token is a plain counter, never derived from the block's contents.
     * Two different apps can legitimately share a package/mode/delay triple, and re-delivering the
     * *same* block is still a new attempt that must start from full — so identical inputs on
     * consecutive deliveries must still mint a different token.
     *
     * This proves only that the KEY CHANGES. Whether Compose actually discards remembered state
     * under a changed `key` is a Compose-runtime fact, not something a JVM test on this pure class
     * can observe — that half stays unverified here.
     */
    @Test
    fun `renderToken strictly increases on every delivery, including a re-delivery of an identical block`() {
        val lifecycle = OverlayLifecycle()

        lifecycle.onDelivered(pendingOverlayId = 42L)
        val firstToken = lifecycle.renderToken

        lifecycle.onDelivered(pendingOverlayId = 42L) // identical id: same block, re-delivered
        val secondToken = lifecycle.renderToken

        assertTrue(
            "expected renderToken to increase past $firstToken on re-delivery, was $secondToken",
            secondToken > firstToken
        )
    }

    @Test
    fun `a delivery restores the walk-away budget so the next walk-away is not silently swallowed`() {
        val lifecycle = OverlayLifecycle()

        lifecycle.onDelivered(pendingOverlayId = 1L)
        val firstWalkAway = lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)
        assertFalse("first walk-away of a fresh delivery must not be empty", firstWalkAway.isEmpty())

        lifecycle.onDelivered(pendingOverlayId = 2L)
        val secondWalkAway = lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)

        assertEquals(firstWalkAway.size, secondWalkAway.size)
        assertFalse("walk-away budget must be restored by the new delivery, not still spent", secondWalkAway.isEmpty())
    }

    // ── onWalkAwayRequested ──

    /**
     * Issue #26's fix, and the reason the order is asserted exactly. [Effect.ArmWalkAwayWindow]
     * MUST precede [Effect.GoHome]: the whole point of the window is to be armed before the
     * blocked app's window can resurface, and that resurfacing can happen on the very next frame
     * after `GLOBAL_ACTION_HOME` is dispatched. Arming it after the dispatch is arming it after the
     * event it exists to catch.
     */
    @Test
    fun `walking away records, clears, arms the window, goes home, and schedules a fail-safe, in that order`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)
        val token = lifecycle.renderToken

        val effects = lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)

        assertEquals(
            listOf(
                OverlayLifecycle.Effect.RecordWalkAway(attributedPackage, blockMode),
                OverlayLifecycle.Effect.MarkOverlayInactive,
                OverlayLifecycle.Effect.ArmWalkAwayWindow(walkAwayPackage),
                OverlayLifecycle.Effect.GoHome,
                OverlayLifecycle.Effect.ScheduleFailSafeFinish(token, OverlayLifecycle.WALK_AWAY_FINISH_FAILSAFE_MS)
            ),
            effects
        )
    }

    /**
     * Issue #26's fix used to be a source-level grep for `finish()`; it is a value now. A walk-away
     * never finishes inline — `finish()` on this `singleInstance` activity pops straight back to the
     * blocked app underneath, racing the asynchronous `GLOBAL_ACTION_HOME`. [onStopped] is the only
     * path allowed to finish a walked-away overlay, once the launcher has genuinely stopped it.
     */
    @Test
    fun `a walk-away never finishes inline`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)

        val effects = lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)

        assertFalse(effects.contains(OverlayLifecycle.Effect.Finish))
    }

    /**
     * Turning around is not permission to enter: a walk-away never grants passthrough, so the next
     * attempt at this app gets a fresh, full block.
     */
    @Test
    fun `a walk-away never grants passthrough`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)

        val effects = lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)

        assertFalse(effects.contains(OverlayLifecycle.Effect.GrantPassthrough))
    }

    /**
     * The once-only gate. The "I changed my mind" button and the back gesture both land here, and
     * can race each other or double-fire; one attempt must not log two `RecordWalkAway` rows.
     */
    @Test
    fun `a second walk-away request in the same delivery returns an empty list`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)

        lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)
        val second = lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)

        assertTrue(second.isEmpty())
    }

    @Test
    fun `the scheduled fail-safe carries the current renderToken and the fail-safe delay constant`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)
        val currentToken = lifecycle.renderToken

        val effects = lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)

        assertEquals(
            OverlayLifecycle.Effect.ScheduleFailSafeFinish(currentToken, OverlayLifecycle.WALK_AWAY_FINISH_FAILSAFE_MS),
            effects.last()
        )
    }

    /**
     * A web block is attributed to the rule's app (Instagram) but the user is sitting in the
     * browser, so the row recorded and the window armed must carry two DIFFERENT packages — never
     * the same one, or the armed window would be watching for the wrong app's window to resurface.
     */
    @Test
    fun `the attributed package and the walk-away package are allowed to differ, and never collapse to the same one`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)

        val effects = lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)

        val recorded = effects.filterIsInstance<OverlayLifecycle.Effect.RecordWalkAway>().single()
        val armed = effects.filterIsInstance<OverlayLifecycle.Effect.ArmWalkAwayWindow>().single()

        assertEquals(attributedPackage, recorded.packageName)
        assertEquals(walkAwayPackage, armed.packageName)
        assertTrue(recorded.packageName != armed.packageName)
    }

    // ── onFailSafeFired ──

    /**
     * The fail-safe fires when the dispatched go-home never stopped us. [Effect.ArmWalkAwayWindow]
     * comes before [Effect.Finish], and that is the whole point: this finish deliberately reproduces
     * #26's pop (it pops the blocked app forward), so the window must be RE-ARMED at the moment of
     * the pop, not only at the tap 1200ms earlier — a window armed only then would have whatever was
     * left of the 1500ms suppression window minus the 1200ms already spent waiting.
     */
    @Test
    fun `a fail-safe firing logs it, re-arms the walk-away window, then finishes, in that order`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)
        val token = lifecycle.renderToken

        val effects = lifecycle.onFailSafeFired(
            token = token,
            isFinishing = false,
            isDestroyed = false,
            walkAwayPackage = walkAwayPackage
        )

        assertEquals(
            listOf(
                OverlayLifecycle.Effect.LogFailSafeFired,
                OverlayLifecycle.Effect.ArmWalkAwayWindow(walkAwayPackage),
                OverlayLifecycle.Effect.Finish
            ),
            effects
        )
    }

    @Test
    fun `a fail-safe is refused when the activity is already finishing`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)
        val token = lifecycle.renderToken

        val effects = lifecycle.onFailSafeFired(
            token = token,
            isFinishing = true,
            isDestroyed = false,
            walkAwayPackage = walkAwayPackage
        )

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a fail-safe is refused when the activity is already destroyed`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)
        val token = lifecycle.renderToken

        val effects = lifecycle.onFailSafeFired(
            token = token,
            isFinishing = false,
            isDestroyed = true,
            walkAwayPackage = walkAwayPackage
        )

        assertTrue(effects.isEmpty())
    }

    /**
     * A new block delivered through `onNewIntent` during the transition must not be killed by the
     * previous attempt's timer — that block belongs to a different app or a different rule, and the
     * user has not walked away from it.
     */
    @Test
    fun `a fail-safe is refused when its token is stale because a new block was delivered since`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)
        val staleToken = lifecycle.renderToken
        lifecycle.onDelivered(pendingOverlayId = 2L) // a re-delivery bumps renderToken past staleToken

        val effects = lifecycle.onFailSafeFired(
            token = staleToken,
            isFinishing = false,
            isDestroyed = false,
            walkAwayPackage = walkAwayPackage
        )

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `the token handed out by a walk-away is valid immediately after it, and stale once the next delivery happens`() {
        val lifecycle = OverlayLifecycle()
        lifecycle.onDelivered(pendingOverlayId = 1L)

        val walkAwayEffects = lifecycle.onWalkAwayRequested(attributedPackage, blockMode, walkAwayPackage)
        val token = (walkAwayEffects.last() as OverlayLifecycle.Effect.ScheduleFailSafeFinish).token

        val whileStillCurrent = lifecycle.onFailSafeFired(token, isFinishing = false, isDestroyed = false, walkAwayPackage)
        assertFalse("token must still be valid immediately after the walk-away that minted it", whileStillCurrent.isEmpty())

        lifecycle.onDelivered(pendingOverlayId = 2L)

        val afterNextDelivery = lifecycle.onFailSafeFired(token, isFinishing = false, isDestroyed = false, walkAwayPackage)
        assertTrue("token must go stale exactly when the next delivery happens", afterNextDelivery.isEmpty())
    }

    // ── onStopped ──

    /**
     * Issue #8: this activity is `singleInstance` in its own task, so tabbing out only STOPPED it —
     * it stayed alive in the background with its countdown still running to zero.
     */
    @Test
    fun `leaving the overlay while it is genuinely still live marks it inactive and finishes it`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onStopped(isFinishing = false, isChangingConfigurations = false)

        assertEquals(
            listOf(OverlayLifecycle.Effect.MarkOverlayInactive, OverlayLifecycle.Effect.Finish),
            effects
        )
    }

    @Test
    fun `stopping while already finishing does not mark inactive a second time for one dismissal`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onStopped(isFinishing = true, isChangingConfigurations = false)

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `stopping for a rotation must not dismiss a live block`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onStopped(isFinishing = false, isChangingConfigurations = true)

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `stopping while both finishing and rotating still does nothing`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onStopped(isFinishing = true, isChangingConfigurations = true)

        assertTrue(effects.isEmpty())
    }

    // ── onTimerCompleted ──

    @Test
    fun `the timer completing while at least started with a passthrough target grants it, then finishes`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onTimerCompleted(hasPassthroughTarget = true, atLeastStarted = true)

        assertEquals(
            listOf(
                OverlayLifecycle.Effect.GrantPassthrough,
                OverlayLifecycle.Effect.MarkOverlayInactive,
                OverlayLifecycle.Effect.Finish
            ),
            effects
        )
    }

    /**
     * Issue #8's bypass, one layer further in: a countdown that reached zero while backgrounded must
     * never open the app. This is the belt-and-braces check behind the lifecycle-gated ticker and
     * [onStopped] itself.
     */
    @Test
    fun `the timer completing while not at least started never grants passthrough`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onTimerCompleted(hasPassthroughTarget = true, atLeastStarted = false)

        assertFalse(effects.contains(OverlayLifecycle.Effect.GrantPassthrough))
        assertEquals(
            listOf(OverlayLifecycle.Effect.MarkOverlayInactive, OverlayLifecycle.Effect.Finish),
            effects
        )
    }

    @Test
    fun `the timer completing with no passthrough target never grants passthrough`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onTimerCompleted(hasPassthroughTarget = false, atLeastStarted = true)

        assertFalse(effects.contains(OverlayLifecycle.Effect.GrantPassthrough))
    }

    // ── onDestroyed ──

    /**
     * Issue #36's third mechanism: a dying overlay's `onDestroy` runs AFTER the replacement's
     * `onResume`, so an unconditional clear wiped the live overlay's pending state. Proven here by
     * having a SECOND instance adopt a later id before the first instance is destroyed — the first
     * instance must still report the id IT adopted, never "whatever is pending now".
     */
    @Test
    fun `destroying an instance reports the id that instance adopted, not whatever is pending on a newer instance`() {
        val first = OverlayLifecycle()
        first.onDelivered(pendingOverlayId = 7L)

        val second = OverlayLifecycle()
        second.onDelivered(pendingOverlayId = 8L) // a replacement instance is already live

        val effects = first.onDestroyed()

        assertEquals(
            listOf(
                OverlayLifecycle.Effect.CancelPendingFailSafe,
                OverlayLifecycle.Effect.MarkOverlayInactive,
                OverlayLifecycle.Effect.ReportOverlayDismissed(7L)
            ),
            effects
        )
    }

    @Test
    fun `an instance destroyed before any delivery reports no overlay id, never one the guard could have handed out`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onDestroyed()

        assertEquals(
            listOf(
                OverlayLifecycle.Effect.CancelPendingFailSafe,
                OverlayLifecycle.Effect.MarkOverlayInactive,
                OverlayLifecycle.Effect.ReportOverlayDismissed(BlockLaunchGate.NO_OVERLAY_ID)
            ),
            effects
        )
    }

    // ── onUnrenderableBlock ──

    /**
     * `BlockMode.NONE` gates nothing, so showing any of the three overlay kinds for it would gate an
     * app the user explicitly chose not to gate — dismiss rather than render.
     */
    @Test
    fun `an unrenderable block is dismissed rather than shown`() {
        val lifecycle = OverlayLifecycle()

        val effects = lifecycle.onUnrenderableBlock()

        assertEquals(
            listOf(OverlayLifecycle.Effect.MarkOverlayInactive, OverlayLifecycle.Effect.Finish),
            effects
        )
    }

    // ── constant invariant ──

    /**
     * The two constants are one mechanism: if the fail-safe fires, its finish pops the blocked app
     * forward, and that pop must land while the service's walk-away suppression window is still
     * open — otherwise the block re-arms over an app the user already declined (#26, again).
     */
    @Test
    fun `the fail-safe delay is shorter than the walk-away suppression window it depends on`() {
        val failSafeMs = OverlayLifecycle.WALK_AWAY_FINISH_FAILSAFE_MS
        val suppressionMs = BlockLaunchGate.WALK_AWAY_TRANSITION_MS

        assertTrue(
            "WALK_AWAY_FINISH_FAILSAFE_MS ($failSafeMs) must be < WALK_AWAY_TRANSITION_MS ($suppressionMs)",
            failSafeMs < suppressionMs
        )
    }
}
