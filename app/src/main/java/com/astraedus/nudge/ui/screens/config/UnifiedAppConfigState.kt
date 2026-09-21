package com.astraedus.nudge.ui.screens.config

import androidx.compose.runtime.Immutable
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.FeatureMode

@Immutable
data class FeatureOverride(
    val mode: FeatureMode = FeatureMode.INHERIT,
    val delaySeconds: Int = 15,
    val autoKickEnabled: Boolean = false,
    val autoKickAfter: Int = 30,
    val autoKickCooldownMinutesText: String = "1",
    val originalAutoKickCooldownSeconds: Int = 60
)

@Immutable
data class FeatureInfo(
    val key: String,
    val displayName: String
)

@Immutable
data class UnifiedAppConfigState(
    val packageName: String = "",
    val appName: String = "",

    // Always-active settings
    val enabled: Boolean = true,
    val dailyLimitEnabled: Boolean = false,
    val dailyLimitMinutes: Int = 30,
    val showCounter: Boolean = true,
    val showTimeRemaining: Boolean = false,
    val grayscale: Boolean = false,

    // Web domain blocking
    val webDomainEnabled: Boolean = false,
    val webDomains: String = "",
    /**
     * Mode the configured websites block with while the app itself is NOT blocked
     * ([blocksWholeApp] false). While the app IS blocked, websites simply follow the app's mode
     * and this is kept in sync so switching whole-app blocking off does not silently change what
     * the websites do. Never [BlockMode.NONE] — "off" is expressed by [webDomainEnabled].
     */
    val webBlockMode: BlockMode = BlockMode.DELAY,

    // Default behavior
    val defaultMode: BlockMode = BlockMode.DELAY,
    /**
     * Mode to restore when the user turns whole-app blocking back on after switching it off.
     * Without this, toggling off and on again would silently discard a configured Hard Block or
     * Breathing choice and substitute the DELAY default.
     */
    val lastBlockingMode: BlockMode = BlockMode.DELAY,
    val defaultDelaySeconds: Int = 15,
    val defaultAutoKickEnabled: Boolean = false,
    val defaultAutoKickByInteractions: Boolean = true,
    val defaultAutoKickAfter: Int = 30,
    val defaultAutoKickAfterMinutesText: String = "",
    val defaultAutoKickCooldownMinutesText: String = "1",
    val originalAutoKickCooldownSeconds: Int = 60,
    val originalAutoKickAfterMinutes: Int? = null,

    // Feature overrides (only for supported apps)
    val availableFeatures: List<FeatureInfo> = emptyList(),
    val featureOverrides: Map<String, FeatureOverride> = emptyMap(),

    // Scheduled override
    val scheduledOverrideEnabled: Boolean = false,
    val scheduleDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val scheduleStartHour: Int = 6,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 9,
    val scheduleEndMinute: Int = 0,
    val scheduledMode: BlockMode = BlockMode.HARD_BLOCK,
    val scheduledDelaySeconds: Int = 15,
    val scheduledFeatureOverrides: Map<String, FeatureOverride> = emptyMap(),

    // UI state
    val isLoading: Boolean = true,
    val isSaved: Boolean = false,
    val hasExistingRules: Boolean = false,
    val showDeleteConfirmation: Boolean = false
) {
    val supportsFeatures: Boolean get() = availableFeatures.isNotEmpty()

    /**
     * Whether the app itself is gated. False means the app-level rule is [BlockMode.NONE]: the app
     * opens freely and only the feature overrides (Shorts, Reels, …) and the daily limit apply.
     */
    val blocksWholeApp: Boolean get() = defaultMode != BlockMode.NONE

    /**
     * The mode the shared `delaySeconds` control is currently editing the duration OF: the app's own
     * mode when the app is blocked, and otherwise the website mode — a web-only rule still needs
     * its countdown length editable.
     */
    private val durationMode: BlockMode
        get() = if (blocksWholeApp) defaultMode else webBlockMode

    /**
     * Whether the shared `delaySeconds` control is meaningful, i.e. whether the live mode spends a
     * duration at all. Asked of [BlockMode.usesDuration] rather than listed here, so a mode added to
     * the picker cannot ship with an uneditable duration.
     */
    val showDelayDuration: Boolean
        get() = if (blocksWholeApp) {
            durationMode.usesDuration
        } else {
            webDomainEnabled && durationMode.usesDuration
        }

    /**
     * What to call that control. A HOLD rule's seconds are not a countdown the user watches, they
     * are how long a thumb has to stay down, and "Delay Duration" over a hold picker reads as a
     * second, separate wait.
     */
    val delayDurationLabel: String
        get() = if (durationMode == BlockMode.HOLD) "Hold Duration" else "Delay Duration"

    companion object {
        val FEATURES_BY_PACKAGE: Map<String, List<FeatureInfo>> = mapOf(
            "com.instagram.android" to listOf(
                FeatureInfo("REELS", "Reels"),
                FeatureInfo("EXPLORE", "Explore")
            ),
            "com.google.android.youtube" to listOf(
                FeatureInfo("SHORTS", "Shorts")
            ),
            "com.zhiliaoapp.musically" to listOf(
                FeatureInfo("TIKTOK_FEED", "Feed")
            ),
            "com.ss.android.ugc.trill" to listOf(
                FeatureInfo("TIKTOK_FEED", "Feed")
            )
        )

        /**
         * Default web domains for common social apps.
         * Used to auto-populate when "Block on web too" is toggled on.
         */
        val DEFAULT_WEB_DOMAINS: Map<String, String> = mapOf(
            "com.instagram.android" to "instagram.com,www.instagram.com",
            "com.google.android.youtube" to "youtube.com,www.youtube.com,m.youtube.com",
            "com.zhiliaoapp.musically" to "tiktok.com,www.tiktok.com",
            "com.ss.android.ugc.trill" to "tiktok.com,www.tiktok.com"
        )
    }
}
