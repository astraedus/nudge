package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.RuleSnapshots
import com.astraedus.nudge.domain.lock.SettingsWeakening
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place an editor changes the logical day start ("New day starts at"). It answers the
 * Strict Mode gate question and does the write, so the three first-unlock editors cannot drift
 * apart on what counts as a weakening change.
 *
 * Reading the current value and the enabled rules here rather than at the call site also means the
 * check is always against the state at the moment of the tap, not against whatever the screen
 * loaded when it opened.
 */
@Singleton
class SetDayStartMinuteUseCase @Inject constructor(
    private val preferences: NudgePreferences,
    private val snapshots: RuleSnapshots
) {

    /**
     * True when saving [minute] must pass the unlock challenge first. An unchanged value never
     * challenges, whatever Strict Mode says; otherwise the decision is
     * [SettingsWeakening.requiresDayStartUnlock], which also requires an enabled first-unlock rule
     * on this phone and Strict Mode being on.
     */
    suspend fun requiresUnlock(minute: Int): Boolean {
        val currentMinute = preferences.dayStartMinute.first()
        if (currentMinute == minute) return false
        val hasEnabledFirstUnlockRule = snapshots.enabledRules().any { it.firstUnlockMinutes != null }
        return SettingsWeakening.requiresDayStartUnlock(
            currentMinute = currentMinute,
            newMinute = minute,
            hasEnabledFirstUnlockRule = hasEnabledFirstUnlockRule,
            strictModeEnabled = preferences.isStrictModeEnabled.first()
        )
    }

    /**
     * Writes [minute], clamped to a real minute of the day. The clamp is here rather than at each
     * editor so an out-of-range value from a picker or an imported setting cannot land in
     * DataStore; [com.astraedus.nudge.domain.engine.FirstUnlockWindow] clamps again when it reads
     * the value, but the stored setting should already be valid.
     */
    suspend fun execute(minute: Int) {
        preferences.setDayStartMinute(minute.coerceIn(0, MINUTES_PER_DAY - 1))
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
