package com.astraedus.nudge.domain.hold

/**
 * The press-and-hold's state machine: press, release, progress, and a completion that fires exactly
 * once.
 *
 * Pure Kotlin with no Android and no Compose, because the interesting parts of a press-and-hold are
 * the RULES (letting go resets it; completion may not fire twice; a re-delivered block starts over)
 * and none of them are observable on a device without a finger and a stopwatch. The composable that
 * drives this ([com.astraedus.nudge.ui.overlay.HoldTarget]) owns only rendering.
 *
 * Not thread-safe by design: every call comes from the main thread (a pointer event, or the frame
 * loop driving it).
 */
class HoldProgress(private val durationMs: Long) {

    /** When the current hold began, or null when nothing is being held. */
    private var pressedAtMs: Long? = null

    private var completedOnce = false

    /**
     * True while a hold is IN PROGRESS. False before the first press, after any release, and once
     * the hold has completed — a finished hold is not an ongoing one, and reading it as ongoing is
     * how a caller ends up waiting for a release that already happened.
     */
    val isPressed: Boolean get() = pressedAtMs != null

    /** True once this hold has completed. Completion is terminal until [reset]. */
    val isCompleted: Boolean get() = completedOnce

    /**
     * Begin a hold at [nowMs], or RESTART one already in progress from that moment.
     *
     * Restarting rather than ignoring a second press is the safe direction: the only caller that can
     * press twice without an intervening [release] is the driving effect re-entering after the
     * overlay was backgrounded, and time spent off-screen must not count toward opening an app —
     * the same rule the countdown itself follows (issue #8). A completed hold is not re-armed.
     */
    fun press(nowMs: Long) {
        if (completedOnce) return
        pressedAtMs = nowMs
    }

    /** Let go. An incomplete hold is abandoned and the next press starts from zero. */
    fun release() {
        if (completedOnce) return
        pressedAtMs = null
    }

    /** How far the current hold has got, `0f..1f`. Zero while nothing is held, 1f once complete. */
    fun fraction(nowMs: Long): Float {
        if (completedOnce) return 1f
        val start = pressedAtMs ?: return 0f
        if (durationMs <= 0L) return 1f
        val held = (nowMs - start).coerceAtLeast(0L)
        return (held.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /**
     * Advance the hold to [nowMs] and report whether it has JUST completed.
     *
     * Returns true exactly once per completed hold, never on a later call — the caller grants
     * passthrough on a true, and this is the only thing standing between one hold and two grants.
     */
    fun advance(nowMs: Long): Boolean {
        if (completedOnce) return false
        val start = pressedAtMs ?: return false
        if (durationMs > 0L && nowMs - start < durationMs) return false
        completedOnce = true
        // The hold is over, so it is no longer in progress. Clearing this is what keeps [isPressed]
        // honest; [fraction] reads 1f from [completedOnce] and does not need the start time again.
        pressedAtMs = null
        return true
    }

    /**
     * Back to untouched, including a completed hold.
     *
     * The block overlay is `singleInstance`, so a new block can be delivered onto the live activity;
     * every such delivery is a NEW attempt and owes the user a full hold. In practice the composable
     * is discarded by the per-delivery `key(blockToken)` and a fresh machine is remembered, so this
     * exists for a caller that holds one across deliveries — and so the rule is stated somewhere a
     * test can read it.
     */
    fun reset() {
        pressedAtMs = null
        completedOnce = false
    }
}
