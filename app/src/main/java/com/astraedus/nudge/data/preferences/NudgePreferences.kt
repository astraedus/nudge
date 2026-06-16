package com.astraedus.nudge.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.astraedus.nudge.service.GlobalEnabledProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
}
