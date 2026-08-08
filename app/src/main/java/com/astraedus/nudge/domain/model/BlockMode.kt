package com.astraedus.nudge.domain.model

enum class BlockMode {
    /**
     * Blocks nothing on its own. Exists so an app-level rule can carry the daily limit, the
     * interaction counter, the time-remaining overlay, grayscale and web domains WITHOUT also
     * gating the whole app — which is what makes "block only Shorts, leave the rest of YouTube
     * alone" expressible.
     *
     * Before this existed, [com.astraedus.nudge.ui.screens.config.UnifiedAppConfigScreen] always
     * wrote a blocking app-level rule and feature overrides could only differ from it, so a
     * feature-scoped rule with an unblocked host app could not be configured at all.
     *
     * [com.astraedus.nudge.domain.engine.BlockEngine] matches NONE in none of its block branches,
     * so it yields Allow. A daily limit on a NONE rule is still enforced — the time-budget check
     * keys off `dailyLimitMinutes`, not the mode — which is deliberate: "don't block it, but cap
     * it at 60 min/day" is a real thing users want.
     */
    NONE,
    HARD_BLOCK,
    DELAY,
    BREATHING
}
