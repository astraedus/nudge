package com.astraedus.nudge.di

import android.content.Context
import com.astraedus.nudge.data.preferences.NudgePreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The Hilt-built [NudgePreferences], for composables that have no ViewModel to inject it.
 *
 * `NudgePreferences(context)` is constructible by hand, and several screens did exactly that. For
 * a screen that only READS, that is harmless — DataStore is process-wide, so two instances see the
 * same values.
 *
 * It stopped being harmless when preferences grew a collaborator. `NudgePreferences` now takes a
 * [com.astraedus.nudge.domain.widget.WidgetRefreshSignal] and pushes it after every write a
 * home-screen widget renders. That parameter defaults to `NONE`, so a hand-built instance still
 * compiles and still writes correctly — and silently pushes nothing. The Settings screen's Strict
 * Mode toggle is the only way a user turns the commitment lock on, and it was doing precisely
 * that: the fix to `setStrictModeEnabled` was real, correct, and unreachable from the one path
 * anybody actually takes.
 *
 * So a screen that WRITES a widget-visible preference reaches the singleton through here instead.
 * `WidgetRefreshCoverageContractTest` pins it, because "constructs its own instance" is a shape no
 * value-level test can see.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PreferencesEntryPoint {

    fun nudgePreferences(): NudgePreferences

    companion object {
        /** The application-scoped [NudgePreferences], wired with the live widget refresh signal. */
        fun preferences(context: Context): NudgePreferences =
            EntryPointAccessors
                .fromApplication(context.applicationContext, PreferencesEntryPoint::class.java)
                .nudgePreferences()
    }
}
