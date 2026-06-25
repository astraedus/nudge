package com.astraedus.nudge.domain.lock

import com.astraedus.nudge.data.db.entity.BlockRule

/**
 * Pure-Kotlin comparison of two versions of the same per-app block rule to decide whether the
 * edit WEAKENS protection. Under Strict Mode, weakening edits are gated behind the unlock
 * challenge; strengthening edits and no-op edits save freely.
 *
 * No Android imports — unit-testable on the JVM. ([BlockRule]'s Room annotations are compile-time
 * only and do not pull Android into JVM tests.)
 */
object RuleWeakening {

    /**
     * Block-mode strength ordering (higher = stronger protection). Anything not recognized
     * (e.g. a future mode, or "no rule") sorts below the known modes.
     */
    private fun modeStrength(mode: String?): Int = when (mode) {
        "HARD_BLOCK" -> 3
        "DELAY" -> 2
        "BREATHING" -> 1
        else -> 0
    }

    /**
     * Returns true if [new] is weaker protection than [old] in ANY dimension, treating each
     * dimension independently — softening one axis is weakening even if another is strengthened
     * (the user must justify the part that reduces protection).
     *
     * Weakening dimensions:
     *  - enabled true -> false (the rule stops applying)
     *  - mode softened (HARD_BLOCK > DELAY > BREATHING)
     *  - delaySeconds shortened (less time to reconsider)
     *  - dailyLimitMinutes lowered, or removed when one existed (more allowance)
     *
     * Strengthening or unchanged on all dimensions -> false.
     */
    fun isWeakening(old: BlockRule, new: BlockRule): Boolean {
        // Disabling an active rule.
        if (old.enabled && !new.enabled) return true

        // Softening the block mode.
        if (modeStrength(new.mode) < modeStrength(old.mode)) return true

        // Shortening the delay = less friction before the app opens.
        if (new.delaySeconds < old.delaySeconds) return true

        // Daily limit: removing it, or raising it, grants more usage.
        if (isDailyLimitWeakened(old.dailyLimitMinutes, new.dailyLimitMinutes)) return true

        return false
    }

    /**
     * A daily limit is weakened when an existing cap is removed (null) or raised. Adding a cap
     * where none existed, or lowering an existing cap, is strengthening.
     */
    private fun isDailyLimitWeakened(old: Int?, new: Int?): Boolean = when {
        old == null -> false            // no cap before -> any new cap (or still none) is not weaker
        new == null -> true             // had a cap, now removed -> weaker
        else -> new > old               // cap raised -> weaker
    }
}
