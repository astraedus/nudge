package com.astraedus.nudge.di

import android.content.Context
import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.dao.UsageEventDao
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.ui.screens.stats.StatsCalculator
import com.astraedus.nudge.util.NudgeLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideBlockRuleRepository(
        blockRuleDao: BlockRuleDao,
        appGroupDao: AppGroupDao
    ): BlockRuleRepository = BlockRuleRepository(blockRuleDao, appGroupDao)

    @Provides
    @Singleton
    fun provideUsageRepository(
        @ApplicationContext context: Context,
        usageEventDao: UsageEventDao,
        timeTracker: TimeTracker
    ): UsageRepository = UsageRepository(context, usageEventDao, timeTracker)

    @Provides
    @Singleton
    fun provideNudgePreferences(
        @ApplicationContext context: Context
    ): NudgePreferences = NudgePreferences(context)

    @Provides
    @Singleton
    fun provideInstalledAppsRepository(
        @ApplicationContext context: Context
    ): InstalledAppsRepository = InstalledAppsRepository(context)

    @Provides
    fun provideScheduleEvaluator(): ScheduleEvaluator = ScheduleEvaluator()

    @Provides
    fun provideBlockEngine(
        scheduleEvaluator: ScheduleEvaluator,
        nudgeLog: NudgeLog
    ): BlockEngine = BlockEngine(scheduleEvaluator, nudgeLog)

    @Provides
    @Singleton
    fun provideNudgeLog(logger: NudgeLogger): NudgeLog = logger

    @Provides
    fun provideRuleEvaluator(): RuleEvaluator = RuleEvaluator()

    @Provides
    fun provideTimeTracker(): TimeTracker = TimeTracker()

    @Provides
    fun provideStatsCalculator(timeTracker: TimeTracker): StatsCalculator = StatsCalculator(timeTracker)
}
