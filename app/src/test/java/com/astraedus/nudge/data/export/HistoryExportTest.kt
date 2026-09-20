package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.isShownConfrontation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The FILE FORMAT contract for usage history.
 *
 * ## Why this is still envelope `version: 1`
 *
 * The envelope has always been read by KNOWN KEY ONLY -- `version`, then `rules` and `groups` via
 * `optJSONArray` -- so an unknown top-level key has never been able to fail an import. Bumping to
 * version 2 would instead make every older Nudge reject the whole file ("newer than supported") and
 * cost the user their RULES to deliver history they cannot use. So history rides as an optional key
 * at version 1, and `unknown top-level keys are ignored` below is the guard that keeps that true.
 */
class HistoryExportTest {

    private lateinit var exporter: RuleExporter

    @Before
    fun setUp() {
        exporter = RuleExporter()
    }

    private fun event(
        pkg: String = "com.instagram.android",
        ts: Long,
        blocked: Boolean = true,
        mode: String? = "DELAY",
        changedMind: Boolean = false
    ) = UsageEvent(
        id = ts, // deliberately non-zero: local ids must not survive the trip
        packageName = pkg,
        timestamp = ts,
        wasBlocked = blocked,
        blockMode = mode,
        userChangedMind = changedMind
    )

    private fun export(history: List<UsageEvent>): String = exporter.exportRules(
        rules = listOf(BlockRule(id = 1, packageName = "com.instagram.android", mode = "DELAY")),
        groups = emptyList(),
        groupMembers = emptyMap(),
        history = history
    )

    // --- Round trip ------------------------------------------------------------------------

    @Test
    fun `history survives an export-import round trip field for field`() {
        val source = listOf(
            event(ts = 1_700_000_000_000, mode = "DELAY"),
            event(ts = 1_700_000_001_000, mode = "DELAY", changedMind = true),
            event(pkg = "com.google.android.youtube", ts = 1_700_000_002_000, mode = "HARD_BLOCK"),
            event(pkg = "com.zhiliaoapp.musically", ts = 1_700_000_003_000, blocked = false, mode = null)
        )

        val result = exporter.importRules(export(source))

        assertNull(result.error)
        assertEquals(1, result.version)
        assertEquals(source.map(HistoryMerge::toExported), result.history)
        assertEquals(0, result.invalidHistoryCount)
    }

    @Test
    fun `the exported file is valid JSON at version 1 with history as a top-level array`() {
        val json = JSONObject(export(listOf(event(ts = 1000), event(ts = 2000))))

        assertEquals(1, json.getInt("version"))
        assertEquals(2, json.getJSONArray("history").length())
        assertEquals(1, json.getJSONArray("rules").length())
        assertNotNull(json.getJSONArray("groups"))
    }

    @Test
    fun `package names needing JSON escaping survive`() {
        val source = listOf(event(pkg = """weird."pkg"\name""", ts = 1000))

        val result = exporter.importRules(export(source))

        assertNull(result.error)
        assertEquals("""weird."pkg"\name""", result.history.single().packageName)
    }

    /**
     * The history array is appended by finding the envelope's closing brace, so a `}` sitting
     * inside a STRING value is the adversarial case for that surgery. It is safe by construction --
     * the root's own brace is always the LAST one in the document -- and this pins it, because
     * getting it wrong would corrupt the file rather than fail a test somewhere obvious.
     */
    @Test
    fun `a closing brace inside a rule value cannot break the splice`() {
        val json = exporter.exportRules(
            rules = listOf(
                BlockRule(
                    id = 1,
                    packageName = "com.app.with}brace",
                    mode = "DELAY",
                    webDomains = "weird.example.com/}"
                )
            ),
            groups = emptyList(),
            groupMembers = emptyMap(),
            history = listOf(event(pkg = "com.app.with}brace", ts = 1000))
        )

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals("com.app.with}brace", result.rules.single().packageName)
        assertEquals("weird.example.com/}", result.rules.single().webDomains)
        assertEquals("com.app.with}brace", result.history.single().packageName)
    }

    /** Nothing to export means no `history` key at all -- byte-identical to a pre-history backup. */
    @Test
    fun `an export with no history writes no history key`() {
        val json = export(emptyList())

        assertFalse(json.contains("history"))
        assertFalse(JSONObject(json).has("history"))
        assertTrue(exporter.importRules(json).history.isEmpty())
    }

    // --- Formatting ------------------------------------------------------------------------

    /**
     * Rules stay pretty-printed (a backup a human can read was the point) while history is dense.
     * History is machine data with an unbounded row count, so indenting it would multiply the file
     * size for nobody's benefit.
     */
    @Test
    fun `rules stay pretty-printed while history is written compactly`() {
        val json = exporter.exportRules(
            rules = listOf(
                BlockRule(id = 1, packageName = "com.app1", mode = "DELAY"),
                BlockRule(id = 2, packageName = "com.app2", mode = "HARD_BLOCK")
            ),
            groups = emptyList(),
            groupMembers = emptyMap(),
            history = (1..50L).map { event(ts = it * 1000) }
        )

        val historyStart = json.indexOf("\"history\"")
        val envelope = json.substring(0, historyStart)
        val historyBlock = json.substring(historyStart)

        // History: one line, and no whitespace padding around the separators.
        assertFalse(
            "the history array must be a single line, was:\n${historyBlock.take(200)}",
            historyBlock.substringAfter('[').substringBeforeLast(']').contains('\n')
        )
        assertTrue(historyBlock.contains("{\"packageName\":\""))
        // Rules: still indented and spaced, exactly as a hand-readable backup always was.
        assertTrue(envelope.contains("\n  \"rules\": ["))
        assertTrue(envelope.contains("\"packageName\": \"com.app1\""))
    }

    /**
     * A heavy user's history is unbounded (retention has no call site), so the per-event cost of
     * the format is the whole ballgame. ~100 bytes/event; pretty-printing would be 4-5x that.
     */
    @Test
    fun `a large history stays compact and still round-trips exactly`() {
        val source = (1..10_000L).map {
            event(ts = 1_700_000_000_000 + it * 1000, changedMind = it % 4 == 0L)
        }

        val json = export(source)
        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(10_000, result.history.size)
        assertEquals(source.map(HistoryMerge::toExported), result.history)
        val bytesPerEvent = json.length / source.size
        assertTrue("$bytesPerEvent bytes/event is not compact", bytesPerEvent < 150)
    }

    // --- Backward and forward compatibility -------------------------------------------------

    /**
     * The version-1 promise: a rules-only file written by any older Nudge imports exactly as it
     * always did, and simply carries no history.
     */
    @Test
    fun `a version 1 file without history imports exactly as before`() {
        val result = exporter.importRules(
            """
            {
                "version": 1,
                "exportedAt": 1000,
                "rules": [
                    {"packageName": "com.app1", "mode": "DELAY", "delaySeconds": 30},
                    {"packageName": "com.app2", "mode": "HARD_BLOCK"}
                ],
                "groups": [{"name": "Social", "members": ["com.instagram.android"]}]
            }
            """.trimIndent()
        )

        assertNull(result.error)
        assertEquals(2, result.rules.size)
        assertEquals(1, result.groups.size)
        assertTrue(result.history.isEmpty())
        assertEquals(0, result.invalidCount)
        assertEquals(0, result.invalidHistoryCount)
    }

    /**
     * The reason history could be added WITHOUT a version bump: the envelope is read by known key
     * only. This guards that property in both directions -- a future key added by a newer Nudge
     * must not fail this build either.
     */
    @Test
    fun `unknown top-level keys are ignored rather than failing the file`() {
        val result = exporter.importRules(
            """
            {
                "version": 1,
                "rules": [{"packageName": "com.app1", "mode": "DELAY"}],
                "history": [{"packageName": "com.app1", "timestamp": 5, "wasBlocked": true}],
                "screenTime": {"com.app1": 12345},
                "futureFeature": [1, 2, 3]
            }
            """.trimIndent()
        )

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertEquals(1, result.history.size)
        assertEquals(0, result.invalidCount)
        assertEquals(0, result.invalidHistoryCount)
    }

    @Test
    fun `an explicitly null history key is treated as absent`() {
        val result = exporter.importRules(
            """{"version": 1, "rules": [{"packageName": "com.a", "mode": "DELAY"}], "history": null}"""
        )

        assertNull(result.error)
        assertTrue(result.history.isEmpty())
    }

    /** Same rule as `rules` and `groups`: a present-but-wrong-shaped key is not a Nudge export. */
    @Test
    fun `a history key that is not an array fails loudly`() {
        val result = exporter.importRules(
            """{"version": 1, "rules": [{"packageName": "com.a", "mode": "DELAY"}], "history": 7}"""
        )

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("history"))
    }

    // --- Per-entry tolerance ----------------------------------------------------------------

    @Test
    fun `unreadable history entries are skipped and counted, never fatal`() {
        val result = exporter.importRules(
            """
            {
                "version": 1,
                "rules": [{"packageName": "com.app1", "mode": "DELAY"}],
                "history": [
                    {"packageName": "com.app1", "timestamp": 1000, "wasBlocked": true},
                    {"timestamp": 2000, "wasBlocked": true},
                    {"packageName": "com.app1"},
                    {"packageName": "com.app1", "timestamp": "yesterday"},
                    {"packageName": "   ", "timestamp": 3000},
                    {"packageName": "com.app1", "timestamp": 0},
                    {"packageName": "com.app1", "timestamp": 4000, "wasBlocked": "yes"},
                    {"packageName": "com.app1", "timestamp": 5000, "blockMode": 7},
                    "not an object",
                    12345,
                    {"packageName": "com.app2", "timestamp": 6000, "userChangedMind": true}
                ]
            }
            """.trimIndent()
        )

        assertNull("a corrupt event must never cost the user their rules", result.error)
        assertEquals(1, result.rules.size)
        assertEquals(listOf(1000L, 6000L), result.history.map { it.timestamp })
        assertEquals(9, result.invalidHistoryCount)
        assertEquals("rule skips stay their own tally", 0, result.invalidCount)
    }

    /**
     * `"wasBlocked": "yes"` is REJECTED rather than coerced to false. A dropped event dents a
     * statistic; a silently mis-read one corrupts it, and these rows are the stat tiles.
     */
    @Test
    fun `a wrongly typed flag skips the event instead of guessing a value`() {
        val result = exporter.importRules(
            """
            {
                "version": 1,
                "rules": [],
                "history": [
                    {"packageName": "com.app1", "timestamp": 1000, "userChangedMind": "yes"}
                ]
            }
            """.trimIndent()
        )

        assertTrue(result.history.isEmpty())
        assertEquals(1, result.invalidHistoryCount)
    }

    @Test
    fun `history skip reasons name the offending entry and stay bounded`() {
        val bad = (1..200).joinToString(",") { """{"timestamp": $it}""" }
        val result = exporter.importRules(
            """{"version": 1, "rules": [{"packageName": "com.a", "mode": "DELAY"}], "history": [$bad]}"""
        )

        assertNull(result.error)
        assertEquals(200, result.invalidHistoryCount)
        assertTrue(
            "reasons must be capped, was ${result.invalidHistoryReasons.size}",
            result.invalidHistoryReasons.size in 1..20
        )
        assertTrue(result.invalidHistoryReasons.first().startsWith("History event 1:"))
        assertTrue(result.invalidHistoryReasons.first().contains("packageName"))
    }

    @Test
    fun `optional fields default the way the entity does`() {
        val result = exporter.importRules(
            """
            {
                "version": 1,
                "rules": [],
                "history": [{"packageName": "com.app1", "timestamp": 1000}]
            }
            """.trimIndent()
        )

        val restored = HistoryMerge.toEntity(result.history.single())
        assertFalse(restored.wasBlocked)
        assertFalse(restored.userChangedMind)
        assertNull(restored.blockMode)
    }

    /**
     * A file that is nothing but unreadable history is still a total failure -- "Imported: 0" with
     * a cheerful tick would hide that the user got nothing back.
     */
    @Test
    fun `a file whose only content is unreadable history fails loudly`() {
        val result = exporter.importRules(
            """{"version": 1, "rules": [], "history": [{"timestamp": 1}, {"timestamp": 2}]}"""
        )

        assertNotNull(result.error)
        assertEquals(2, result.invalidHistoryCount)
    }

    // --- The numbers the user actually sees --------------------------------------------------

    /**
     * The point of the whole feature: the counts behind the home tiles are reproduced exactly from
     * a restored file. Both rows of a walk-away have to come back -- the tile predicate
     * (`UsageEventDao.getAllTimeShownCount`, checked last) is only right if the raw rows it reads
     * are intact, so the raw counts are asserted too.
     */
    @Test
    fun `blocked and walked-away counts survive the round trip`() {
        val source = buildList {
            repeat(19) { add(event(ts = 1_000L + it, blocked = true)) }
            repeat(7) { add(event(ts = 9_000L + it, blocked = true, changedMind = true)) }
            repeat(5) { add(event(ts = 20_000L + it, blocked = false, mode = null)) }
        }
        val sourceBlocked = source.count { it.wasBlocked }
        val sourceWalkAways = source.count { it.userChangedMind }

        val restored = exporter.importRules(export(source)).history.map(HistoryMerge::toEntity)

        assertEquals(source.size, restored.size)
        assertEquals(sourceBlocked, restored.count { it.wasBlocked })
        assertEquals(sourceWalkAways, restored.count { it.userChangedMind })
        assertEquals(26, restored.count { it.wasBlocked })
        assertEquals(7, restored.count { it.userChangedMind })
        // What the home tile shows for this file: 26 rows carry `wasBlocked`, 7 of them are the
        // walk-away row, so the tile reads 19 -- never 26.
        assertEquals(19, restored.count { it.isShownConfrontation })
    }

    @Test
    fun `imported rows carry no local ids from the exporting device`() {
        val restored = exporter.importRules(export(listOf(event(ts = 1000), event(ts = 2000))))
            .history
            .map(HistoryMerge::toEntity)

        assertTrue(restored.all { it.id == 0L })
    }
}
