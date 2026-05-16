package com.astraedus.nudge.di

import android.content.Context
import androidx.room.Room
import com.astraedus.nudge.data.db.NudgeDatabase
import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.dao.UsageEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NudgeDatabase {
        return Room.databaseBuilder(
            context,
            NudgeDatabase::class.java,
            "nudge.db"
        ).addMigrations(
            NudgeDatabase.MIGRATION_1_2,
            NudgeDatabase.MIGRATION_2_3,
            NudgeDatabase.MIGRATION_3_4,
            NudgeDatabase.MIGRATION_4_5
        ).build()
    }

    @Provides
    fun provideBlockRuleDao(db: NudgeDatabase): BlockRuleDao = db.blockRuleDao()

    @Provides
    fun provideAppGroupDao(db: NudgeDatabase): AppGroupDao = db.appGroupDao()

    @Provides
    fun provideUsageEventDao(db: NudgeDatabase): UsageEventDao = db.usageEventDao()
}
