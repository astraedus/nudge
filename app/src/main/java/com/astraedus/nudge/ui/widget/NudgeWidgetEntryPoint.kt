package com.astraedus.nudge.ui.widget

import android.content.Context
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * How the widgets reach the app's singletons.
 *
 * A `GlanceAppWidget` is instantiated by its `AppWidgetProvider`, which the framework constructs
 * reflectively — there is no constructor Hilt can inject into, and `@AndroidEntryPoint` does not
 * apply. This is the documented escape hatch: pull the same `SingletonComponent` bindings the rest
 * of the app uses, so a widget reads through the identical repositories rather than opening its own
 * Room instance or its own DataStore. Two readers of one database is how a widget comes to disagree
 * with the screen it links to.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NudgeWidgetEntryPoint {

    fun usageRepository(): UsageRepository
    fun screenTimeProvider(): ScreenTimeProvider
    fun installedAppsRepository(): InstalledAppsRepository
    fun insightsCalculator(): InsightsCalculator
    fun timeTracker(): TimeTracker
    fun nudgePreferences(): NudgePreferences

    companion object {
        fun from(context: Context): NudgeWidgetEntryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            NudgeWidgetEntryPoint::class.java
        )
    }
}
