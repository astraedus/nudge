package com.astraedus.nudge.service

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.nuke.NukeClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folds a running Nuke timer's segment into its stored count (#184), so a hard power-off or a crash
 * loses at most the time since the last call. Called on the screen going off and on shutdown from
 * [NudgeAccessibilityService], and on opening the Nuke screen. A no-op with no timer running (and
 * DataStore skips a write that changes nothing).
 *
 * Losing a checkpoint only ever makes the timer run LONGER than asked, never shorter; the model and
 * its limits are in `docs/architecture/nuke-mode.md`.
 */
@Singleton
class NukeTimerCheckpoint @Inject constructor(
    private val preferences: NudgePreferences,
    private val clock: NukeClock
) {
    suspend fun checkpoint() {
        preferences.checkpointNukeTimer(clock.now())
    }
}
