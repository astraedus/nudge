package com.astraedus.nudge.data.export

/**
 * Data classes representing the JSON export format for Nudge rules.
 * Designed to be human-readable and portable between devices.
 */
data class NudgeExport(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val rules: List<ExportedRule>,
    val groups: List<ExportedGroup> = emptyList(),
    /**
     * Usage history -- one entry per `usage_events` row. OPTIONAL, and deliberately added at
     * envelope version 1 rather than as a version 2: every shipped version of the importer reads
     * the envelope by KNOWN KEY ONLY (`version`, `rules`, `groups`), so an older Nudge simply
     * ignores this array, whereas a version bump would make it reject the entire file as "newer
     * than supported" and cost the user their rules as well as their history.
     */
    val history: List<ExportedHistoryEvent> = emptyList(),
    /**
     * App settings -- the custom block messages and the protection switches. OPTIONAL and added at
     * envelope version 1 for exactly the reason [history] was: every shipped importer reads the
     * envelope by KNOWN KEY ONLY, so an older Nudge ignores this object, whereas a version bump
     * would make it reject the whole file as "newer than supported" and cost the user their rules.
     *
     * null = this file carries no settings (every backup written before this existed).
     */
    val settings: ExportedSettings? = null
)

/**
 * The user's app-level settings, as carried by a backup file.
 *
 * EVERY field is nullable and every field is optional in the JSON, and that is the whole
 * forward/backward-compatibility mechanism at this level: a key that is absent means "this file
 * does not carry that setting", and the importing device simply keeps its own value. So a backup
 * written by a future Nudge that adds a tenth setting still restores its other nine here, and a
 * backup written by this build still restores on that future one.
 *
 * ## What is deliberately NOT here
 *
 * Device-local state, which describes this phone rather than the user's configuration and would be
 * actively wrong to copy onto another one:
 * - `ONBOARDING_COMPLETE` — restoring it would skip onboarding (incl. the accessibility permission
 *   walkthrough) on a device that has granted nothing.
 * - `EMERGENCY_PASS_USAGE` — the rolling 24h lockout ledger. Importing it would either hand the
 *   user a fresh pass or spend one they never used.
 * - `PIP_ESCAPE_PROMPTED` — a one-shot "we already explained this" marker, per device.
 * - `DEBUG_LOGGING_ENABLED` — a diagnostic switch, not a preference.
 *
 * And `GLOBAL_ENABLED`, the home-screen master switch, which is a judgement call rather than an
 * obvious exclusion. It is the user's "pause Nudge right now" control, not a configuration value,
 * and the asymmetry decides it: a fresh install already defaults to enabled, so carrying `true`
 * restores nothing, while carrying `false` silently turns the entire blocker off on the new device.
 * The only behaviour including it would add is the ability for a file to disable protection — the
 * failure class this codebase calls its worst ("a blocker silently blocking nothing"). Excluded.
 */
data class ExportedSettings(
    /** "Block restricted websites" master switch. */
    val contentFilterEnabled: Boolean? = null,
    /** Block mode applied to content-filter hits; one of [com.astraedus.nudge.domain.model.BlockMode]. */
    val contentFilterMode: String? = null,
    /** "Strict keyword matching" sub-toggle of the content filter. */
    val contentFilterStrictKeywords: Boolean? = null,
    /** Strict Mode ("commitment lock") master switch. */
    val strictModeEnabled: Boolean? = null,
    /** Strict Mode difficulty: characters the unlock challenge asks the user to type. */
    val strictModeChallengeLength: Int? = null,
    /** "Daily 2-minute pass" escape-hatch master switch. */
    val emergencyPassEnabled: Boolean? = null,
    /**
     * User-edited overlay message pools, one message per line. An empty string is a REAL value
     * meaning "use the built-in defaults" (see `NudgeMessages.resolvePool`), not "absent" — a user
     * who cleared their custom messages gets that cleared state restored.
     */
    val customDelayTitles: String? = null,
    val customDelaySubtitles: String? = null,
    val customHardBlockMessages: String? = null
) {
    /** True when this carries no setting at all, so there is nothing to write to the file. */
    val isEmpty: Boolean
        get() = contentFilterEnabled == null &&
            contentFilterMode == null &&
            contentFilterStrictKeywords == null &&
            strictModeEnabled == null &&
            strictModeChallengeLength == null &&
            emergencyPassEnabled == null &&
            customDelayTitles == null &&
            customDelaySubtitles == null &&
            customHardBlockMessages == null
}

data class ExportedRule(
    val packageName: String?,
    val groupName: String?, // Resolved from groupId -> group name for portability
    val mode: String,
    val delaySeconds: Int,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean,
    val scheduleDays: String?,
    val scheduleStartMinute: Int?,
    val scheduleEndMinute: Int?,
    val inAppFeatures: String?,
    val grayscale: Boolean,
    val showCounter: Boolean,
    val autoKickAfter: Int?,
    val showTimeRemaining: Boolean,
    val autoKickCooldownSeconds: Int,
    val webDomains: String? = null,
    val autoKickAfterMinutes: Int? = null,
    /** Independent block mode for [webDomains]; null = inherit [mode]. See `BlockRule.webBlockMode`. */
    val webBlockMode: String? = null
)

data class ExportedGroup(
    val name: String,
    val members: List<String> // package names
)

/**
 * One row of block/walk-away history -- the exact shape of a `UsageEvent`, minus its local row id.
 *
 * This is what makes the dashboard tiles and the insight pages survive a device move: they are
 * computed from `usage_events`, so without these rows a restored backup shows a user with years of
 * rules and a lifetime "Blocked" count of zero.
 *
 * Screen time is NOT here and cannot be: it comes from `UsageStatsManager`, which is OS-owned and
 * re-derives itself per device. Only Nudge's own decisions transfer.
 */
data class ExportedHistoryEvent(
    val packageName: String,
    /** Epoch millis, as stored. */
    val timestamp: Long,
    val wasBlocked: Boolean,
    val blockMode: String?,
    val userChangedMind: Boolean
)
