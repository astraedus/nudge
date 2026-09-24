package com.astraedus.nudge.domain.model

sealed class BlockDecision {
    data object Allow : BlockDecision()
    data class Block(
        val mode: BlockMode,
        val delaySeconds: Int = 0,
        val grayscale: Boolean = false,
        val ruleName: String? = null,
        val dailyTimeRemainingMs: Long? = null,
        val dailyLimitMinutes: Int? = null,
        /**
         * Whether the Instagram Reels tab cover should be drawn for this block. Read off the
         * single rule that DECIDED this HARD_BLOCK (`unconditionalHardBlockRule` /
         * `timeBudgetRule` / the deciding timed-mode rule in [com.astraedus.nudge.domain.engine.BlockEngine]),
         * never `any {}` across [ActiveRule]s the way [grayscale] is: a user who turns the flag
         * off on the Reels rule must not have it silently re-enabled by an unrelated applicable
         * rule that still carries the default `true`. Defaults false here (unlike the entity's
         * `true`) because [BlockDecision.Allow] never carries it and a `Block` built without an
         * explicit value should never accidentally claim the cover is wanted.
         */
        val tabVanish: Boolean = false
    ) : BlockDecision()
}
