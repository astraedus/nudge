package com.astraedus.nudge.ui.lock

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Reusable ViewModel-side gate for Strict Mode weakening actions.
 *
 * Wiring pattern (kept in-ViewModel, no nav-result plumbing): a ViewModel owns one [StrictModeGate],
 * exposes its [challenge] StateFlow to the screen, and routes every weakening action through
 * [run]. When Strict Mode is off, the action runs immediately. When on, a fresh challenge is
 * generated, the action is stashed, and the screen shows the
 * [com.astraedus.nudge.ui.components.ChallengeDialog]; [verifyAndRun] runs it on success,
 * [cancel] discards it.
 *
 * Single source of this logic so HomeViewModel / config / active-rules / settings stay consistent
 * (anti-duplication).
 */
class StrictModeGate(
    private val preferences: NudgePreferences
) {
    private val _challenge = MutableStateFlow<ChallengeState?>(null)
    val challenge: StateFlow<ChallengeState?> = _challenge.asStateFlow()

    /** A weakening action, deferred until the challenge passes. */
    private var pendingAction: (suspend () -> Unit)? = null

    /**
     * Run a weakening [action]. If Strict Mode is OFF, runs it now. If ON, generates a fresh
     * challenge (difficulty from prefs), stashes [action], and surfaces [challenge] for the screen.
     *
     * @param prompt short description of what is being unlocked, shown in the dialog.
     */
    suspend fun run(prompt: String, action: suspend () -> Unit) {
        if (!preferences.isStrictModeEnabled.first()) {
            action()
            return
        }
        val length = preferences.strictModeChallengeLength.first()
        pendingAction = action
        _challenge.value = ChallengeState(
            target = StrictModeChallenge.generate(length),
            prompt = prompt
        )
    }

    /**
     * Verify [input] against the active challenge. On exact match, runs and clears the pending
     * action and dismisses the dialog, then returns true. On mismatch (or no active challenge),
     * leaves the dialog up and returns false.
     */
    suspend fun verifyAndRun(input: String): Boolean {
        val active = _challenge.value ?: return false
        if (!StrictModeChallenge.verify(input, active.target)) return false
        val action = pendingAction
        _challenge.value = null
        pendingAction = null
        action?.invoke()
        return true
    }

    /** User backed out: discard the pending action and dismiss the dialog. Action never runs. */
    fun cancel() {
        _challenge.value = null
        pendingAction = null
    }
}
