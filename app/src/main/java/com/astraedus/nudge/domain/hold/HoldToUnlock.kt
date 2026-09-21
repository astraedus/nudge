package com.astraedus.nudge.domain.hold

/**
 * "Hold to unlock": the deliberate press-and-hold that opens a blocked app once its timer has run
 * out ([issue #35](https://github.com/astraedus/nudge/issues/35), asked for by a user through two
 * channels in one week).
 *
 * A countdown you can end with a reflex tap is friction the thumb learns to spend without the
 * person noticing. A hold is a few seconds the user has to keep *choosing*, and letting go is the
 * cheap action rather than the expensive one — which is the direction this app wants every affordance
 * to point.
 *
 * ## Where the duration comes from
 *
 * One global setting today, and [resolveDurationMs] is deliberately shaped for the per-app override
 * that is the next request on the same issue: the call site already asks "what is the EFFECTIVE hold
 * for this block", so adding a nullable `holdSeconds` column to `BlockRule` later fills in the first
 * argument and changes nothing else. Nothing is stored per-rule yet, so there is no migration to
 * regret.
 *
 * ## Why seconds, and why bounded
 *
 * The picker offers [OPTION_SECONDS]; the stored value is a plain `Int` of seconds so a future
 * option (10s, say) is a list edit rather than a preference migration. Everything that reads the
 * number clamps it to `0..`[MAX_SECONDS], because this value also arrives from an IMPORTED BACKUP
 * FILE — the same reasoning that bounds the Strict Mode challenge length. A hold nobody can
 * physically complete is not friction, it is a permanent lockout of the user's own escape.
 */
object HoldToUnlock {

    /** Hold disabled: the timer completing opens the app directly, as it did before v1.17.3. */
    const val OFF_SECONDS = 0

    /**
     * Default hold length for a fresh install and for every user upgrading into this feature.
     *
     * Three seconds is long enough that it cannot be spent by a reflex — the whole point — and short
     * enough that it never reads as the app being broken. The user who asked for this cites Wall
     * Habit, which uses a hold of the same order.
     */
    const val DEFAULT_SECONDS = 3

    /** The durations the Settings picker offers, "Off" first. */
    val OPTION_SECONDS = listOf(OFF_SECONDS, 2, 3, 5)

    /**
     * Hard ceiling on any hold this app will honour, whatever the source.
     *
     * Deliberately larger than the largest [OPTION_SECONDS] entry: this bound exists to keep an
     * imported file (or a future option) from installing an unfinishable hold, not to re-state the
     * picker. A value above it is clamped rather than obeyed.
     */
    const val MAX_SECONDS = 30

    /**
     * The hold that applies to one block, in milliseconds. `0` means "no hold".
     *
     * @param ruleOverrideSeconds a per-rule hold, or null to use the global setting. Always null
     *   today; see the class doc.
     * @param globalSeconds the user's global "Hold to unlock" setting.
     */
    fun resolveDurationMs(ruleOverrideSeconds: Int?, globalSeconds: Int): Long =
        (ruleOverrideSeconds ?: globalSeconds).coerceIn(OFF_SECONDS, MAX_SECONDS) * 1_000L

    /** True when [durationMs] describes a real hold rather than "open immediately". */
    fun isEnabled(durationMs: Long): Boolean = durationMs > 0L
}

/**
 * The hold's state machine: press, release, progress, and a completion that fires exactly once.
 *
 * Pure Kotlin with no Android and no Compose, because the interesting parts of a press-and-hold are
 * the RULES (letting go resets it; completion may not fire twice; a re-delivered block starts over)
 * and none of them are observable on a device without a finger and a stopwatch. The composable that
 * drives this ([com.astraedus.nudge.ui.overlay.HoldToUnlockControl]) owns only rendering.
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
