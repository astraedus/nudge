package com.astraedus.nudge.data.export

import com.astraedus.nudge.testing.SourceScan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nuke Mode never leaves the phone in a backup, and a backup can never change Nuke.
 *
 * Two reasons, both about the threat model in `docs/architecture/nuke-mode.md`:
 *  - An EXPORT carrying the key hash or the list would put the thing that ends Nuke in a plain,
 *    shareable JSON file (the hash is not the key, but a file that could restore "Nuke off, no key"
 *    is a one-tap way out of it on the next import).
 *  - An IMPORT is the one write path that bypasses every screen. A hand-edited file saying
 *    `"nukeActive": false` would end Nuke without the key or the emergency code; one saying
 *    `"nukeKeyHash": "<hash of something in my pocket>"` would swap the key.
 *
 * Layers: L4-style (the real exporter over the real file format) for the round trip, and
 * source-level for the preference writer, which is not JVM-constructible — the same split
 * `SettingsExportTest` and `ImportedSettingsWriteContractTest` use.
 */
class NukeExportExclusionTest {

    private val exporter = RuleExporter()

    @Test
    fun `the settings a backup carries have no Nuke field`() {
        val fields = ExportedSettings::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("ExportedSettings must be inspectable", fields.isNotEmpty())
        assertFalse("no Nuke field may ride in a backup: $fields", fields.any { "nuke" in it })
    }

    @Test
    fun `an exported file says nothing about Nuke`() {
        val json = exporter.exportRules(
            rules = emptyList(),
            groups = emptyList(),
            groupMembers = emptyMap(),
            settings = ExportedSettings(strictModeEnabled = true, emergencyPassEnabled = false)
        )
        assertFalse(json.lowercase().contains("nuke"))
    }

    @Test
    fun `a hand-edited file carrying Nuke keys changes nothing an import can apply`() {
        val file = JSONObject()
            .put("version", 1)
            .put("rules", org.json.JSONArray())
            .put(
                "settings",
                JSONObject()
                    .put("strictModeEnabled", true)
                    .put("nukeActive", false)
                    .put("nuke_active", false)
                    .put("nukePackages", org.json.JSONArray().put("com.instagram.android"))
                    .put("nukeKeyHash", "0".repeat(64))
            )
            // And at the top level, for good measure.
            .put("nuke", JSONObject().put("active", false))
            .toString()

        val result = exporter.importRules(file)

        assertEquals(null, result.error)
        assertNotNull(result.settings)
        // The only thing the import can apply is the one real setting the file carried.
        assertEquals(ExportedSettings(strictModeEnabled = true), result.settings)
    }

    @Test
    fun `the preference writer an import uses cannot reach a Nuke key`() {
        val source =
            SourceScan.kotlinFile("com/astraedus/nudge/data/preferences/NudgePreferences.kt").readText()
        val start = source.indexOf("suspend fun applyImportedSettings(")
        assertTrue("applyImportedSettings must exist", start >= 0)
        val body = source.substring(start, source.indexOf("\n    }\n", start))
        assertFalse("an import must never write Nuke state", body.contains("NUKE_"))
        assertFalse(body.lowercase().contains("nuke"))

        val exportStart = source.indexOf("suspend fun exportableSettings()")
        val exportBody = source.substring(exportStart, start)
        assertFalse("an export must never read Nuke state", exportBody.lowercase().contains("nuke"))
    }

    /**
     * Profiles (#183) and the timer (#184) are Nuke state too. A file carrying a timer could restore
     * "a timer that has already run out" over a running one, and profiles name THIS phone's apps.
     */
    @Test
    fun `a hand-edited file carrying Nuke profiles or a timer changes nothing an import can apply`() {
        val file = JSONObject()
            .put("version", 1)
            .put("rules", org.json.JSONArray())
            .put(
                "settings",
                JSONObject()
                    .put("emergencyPassEnabled", false)
                    .put("nukeProfiles", org.json.JSONArray().put(JSONObject().put("name", "x")))
                    .put("nuke_profiles", "[]")
                    .put("nukeTimerDurationMs", 0)
                    .put("nuke_timer_consumed_ms", Long.MAX_VALUE)
                    .put("nukeTimerChoiceMinutes", 120)
            )
            .toString()

        val result = exporter.importRules(file)

        assertEquals(null, result.error)
        assertEquals(ExportedSettings(emergencyPassEnabled = false), result.settings)
    }

    /**
     * Every Nuke key, including ones added after this test, stays out of both halves of a backup.
     * The key list is read from the `Keys` object itself, and must include the profile and timer
     * keys, so the scan can never pass by finding no keys at all.
     */
    @Test
    fun `no Nuke key, present or future, is read by an export or written by an import`() {
        val source =
            SourceScan.kotlinFile("com/astraedus/nudge/data/preferences/NudgePreferences.kt").readText()
        val keysStart = source.indexOf("private object Keys {")
        assertTrue("the Keys object must exist", keysStart >= 0)
        val keysBlock = source.substring(keysStart, source.indexOf("\n    }\n", keysStart))
        val nukeKeys = Regex("""val (NUKE_[A-Z_]+) =""").findAll(keysBlock).map { it.groupValues[1] }.toSet()
        assertTrue(
            "the profile and timer keys must be among them: $nukeKeys",
            nukeKeys.containsAll(
                setOf(
                    "NUKE_ACTIVE", "NUKE_PACKAGES", "NUKE_KEY_HASH", "NUKE_PROFILES",
                    "NUKE_TIMER_DURATION_MS", "NUKE_TIMER_CONSUMED_MS", "NUKE_TIMER_BOOT_COUNT",
                    "NUKE_TIMER_SEGMENT_START_MS", "NUKE_TIMER_CHOICE_MINUTES"
                )
            )
        )

        val exportStart = source.indexOf("suspend fun exportableSettings()")
        val importStart = source.indexOf("suspend fun applyImportedSettings(")
        val backupCode = source.substring(exportStart, importStart) +
            source.substring(importStart, source.indexOf("\n    }\n", importStart))
        nukeKeys.forEach { key ->
            assertFalse("$key must never ride in a backup", backupCode.contains("Keys.$key"))
        }
    }
}
