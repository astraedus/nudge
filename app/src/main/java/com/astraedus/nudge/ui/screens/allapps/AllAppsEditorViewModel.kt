package com.astraedus.nudge.ui.screens.allapps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The All apps editor (spec 3.4). DAY-0 STUB: lane C builds the real state, load, save and Strict
 * gate. The route argument [ARG_RULE_ID] is the contract: absent, 0 or less opens a new rule.
 */
@HiltViewModel
class AllAppsEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** The rule being edited, or null for a new one. */
    val ruleId: Long? = savedStateHandle.get<Long>(ARG_RULE_ID)?.takeIf { it > 0L }

    companion object {
        const val ARG_RULE_ID = "ruleId"
    }
}
