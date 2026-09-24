package com.astraedus.nudge.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.astraedus.nudge.data.db.entity.UsageEvent
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
    fun `MIGRATION_6_7 adds webDomains column`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_6_7.migrate(db.proxy)

        assertEquals(
            listOf("ALTER TABLE block_rules ADD COLUMN webDomains TEXT DEFAULT NULL"),
            db.sql
        )
    }

    @Test
    fun `MIGRATION_7_8 adds autoKickAfterMinutes column`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_7_8.migrate(db.proxy)

        assertEquals(
            listOf("ALTER TABLE block_rules ADD COLUMN autoKickAfterMinutes INTEGER DEFAULT NULL"),
            db.sql
        )
    }

    @Test
    fun `MIGRATION_7_8 adds a NULLABLE column so existing rules default to time-kick off`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_7_8.migrate(db.proxy)

        // No NOT NULL: pre-migration rows must read back as null (= feature disabled), never as 0
        // (which would mean "kick after 0 minutes" and lock every existing user out of every app).
        val sql = db.sql.single()
        assert(!sql.contains("NOT NULL")) { "autoKickAfterMinutes must stay nullable, got: $sql" }
        assert(sql.contains("DEFAULT NULL")) { "expected explicit DEFAULT NULL, got: $sql" }
    }

    @Test
    fun `MIGRATION_8_9 recreates usage_events without durationMs and keeps every row`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_8_9.migrate(db.proxy)

        assertEquals("expected create/copy/drop/rename, got: ${db.sql}", 4, db.sql.size)
        val (create, copy, drop, rename) = db.sql

        // SQLite < 3.35 has no DROP COLUMN, and minSdk 26 predates it — recreate is the only way.
        assert(create.startsWith("CREATE TABLE IF NOT EXISTS `usage_events_new`")) { create }
        // Must match Room's generated schema for UsageEvent exactly or validation fails on open.
        assert(create.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL")) { create }
        listOf("packageName", "timestamp", "wasBlocked", "blockMode", "userChangedMind")
            .forEach { assert(create.contains("`$it`")) { "missing column $it in: $create" } }
        assert(!create.contains("durationMs")) { "durationMs must not be recreated: $create" }

        // The rows are user data (the stats screen's block/allow history) — never drop-and-start-over.
        assert(copy.startsWith("INSERT INTO `usage_events_new`")) { copy }
        assert(copy.contains("SELECT")) { copy }
        assert(copy.contains("FROM `usage_events`")) { copy }
        assert(!copy.contains("durationMs")) { copy }

        assertEquals("DROP TABLE `usage_events`", drop)
        assertEquals("ALTER TABLE `usage_events_new` RENAME TO `usage_events`", rename)
    }

    @Test
    fun `usage_events entity declares no duration field`() {
        // Class-level guard, not an instance one: this table records block/allow DECISIONS.
        // Any duration-shaped column on it has no write path (issue #22) and every reader that
        // sums it silently reports 0 — which is exactly how the daily-limit bug (#14) shipped.
        val durationFields = UsageEvent::class.java.declaredFields
            .map { it.name }
            .filter { it.contains("duration", ignoreCase = true) }

        assertEquals(
            "usage_events must not carry duration columns — screen time comes from " +
                "UsageStatsManager via ScreenTimeProvider. Found: $durationFields",
            emptyList<String>(),
            durationFields
        )
    }

    @Test
    fun `MIGRATION_9_10 adds a nullable webBlockMode column so existing rules keep inheriting`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_9_10.migrate(db.proxy)

        // Nullable, no NOT NULL: null means "inherit the app-level mode", which is exactly what
        // every pre-migration rule did, so their behaviour is unchanged.
        val addColumn = db.sql.first()
        assertEquals(
            "ALTER TABLE block_rules ADD COLUMN webBlockMode TEXT DEFAULT NULL",
            addColumn
        )
    }

    /**
     * The bug this column exists for (issue #21): a rule with `mode = 'NONE'` and configured
     * `webDomains` enforced NOTHING on those websites, because web blocking ran through the
     * app-level mode. Those rows exist in the wild (the editor kept previously-entered domains
     * when whole-app blocking was switched off), so the migration repairs them instead of leaving
     * a silently-dead block behind. DELAY, not HARD_BLOCK: the rule already carries a
     * delaySeconds, and the editor uses the same fallback when there is no prior blocking choice.
     */
    @Test
    fun `MIGRATION_9_10 repairs NONE-mode rules that had web domains configured`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_9_10.migrate(db.proxy)

        assertEquals("migration should be exactly ALTER + repair UPDATE", 2, db.sql.size)
        val update = db.sql.last()
        assert(update.startsWith("UPDATE block_rules SET webBlockMode = 'DELAY'")) {
            "expected a repair UPDATE, got: $update"
        }
        assert(update.contains("mode = 'NONE'")) { "repair must only touch NONE rules: $update" }
        assert(update.contains("webDomains IS NOT NULL")) {
            "repair must only touch rules that configured web domains: $update"
        }
    }

    @Test
    fun `MIGRATION_10_11 adds tabVanish and followingSteer columns`() {
        val db = RecordingDatabase()

        NudgeDatabase.MIGRATION_10_11.migrate(db.proxy)

        assertEquals(
            listOf(
                "ALTER TABLE block_rules ADD COLUMN tabVanish INTEGER NOT NULL DEFAULT 1",
                "ALTER TABLE block_rules ADD COLUMN followingSteer INTEGER NOT NULL DEFAULT 0"
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
            NudgeDatabase.MIGRATION_5_6,
            NudgeDatabase.MIGRATION_6_7,
            NudgeDatabase.MIGRATION_7_8,
            NudgeDatabase.MIGRATION_8_9,
            NudgeDatabase.MIGRATION_9_10,
            NudgeDatabase.MIGRATION_10_11
        )

        // Read from production, not hand-typed: a hand-kept constant here is exactly the
        // fixture-honesty failure LESSONS 2026-09-21 describes for `PassthroughTest`'s
        // `ownPackageName` -- it agrees with whoever last edited it, not with production, and would
        // keep passing forever after a version bump nobody remembered to make in two places.
        //
        // It is the `NUDGE_DB_VERSION` constant rather than the `@Database` annotation because the
        // annotation is genuinely invisible here: Room declares it with BINARY retention, so
        // `getAnnotation(Database::class.java)` returns null on the JVM and reflecting on it throws.
        // The constant is what the annotation itself is built from, so the two cannot diverge.
        val currentVersion = NUDGE_DB_VERSION

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
