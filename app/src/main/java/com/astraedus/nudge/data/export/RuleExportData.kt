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
     * Rules an older Nudge would MISREAD if they sat in [rules]: every All apps rule and every
     * first-unlock rule (spec 5.8). Same [ExportedRule] shape, with [ExportedRule.allApps] and the
     * except lists filled in for an All apps rule, or [ExportedRule.firstUnlockMinutes] for a
     * first-unlock one. An older importer reads the envelope by known key only, so it skips this
     * array and these rules, where in [rules] it would have imported an All apps rule as a rule
     * with no target and a first-unlock rule with no window at all (i.e. always on). Written only
     * when non-empty, so a file without either kind is byte-for-byte what it was before.
     */
    val extendedRules: List<ExportedRule> = emptyList(),
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
 * written by a future Nudge that adds a thirteenth setting still restores its other twelve here, and a
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
    /**
     * "Allowed words & sites": the user's exceptions to strict keyword matching, canonical and
     * sorted. An EMPTY list is a real value ("no exceptions") and replaces the device's list; null
     * means the file does not carry it. Adding entries on import is weakening (Strict Mode gated).
     */
    val contentFilterAllowlist: List<String>? = null,
    /**
     * "Blocked words & sites": the user's own additions to the content filter, canonical and
     * sorted. An EMPTY list is a real value ("no extra blocks") and replaces the device's list; null
     * means the file does not carry it. REMOVING entries on import is weakening (Strict Mode gated).
     */
    val contentFilterBlocklist: List<String>? = null,
    /** Strict Mode ("commitment lock") master switch. */
    val strictModeEnabled: Boolean? = null,
    /** Strict Mode difficulty: characters the unlock challenge asks the user to type. */
    val strictModeChallengeLength: Int? = null,
    /** "Daily 2-minute pass" escape-hatch master switch. */
    val emergencyPassEnabled: Boolean? = null,
    /**
     * "Bro. wtf." bounce check-ins. Not a protection setting: turning it off does not unblock
     * anything, so importing it is never weakening and needs no gate.
     */
    val bounceCheckInEnabled: Boolean? = null,
    /** Camera preview behind block screens. Only a picture; not a protection setting, never weakening. */
    val cameraOnBlockScreens: Boolean? = null,
    /** Whole-phone pause (#123). Off by default; not a protection setting. */
    val phoneNudgeEnabled: Boolean? = null,
    /** Minutes of session time before the first pause. */
    val phoneNudgeThresholdMinutes: Int? = null,
    /** Length of the first pause; later ones double up to 30s. */
    val phoneNudgePauseSeconds: Int? = null,
    /**
     * User-edited overlay message pools, one message per line. An empty string is a REAL value
     * meaning "use the built-in defaults" (see `NudgeMessages.resolvePool`), not "absent" — a user
     * who cleared their custom messages gets that cleared state restored.
     */
    val customDelayTitles: String? = null,
    val customDelaySubtitles: String? = null,
    val customHardBlockMessages: String? = null,
    /**
     * The hour the first-unlock logical day starts, minutes from midnight (default 240). Null means
     * the file does not carry it. Changing it is weakening while an enabled first-unlock rule
     * exists, because it can move today's unlock into yesterday (spec 5.6), so the import gate
     * checks it like the other protection settings.
     */
    val dayStartMinute: Int? = null
) {
    /** True when this carries no setting at all, so there is nothing to write to the file. */
    val isEmpty: Boolean
        get() = contentFilterEnabled == null &&
            contentFilterMode == null &&
            contentFilterStrictKeywords == null &&
            contentFilterAllowlist == null &&
            contentFilterBlocklist == null &&
            strictModeEnabled == null &&
            strictModeChallengeLength == null &&
            emergencyPassEnabled == null &&
            bounceCheckInEnabled == null &&
            cameraOnBlockScreens == null &&
            phoneNudgeEnabled == null &&
            phoneNudgeThresholdMinutes == null &&
            phoneNudgePauseSeconds == null &&
            customDelayTitles == null &&
            customDelaySubtitles == null &&
            customHardBlockMessages == null &&
            dayStartMinute == null
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
    /**
     * See `BlockRule.remindAfterMinutes` (issue #77). Null = no usage reminder, which is also what
     * a backup written before this field existed means: such a file carries no key at all.
     */
    val remindAfterMinutes: Int? = null,
    /** Independent block mode for [webDomains]; null = inherit [mode]. See `BlockRule.webBlockMode`. */
    val webBlockMode: String? = null,
    /**
     * See `BlockRule.tabVanish`. Defaults **true**, matching the entity default, and that default
     * is also the correct restore reading for a backup written before this feature existed: such a
     * file carries no `tabVanish` key at all, and every rule it describes had no tab cover of its
     * own on the device that wrote it -- restoring it under this build's new default (on) is what
     * "this rule hard-blocks Instagram REELS" means now that a cover exists, not a downgrade.
     */
    val tabVanish: Boolean = true,
    /**
     * See `BlockRule.followingSteer`. Defaults **false**: an experimental, opt-in capability that
     * a pre-feature backup could never have carried, so its absence must restore as off.
     */
    val followingSteer: Boolean = false,
    /**
     * See `BlockRule.keepPassOnShortTrips` (#110). Defaults **false**: opt-in, and a backup
     * written before it existed carries no key, so it must restore with the pass ending at Home
     * exactly as it did on the device that wrote it.
     */
    val keepPassOnShortTrips: Boolean = false,
    /**
     * The first-unlock window in minutes (spec 5.3), or null for every ordinary rule. Only ever
     * carried by an entry of `extendedRules`: in `rules` the key is neither written nor read, so a
     * hand-edited `"firstUnlockMinutes"` there can not add a schedule the file's writer did not
     * understand. See [NudgeExport.extendedRules].
     */
    val firstUnlockMinutes: Int? = null,
    /**
     * An All apps rule (spec 3.4). Only ever true for an entry of `extendedRules`: in `rules` the
     * key is not read, so a hand-edited `"allApps": true` there can not turn an app rule into a
     * lock on every app.
     */
    val allApps: Boolean = false,
    /** An All apps rule's allowed apps, in the order the user added them. Empty otherwise. */
    val exceptPackages: List<String> = emptyList(),
    /**
     * An All apps rule's allowed groups, by NAME: group ids do not survive a move to another phone.
     * Resolved against the groups after they are imported; a name with no group is dropped, which
     * only makes the rule stricter.
     */
    val exceptGroups: List<String> = emptyList()
)

/**
 * One group in a backup (spec 5.2). Envelope version stays 1; the definition fields are additive.
 *
 * - [members] is the group RESOLVED on the exporting device (includes + installed category apps -
 *   "Removed by you" - essentials). An older Nudge reads only [name] and [members], so it imports a
 *   usable hand-picked group. A current Nudge ignores it when [hasDefinition] (importing it as
 *   includes would freeze category apps into hand-picked rows).
 * - [categories] are `AppCategory` names in the offered order, [includes] the apps added by hand,
 *   [excluded] "Removed by you".
 * - [hasDefinition] is false for a file from before categories (none of the three keys present):
 *   then [members] ARE the includes, exactly as before.
 */
data class ExportedGroup(
    val name: String,
    val members: List<String>, // package names
    val categories: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val hasDefinition: Boolean = false
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
