package com.astraedus.nudge.domain.lock

/**
 * Single ordering of block-mode "strength", shared by every weakening check ([RuleWeakening] for
 * per-app rules, [com.astraedus.nudge.domain.lightsoff.LightsOffWeakening] for Lights Off) so two
 * gates can never disagree about whether a mode change reduces protection.
 *
 * Modes are compared as their stored STRING names (that is what Room / DataStore hold), and anything
 * unrecognized — a future mode, or "no rule at all" — sorts below every known mode.
 */
object BlockModeStrength {

    fun of(mode: String?): Int = when (mode) {
        "HARD_BLOCK" -> 3
        "DELAY" -> 2
        "BREATHING" -> 1
        else -> 0
    }

    /** True when [new] is a softer mode than [old] (protection reduced). */
    fun isSoftened(old: String?, new: String?): Boolean = of(new) < of(old)
}
