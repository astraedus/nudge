package com.astraedus.nudge.domain.model

/**
 * What an IN-APP FEATURE override (Shorts, Reels, the TikTok feed) does, as the config screen
 * offers it.
 *
 * It is not [BlockMode] because it carries one option [BlockMode] cannot: [INHERIT], "no override
 * for this feature, do whatever the app-level rule does". Every other entry maps onto a real
 * [BlockMode], and that mapping lives in [toBlockMode] / [fromBlockMode] rather than in the screens
 * — it used to be four hand-written `when` blocks across the config screen and its view model, and
 * adding a mode meant finding all four.
 */
enum class FeatureMode {
    INHERIT,
    BLOCK,
    DELAY,
    HOLD,
    BREATHING;

    /**
     * The [BlockMode] a rule written for this override carries, or null for [INHERIT] — which
     * writes no rule at all.
     */
    fun toBlockMode(): BlockMode? = when (this) {
        INHERIT -> null
        BLOCK -> BlockMode.HARD_BLOCK
        DELAY -> BlockMode.DELAY
        HOLD -> BlockMode.HOLD
        BREATHING -> BlockMode.BREATHING
    }

    /** True when this override gates the feature behind a duration the user can edit. */
    val usesDuration: Boolean get() = this == DELAY || this == HOLD || this == BREATHING

    companion object {
        /**
         * The override that reproduces a stored rule's `mode` column. Anything unreadable — a null,
         * a [BlockMode.NONE], a string from a future version — reads as [INHERIT], which is the
         * safe direction: the feature then follows the app-level rule instead of silently becoming
         * its own unblocked exception.
         */
        fun fromBlockMode(mode: String?): FeatureMode =
            entries.firstOrNull { it.toBlockMode()?.name == mode } ?: INHERIT
    }
}
