package com.astraedus.nudge.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class NudgeDatabaseMigrationTest {

    @Test
    fun `MIGRATION_2_3 adds userChangedMind column`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_2_3.migrate(db.proxy)

        assertEquals(
            listOf("ALTER TABLE usage_events ADD COLUMN userChangedMind INTEGER NOT NULL DEFAULT 0"),
            db.sql
        )
    }

    @Test
    fun `MIGRATION_3_4 adds showCounter column`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_3_4.migrate(db.proxy)

        assertEquals(
            listOf("ALTER TABLE block_rules ADD COLUMN showCounter INTEGER NOT NULL DEFAULT 0"),
            db.sql
        )
    }

    @Test
    fun `MIGRATION_5_6 adds showTimeRemaining and autoKickCooldownSeconds columns`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_5_6.migrate(db.proxy)

        assertEquals(
            listOf(
                "ALTER TABLE block_rules ADD COLUMN showTimeRemaining INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE block_rules ADD COLUMN autoKickCooldownSeconds INTEGER NOT NULL DEFAULT 60"
            ),
            db.sql
        )
    }

    @Test
    fun `all migrations registered from version 1 to current`() {
        val allMigrations = listOf(
            NudgeDatabase.MIGRATION_1_2,
            NudgeDatabase.MIGRATION_2_3,
            NudgeDatabase.MIGRATION_3_4,
            NudgeDatabase.MIGRATION_4_5,
            NudgeDatabase.MIGRATION_5_6
        )

        val currentVersion = 6

        // Every version gap from 1 to current must have a migration
        for (v in 1 until currentVersion) {
            val found = allMigrations.any { it.startVersion == v && it.endVersion == v + 1 }
            assert(found) {
                "Missing migration from version $v to ${v + 1}! " +
                    "Add MIGRATION_${v}_${v + 1} to NudgeDatabase and register it in DatabaseModule."
            }
        }

        assertEquals(
            "Migration count should equal version gaps",
            currentVersion - 1,
            allMigrations.size
        )
    }

    private class RecordingDatabase : InvocationHandler {
        val sql = mutableListOf<String>()

        val proxy: SupportSQLiteDatabase = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            this
        ) as SupportSQLiteDatabase

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "execSQL") {
                sql += args?.firstOrNull() as String
                return null
            }

            return when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }
    }
}
