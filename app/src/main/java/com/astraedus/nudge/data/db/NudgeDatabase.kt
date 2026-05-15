package com.astraedus.nudge.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false
)
abstract class NudgeDatabase : RoomDatabase() {
    abstract fun blockRuleDao(): BlockRuleDao
    abstract fun appGroupDao(): AppGroupDao
    abstract fun usageEventDao(): UsageEventDao
}
