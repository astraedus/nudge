package com.astraedus.nudge.ui.nuke

import com.astraedus.nudge.domain.nuke.NukeClock
import com.astraedus.nudge.domain.nuke.NukeEmergencyCode
import com.astraedus.nudge.domain.nuke.NukeKey
import com.astraedus.nudge.domain.nuke.NukePolicy
import com.astraedus.nudge.domain.nuke.NukeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Nuke unlock dialog is showing.
 *
 * @property prompt what is being unlocked ("End Nuke", "Turn off all blocking").
 * @property emergencyTarget the 64-character code the user is typing, or null while they are still
 *   choosing between scanning and typing. A FRESH code per attempt: every entry into typing mode
 *   generates a new one, so a code seen once is worth nothing the next time.
 * @property error the last thing that went wrong ("That's not your Nuke code"), or null.
 */
data class NukeUnlockState(
    val prompt: String,
    val emergencyTarget: String? = null,
    val error: String? = null
)

/**
 * ViewModel-side gate for everything that WEAKENS Nuke while it is on: ending it, taking an app off
 * the list, changing or removing the key, and turning the master toggle off. The Nuke sibling of
 * [com.astraedus.nudge.ui.lock.StrictModeGate], and deliberately the same shape: [run] either runs
 * the action now or stashes it and surfaces [unlock] for the screen's dialog.
 *
 * The two ways through are the paired key ([onScanned]) and the emergency code ([verifyEmergency]).
 * WHICH changes weaken is not decided here — the caller asks
 * [com.astraedus.nudge.domain.nuke.NukePolicy] and passes the answer, so the policy has one home and
 * a JVM test.
 *
 * A Nuke started with a timer (#184) refuses the paired key, for EVERY action here, until the timer
 * has run out ([NukePolicy.keyWaitMs]): the right key just says how long is left. The emergency code
 * is never affected; it is the way out that always exists.
 *
 * @param currentState reads the LIVE persisted state at unlock time (never a snapshot from when the
 *   dialog opened: the key could have been re-paired in between).
 * @param clock the timer's clock, read at scan time.
 */
class NukeGate(
    private val currentState: suspend () -> NukeState,
    private val clock: NukeClock,
    private val newEmergencyCode: () -> String = { NukeEmergencyCode.generate() }
) {
    private val _unlock = MutableStateFlow<NukeUnlockState?>(null)
    val unlock: StateFlow<NukeUnlockState?> = _unlock.asStateFlow()

    private var pendingAction: (suspend () -> Unit)? = null

    /**
     * Run [action] now if it does not [weaken] Nuke, or stash it behind the unlock dialog if it does.
     */
    suspend fun run(prompt: String, weaken: Boolean, action: suspend () -> Unit) {
        if (!weaken) {
            action()
            return
        }
        pendingAction = action
        _unlock.value = NukeUnlockState(prompt = prompt)
    }

    /**
     * Stash [action] behind the emergency code directly, skipping the scan-or-type choice. For the
     * "End without your code" button, whose whole meaning is "I do not have the key".
     */
    fun requireEmergencyCode(prompt: String, action: suspend () -> Unit) {
        pendingAction = action
        _unlock.value = NukeUnlockState(prompt = prompt, emergencyTarget = newEmergencyCode())
    }

    /**
     * The user scanned something from the unlock dialog. The paired key runs the stashed action; any
     * other code leaves everything as it was and says so. A cancelled scan (null/blank) changes
     * nothing and says nothing.
     *
     * @return true when the action ran.
     */
    suspend fun onScanned(payload: String?): Boolean {
        val active = _unlock.value ?: return false
        if (payload.isNullOrBlank()) return false
        val state = currentState()
        if (!NukeKey.matches(payload, state.keyHash)) {
            _unlock.value = active.copy(error = WRONG_KEY_MESSAGE)
            return false
        }
        val waitMs = NukePolicy.keyWaitMs(state, clock.now())
        if (waitMs > 0) {
            _unlock.value = active.copy(error = NukeTimerCopy.refusal(waitMs))
            return false
        }
        return release()
    }

    /** Switch the open dialog to typing a fresh emergency code. */
    fun useEmergencyCode() {
        val active = _unlock.value ?: return
        _unlock.value = active.copy(emergencyTarget = newEmergencyCode(), error = null)
    }

    /** Back from typing to the scan-or-type choice (the code is discarded; the next one is new). */
    fun backToChoice() {
        val active = _unlock.value ?: return
        _unlock.value = active.copy(emergencyTarget = null, error = null)
    }

    /**
     * Verify the typed emergency code. An exact match runs the stashed action.
     *
     * @return true when the action ran.
     */
    suspend fun verifyEmergency(input: String): Boolean {
        val target = _unlock.value?.emergencyTarget ?: return false
        if (!NukeEmergencyCode.verify(input, target)) return false
        return release()
    }

    /** Backed out. The stashed action never runs. */
    fun cancel() {
        _unlock.value = null
        pendingAction = null
    }

    private suspend fun release(): Boolean {
        val action = pendingAction
        _unlock.value = null
        pendingAction = null
        action?.invoke()
        return action != null
    }

    companion object {
        const val WRONG_KEY_MESSAGE = "That's not your Nuke code."
    }
}
