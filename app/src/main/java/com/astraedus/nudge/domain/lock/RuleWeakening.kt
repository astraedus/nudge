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
     *
     * NONE is listed explicitly rather than left to the fallback: it is a real, user-selectable
     * mode meaning "this rule gates nothing", so switching a blocking rule to it is the single
     * biggest weakening available in this editor and must be caught by Strict Mode.
     */
    private fun modeStrength(mode: String?): Int = when (mode) {
        "HARD_BLOCK" -> 3
        "DELAY" -> 2
        "BREATHING" -> 1
        "NONE" -> 0
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
     *  - autoKickAfter raised, or removed when one existed (more interactions before the kick)
     *  - autoKickAfterMinutes raised, or removed when one existed (more session time before the kick)
     *  - autoKickCooldownSeconds lowered (less time locked out after a kick)
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
        if (isNullableAllowanceRaised(old.dailyLimitMinutes, new.dailyLimitMinutes)) return true

        // Auto-kick interaction threshold: removing it, or raising it, allows more scrolling
        // before the kick fires.
        if (isNullableAllowanceRaised(old.autoKickAfter, new.autoKickAfter)) return true

        // Auto-kick time threshold: removing it, or raising it, allows more session time before
        // the kick fires.
        if (isNullableAllowanceRaised(old.autoKickAfterMinutes, new.autoKickAfterMinutes)) return true

        // Auto-kick cooldown: lowering it (unlike the two thresholds above) means less time
        // locked out after a kick, i.e. it gets EASIER to get back in sooner.
        if (new.autoKickCooldownSeconds < old.autoKickCooldownSeconds) return true

        return false
    }

    /**
     * A nullable "allowance" dimension (a cap that permits more usage the higher it is) is
     * weakened when an existing value is removed (null) or raised. Adding a cap where none
     * existed, or lowering an existing cap, is strengthening. Shared by [BlockRule.dailyLimitMinutes],
     * [BlockRule.autoKickAfter], and [BlockRule.autoKickAfterMinutes] — all three follow the same
     * "raised or removed-when-set = weaker" rule.
     */
    private fun isNullableAllowanceRaised(old: Int?, new: Int?): Boolean = when {
        old == null -> false            // none before -> any new value (or still none) is not weaker
        new == null -> true             // had a value, now removed -> weaker
        else -> new > old               // value raised -> weaker
    }
}
