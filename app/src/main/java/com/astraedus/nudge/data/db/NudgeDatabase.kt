package com.astraedus.nudge.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.dao.UsageEventDao
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.db.entity.UsageEvent

@Database(
    entities = [
        BlockRule::class,
        AppGroup::class,
        AppGroupMember::class,
        UsageEvent::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NudgeDatabase : RoomDatabase() {
    abstract fun blockRuleDao(): BlockRuleDao
    abstract fun appGroupDao(): AppGroupDao
    abstract fun usageEventDao(): UsageEventDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Schedule-based rules
                db.execSQL("ALTER TABLE block_rules ADD COLUMN scheduleDays TEXT")
                db.execSQL("ALTER TABLE block_rules ADD COLUMN scheduleStartMinute INTEGER")
                db.execSQL("ALTER TABLE block_rules ADD COLUMN scheduleEndMinute INTEGER")
                // In-app feature blocking
                db.execSQL("ALTER TABLE block_rules ADD COLUMN inAppFeatures TEXT")
                // Grayscale mode
                db.execSQL("ALTER TABLE block_rules ADD COLUMN grayscale INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
