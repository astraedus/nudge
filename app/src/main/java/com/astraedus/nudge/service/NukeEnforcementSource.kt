package com.astraedus.nudge.service

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.nuke.NukeEnforcement
import com.astraedus.nudge.domain.nuke.NukePolicy
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The production [NukeEnforcement]: the persisted state, through [NukePolicy.isNuked], with this
 * device's [EssentialApps]. Reads the essentials only when Nuke is actually on, so an idle Nuke costs
 * the evaluation hot path one cached DataStore read.
 */
@Singleton
class NukeEnforcementSource @Inject constructor(
    private val preferences: NudgePreferences,
    private val essentials: EssentialApps
) : NukeEnforcement {
    override suspend fun isNuked(packageName: String): Boolean {
        val state = preferences.nukeState.first()
        if (!state.active) return false
        return NukePolicy.isNuked(packageName, state, essentials.packages())
    }
}
