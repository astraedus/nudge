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
     *
     * `internal` rather than private because [ImportedSettingsWeakening] ranks the CONTENT FILTER's
     * mode with the same ladder — it is the same [com.astraedus.nudge.domain.model.BlockMode], so a
     * second copy of this `when` could only ever drift from this one.
     */
    internal fun modeStrength(mode: String?): Int = when (mode) {
        "HARD_BLOCK" -> 3
        // DELAY and HOLD rank EQUAL on purpose. At the same duration they cost the same wall-clock
        // wait before the app opens, and the hold additionally costs a thumb on the screen for all
        // of it -- so neither is a softer version of the other, and switching between them at one
        // duration is not an edit Strict Mode should stand in the way of. Ranking HOLD lower would
        // make the stricter of the two the one that needs a challenge to select.
        "DELAY", "HOLD" -> 2
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
     *  - mode softened (HARD_BLOCK > DELAY = HOLD > BREATHING)
     *  - delaySeconds shortened (less time to reconsider)
     *  - dailyLimitMinutes lowered, or removed when one existed (more allowance)
     *  - autoKickAfter raised, or removed when one existed (more interactions before the kick)
     *  - autoKickAfterMinutes raised, or removed when one existed (more session time before the kick)
     *  - autoKickCooldownSeconds lowered (less time locked out after a kick)
     *  - web enforcement softened: the mode a rule's websites block with (webBlockMode, falling
     *    back to mode) lowered, or the domains removed entirely, when web blocking was configured
     *  - tabVanish true -> false: it removes the Reels-tab cover, i.e. it removes enforcement, so
     *    turning it off is weakening exactly like softening the mode is. `followingSteer` is
     *    deliberately NOT a dimension here -- it is a steer, not a block, so flipping it either way
     *    never changes how much protection a rule enforces (do not add it later "by symmetry" with
     *    tabVanish; it belongs to a different axis entirely).
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

        // Web domains now enforce independently of the app-level mode (issue #21), so they are
        // their own weakening axis: without this, Strict Mode could be sidestepped by softening
        // (or deleting) website blocking while leaving the app-level rule untouched.
        if (isWebEnforcementWeakened(old, new)) return true

        // Tab Vanish: turning it off removes the Reels-tab cover, i.e. removes enforcement that
        // was there before. Turning it ON is never weakening (it only adds a cover), so this is a
        // one-directional check like `enabled` above, not a strength comparison.
        if (old.tabVanish && !new.tabVanish) return true

        return false
    }

    /**
     * True when [new] enforces less on the web than [old] did. Only bites when [old] actually had
     * web blocking configured — adding domains, or having none either way, is never weakening.
     * Removing the domains counts: it turns website enforcement off entirely.
     */
    private fun isWebEnforcementWeakened(old: BlockRule, new: BlockRule): Boolean {
        val oldStrength = webStrength(old) ?: return false
        val newStrength = webStrength(new) ?: return true
        return newStrength < oldStrength
    }

    /**
     * Strength of a rule's WEB enforcement, or null when it enforces nothing on the web (no
     * domains configured). Mirrors [com.astraedus.nudge.domain.model.WebBlockMode.resolve]:
     * `webBlockMode` wins, else the app-level mode.
     */
    private fun webStrength(rule: BlockRule): Int? {
        if (rule.webDomains.isNullOrBlank()) return null
        return modeStrength(rule.webBlockMode ?: rule.mode)
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
