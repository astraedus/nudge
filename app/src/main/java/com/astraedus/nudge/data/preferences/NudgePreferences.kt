package com.astraedus.nudge.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.astraedus.nudge.data.export.ExportedSettings
import com.astraedus.nudge.service.GlobalEnabledProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nudge_prefs")

@Singleton
class NudgePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) : GlobalEnabledProvider {

    private object Keys {
        val GLOBAL_ENABLED = booleanPreferencesKey("global_enabled")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val CONTENT_FILTER_ENABLED = booleanPreferencesKey("content_filter_enabled")
        val CONTENT_FILTER_MODE = stringPreferencesKey("content_filter_mode")
        val CONTENT_FILTER_STRICT_KEYWORDS = booleanPreferencesKey("content_filter_strict_keywords")
        val CUSTOM_DELAY_TITLES = stringPreferencesKey("custom_delay_titles")
        val CUSTOM_DELAY_SUBTITLES = stringPreferencesKey("custom_delay_subtitles")
        val CUSTOM_HARD_BLOCK_MESSAGES = stringPreferencesKey("custom_hard_block_messages")
        val STRICT_MODE_ENABLED = booleanPreferencesKey("strict_mode_enabled")
        val STRICT_MODE_CHALLENGE_LENGTH = intPreferencesKey("strict_mode_challenge_length")
        val EMERGENCY_PASS_ENABLED = booleanPreferencesKey("emergency_pass_enabled")
        val EMERGENCY_PASS_USAGE = stringPreferencesKey("emergency_pass_usage")
        val PIP_ESCAPE_PROMPTED = stringPreferencesKey("pip_escape_prompted")
        val PROTECTION_DEGRADED = booleanPreferencesKey("protection_degraded")
        val PROTECTION_ALERT_SHOWN_AT = longPreferencesKey("protection_alert_shown_at")
    }

    override val isGlobalEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.GLOBAL_ENABLED] ?: true }

    suspend fun setGlobalEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GLOBAL_ENABLED] = enabled
        }
    }

    val isOnboardingComplete: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.ONBOARDING_COMPLETE] ?: false }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETE] = complete
        }
    }

    val isDebugLoggingEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.DEBUG_LOGGING_ENABLED] ?: false }

    suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEBUG_LOGGING_ENABLED] = enabled
        }
    }

    /** Generic "Content Filter" master switch. Opt-in: defaults to false. */
    val contentFilterEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.CONTENT_FILTER_ENABLED] ?: false }

    suspend fun setContentFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONTENT_FILTER_ENABLED] = enabled
        }
    }

    /** Block mode applied to content-filter hits. One of BlockMode names. Defaults to HARD_BLOCK. */
    val contentFilterMode: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[Keys.CONTENT_FILTER_MODE] ?: "HARD_BLOCK" }

    suspend fun setContentFilterMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONTENT_FILTER_MODE] = mode
        }
    }

    /**
     * Opt-in sub-toggle of the content filter that also matches ambiguous keyword terms
     * found in a URL's search query. Defaults to false so the general userbase is
     * unaffected; only matters when [contentFilterEnabled] is also on.
     */
    val contentFilterStrictKeywords: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.CONTENT_FILTER_STRICT_KEYWORDS] ?: false }

    suspend fun setContentFilterStrictKeywords(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONTENT_FILTER_STRICT_KEYWORDS] = enabled
        }
    }

    /**
     * User-edited overlay messages, one per line. Empty string ("") means "use the
     * built-in defaults" — resolved via [com.astraedus.nudge.ui.overlay.NudgeMessages.resolvePool].
     */
    val customDelayTitles: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[Keys.CUSTOM_DELAY_TITLES] ?: "" }

    suspend fun setCustomDelayTitles(value: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CUSTOM_DELAY_TITLES] = value
        }
    }

    val customDelaySubtitles: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[Keys.CUSTOM_DELAY_SUBTITLES] ?: "" }

    suspend fun setCustomDelaySubtitles(value: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CUSTOM_DELAY_SUBTITLES] = value
        }
    }

    val customHardBlockMessages: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[Keys.CUSTOM_HARD_BLOCK_MESSAGES] ?: "" }

    suspend fun setCustomHardBlockMessages(value: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CUSTOM_HARD_BLOCK_MESSAGES] = value
        }
    }

    /**
     * Strict Mode ("commitment lock") master switch. Opt-in: defaults to false. While on, any
     * action that WEAKENS protection — including turning this off — requires passing the unlock
     * challenge. Strengthening protection is always free.
     */
    val isStrictModeEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.STRICT_MODE_ENABLED] ?: false }

    suspend fun setStrictModeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STRICT_MODE_ENABLED] = enabled
        }
    }

    /**
     * Strict Mode challenge difficulty: number of raw characters the user must type to unlock.
     * Defaults to [com.astraedus.nudge.domain.lock.StrictModeChallenge.DEFAULT_LENGTH] (24).
     */
    val strictModeChallengeLength: Flow<Int> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.STRICT_MODE_CHALLENGE_LENGTH]
                ?: com.astraedus.nudge.domain.lock.StrictModeChallenge.DEFAULT_LENGTH
        }

    suspend fun setStrictModeChallengeLength(length: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STRICT_MODE_CHALLENGE_LENGTH] = length
        }
    }

    /**
     * Master toggle for the "2-minute daily pass" emergency escape hatch. Defaults to true (the
     * escape hatch is available out of the box); users can disable it entirely. Independent of Strict
     * Mode, which hides the pass button while it is on.
     */
    val emergencyPassEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.EMERGENCY_PASS_ENABLED] ?: true }

    suspend fun setEmergencyPassEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EMERGENCY_PASS_ENABLED] = enabled
        }
    }

    /**
     * Serialized emergency-pass usage ledger (`pkg=epochMillis;…`), holding the pass's last use for
     * the rolling 24h GLOBAL lockout. Empty string = never used. A legacy per-app ledger migrates
     * transparently — [com.astraedus.nudge.domain.emergency.EmergencyPass.globalLastUsed] reads the
     * MAX timestamp across entries. A fresh use collapses it to the single global entry.
     */
    val emergencyPassUsage: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[Keys.EMERGENCY_PASS_USAGE] ?: "" }

    suspend fun setEmergencyPassUsage(raw: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EMERGENCY_PASS_USAGE] = raw
        }
    }

    /**
     * Record that the emergency pass was used at [now]. The lockout is GLOBAL (across every app), so
     * this overwrites the ledger with a single global entry — no read-modify-merge needed.
     */
    suspend fun recordEmergencyPassUsed(now: Long) {
        setEmergencyPassUsage(
            com.astraedus.nudge.domain.emergency.EmergencyPass.serialize(
                com.astraedus.nudge.domain.emergency.EmergencyPass.recordGlobal(now)
            )
        )
    }

    /**
     * Serialized `;`-separated set of packages we have already shown the picture-in-picture escape
     * explainer for (issue #19). Empty string = none. Parsed via
     * [com.astraedus.nudge.domain.pip.PipEscapeLedger.parse].
     *
     * The explainer is one-shot education about a platform limitation Nudge cannot fix in code, so
     * this exists purely to stop it nagging.
     */
    val pipEscapePromptedPackages: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[Keys.PIP_ESCAPE_PROMPTED] ?: "" }

    /**
     * Record that the PiP escape explainer has been shown for [packageName]. Read-modify-write under
     * a single `edit` so two apps escaping close together cannot clobber each other's entry.
     */
    suspend fun recordPipEscapePrompted(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = com.astraedus.nudge.domain.pip.PipEscapeLedger.parse(
                prefs[Keys.PIP_ESCAPE_PROMPTED] ?: ""
            )
            prefs[Keys.PIP_ESCAPE_PROMPTED] = com.astraedus.nudge.domain.pip.PipEscapeLedger.serialize(
                com.astraedus.nudge.domain.pip.PipEscapeLedger.record(current, packageName)
            )
        }
    }

    // --- Protection watchdog state (see [com.astraedus.nudge.domain.health.ProtectionWatchdog]) ---

    /**
     * Whether the last watchdog run found protection degraded. Persisted rather than kept in
     * memory on purpose: the failure this exists for is the OS killing our process, so the very
     * state the next run needs is the state a field would have lost.
     */
    val protectionDegraded: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.PROTECTION_DEGRADED] ?: false }

    /** Epoch millis of the last protection alert we posted; 0 = never. Backs the alert cooldown. */
    val protectionAlertShownAt: Flow<Long> = context.dataStore.data
        .map { prefs -> prefs[Keys.PROTECTION_ALERT_SHOWN_AT] ?: 0L }

    /**
     * Records one watchdog verdict. Both values are written under a single `edit` because they are
     * read together as one snapshot — a run that recorded "degraded" but lost the alert timestamp
     * would re-alert on the next run and burn through the cooldown the user is being spared.
     */
    suspend fun recordProtectionCheck(degraded: Boolean, alertShownAtMs: Long?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PROTECTION_DEGRADED] = degraded
            alertShownAtMs?.let { prefs[Keys.PROTECTION_ALERT_SHOWN_AT] = it }
        }
    }

    // --- Backup: the settings an export file carries (see [ExportedSettings]) ---------------

    /**
     * The current value of every setting a backup carries.
     *
     * Read through the public Flows above rather than off one raw `Preferences` snapshot, on
     * purpose: those Flows are where each setting's DEFAULT lives, and a second hand-written copy
     * of "`emergencyPassEnabled` defaults to true" here would eventually disagree with them —
     * exporting a value the app never actually used. Nine cached reads on a user-initiated,
     * already-off-main export is a trade worth making for that.
     */
    suspend fun exportableSettings(): ExportedSettings = ExportedSettings(
        contentFilterEnabled = contentFilterEnabled.first(),
        contentFilterMode = contentFilterMode.first(),
        contentFilterStrictKeywords = contentFilterStrictKeywords.first(),
        strictModeEnabled = isStrictModeEnabled.first(),
        strictModeChallengeLength = strictModeChallengeLength.first(),
        emergencyPassEnabled = emergencyPassEnabled.first(),
        customDelayTitles = customDelayTitles.first(),
        customDelaySubtitles = customDelaySubtitles.first(),
        customHardBlockMessages = customHardBlockMessages.first()
    )

    /**
     * Applies an imported backup's settings.
     *
     * ALL-OR-NOTHING: every key is written inside ONE `edit` block, which DataStore commits as a
     * single transaction. A half-applied restore is the shape to avoid here — landing
     * `strictModeEnabled = false` but not the challenge length that came with it leaves the user in
     * a state their backup never described, on the app's protection settings.
     *
     * A null field is SKIPPED, not written: the file does not carry that setting, so this device's
     * own value stands. That is what lets a backup from a future Nudge (or a hand-edited partial
     * file) restore what it does carry without blanking what it does not.
     */
    suspend fun applyImportedSettings(settings: ExportedSettings) {
        if (settings.isEmpty) return
        context.dataStore.edit { prefs ->
            settings.contentFilterEnabled?.let { prefs[Keys.CONTENT_FILTER_ENABLED] = it }
            settings.contentFilterMode?.let { prefs[Keys.CONTENT_FILTER_MODE] = it }
            settings.contentFilterStrictKeywords?.let {
                prefs[Keys.CONTENT_FILTER_STRICT_KEYWORDS] = it
            }
            settings.strictModeEnabled?.let { prefs[Keys.STRICT_MODE_ENABLED] = it }
            settings.strictModeChallengeLength?.let {
                prefs[Keys.STRICT_MODE_CHALLENGE_LENGTH] = it
            }
            settings.emergencyPassEnabled?.let { prefs[Keys.EMERGENCY_PASS_ENABLED] = it }
            settings.customDelayTitles?.let { prefs[Keys.CUSTOM_DELAY_TITLES] = it }
            settings.customDelaySubtitles?.let { prefs[Keys.CUSTOM_DELAY_SUBTITLES] = it }
            settings.customHardBlockMessages?.let { prefs[Keys.CUSTOM_HARD_BLOCK_MESSAGES] = it }
        }
    }
}
