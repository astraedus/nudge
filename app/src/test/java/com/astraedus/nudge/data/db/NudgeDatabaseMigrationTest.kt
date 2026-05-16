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
