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
        val tabVanish: Boolean = false,
        /**
         * Whether the pass this block grants, once waited out, survives a short trip to the home
         * screen ([com.astraedus.nudge.data.db.entity.BlockRule.keepPassOnShortTrips], #110). Read
         * off the deciding rule exactly like [tabVanish], and set ONLY by the timed-mode branch of
         * [com.astraedus.nudge.domain.engine.BlockEngine]: a hard block or an exhausted time
         * budget never grants a pass, so it has nothing to keep. Defaults false, the shipped
         * behaviour, so a `Block` built anywhere else can never claim it.
         */
        val keepPassOnShortTrips: Boolean = false,
        /**
         * This block is Nuke Mode's, not a rule's. The overlay renders it with NO daily 2-minute
         * pass and only "Go home" (the way out of Nuke is the key or the emergency code, not the
         * pass), and the launch fingerprint keeps it distinct from a rule's hard block so a pending
         * rule overlay can never swallow it as a duplicate.
         */
        val nuke: Boolean = false
    ) : BlockDecision()
}
