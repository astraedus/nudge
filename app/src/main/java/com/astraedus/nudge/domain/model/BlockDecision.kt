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
        val nuke: Boolean = false,
        /**
         * The in-app feature (Shorts, Reels, ...) this block is ABOUT: set only when the rule that
         * DECIDED it is a feature rule naming that feature. Null for a whole-app rule, even when
         * the evaluation that found it was asked about a feature, and null for Nuke.
         *
         * Issue [#131](https://github.com/astraedus/nudge/issues/131): the feature used to be
         * taken from the PATH that asked rather than from the rule that answered. YouTube's
         * whole-app Delay reached the launch twice for one tap, once from the foreground path and
         * once from the feature detector (which had just recognised Shorts), and the two were
         * fingerprinted as different blocks, so both launched and both were counted. One rule is
         * one block, whichever path surfaced it. Read through
         * [com.astraedus.nudge.domain.block.BlockLaunchGate.identityOf], never re-derived.
         */
        val feature: String? = null,
        /**
         * When the deciding rule's time window ends, as wall-clock epoch ms: the block screen's
         * "Until 8:42 AM" (spec 3.9). Set for a rule with a days-and-times window, null for one that
         * is on all the time (and for Nuke). Read off the deciding rule, like [tabVanish].
         */
        val until: Long? = null,
        /**
         * Set when the deciding rule is an All apps rule: the rule line and the allowed-app icons
         * (spec 3.9). Null for every other block, which then keeps its redirect bubble.
         */
        val allApps: AllAppsBlock? = null
    ) : BlockDecision()
}
