package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The FILE FORMAT contract for app settings.
 *
 * ## Why this is still envelope `version: 1`
 *
 * The same reason history is (see [HistoryExportTest]): every shipped importer reads the envelope by
 * KNOWN KEY ONLY, so an unknown top-level key has never been able to fail an import, while a bump to
 * version 2 would make every older Nudge reject the whole file as "newer than supported" -- costing
 * the user their RULES in order to deliver settings that build cannot use.
 *
 * ## The two levels of failure, and why they differ
 *
 * A `settings` key that is present but is NOT AN OBJECT fails the envelope loudly, exactly like a
 * `rules`/`groups`/`history` key that is not an array: a top-level key of the wrong SHAPE is
 * evidence about the whole file, not about one entry. A single unreadable KEY INSIDE the object is
 * skipped and counted instead -- issue #20's per-entry isolation, one level down. One bad toggle
 * must never cost the user their custom block messages, let alone the rules in the same file.
 */
class SettingsExportTest {

    private lateinit var exporter: RuleExporter

    @Before
    fun setUp() {
        exporter = RuleExporter()
    }

    /** A fully-populated settings block, the shape a real export always writes. */
    private fun settings() = ExportedSettings(
        contentFilterEnabled = true,
        contentFilterMode = "DELAY",
        contentFilterStrictKeywords = true,
        strictModeEnabled = true,
        strictModeChallengeLength = StrictModeChallenge.LENGTH_HARD,
        emergencyPassEnabled = false,
        customDelayTitles = "Pause.\nWait a moment.",
        customDelaySubtitles = "Is this what you wanted?",
        customHardBlockMessages = "Not today."
    )

    private fun export(
        settings: ExportedSettings? = settings(),
        history: List<UsageEvent> = emptyList(),
        rules: List<BlockRule> = listOf(
            BlockRule(id = 1, packageName = "com.instagram.android", mode = "DELAY")
        )
    ): String = exporter.exportRules(
        rules = rules,
        groups = emptyList(),
        groupMembers = emptyMap(),
        history = history,
        settings = settings
    )

    /** Wraps a hand-written settings body in an otherwise minimal, valid v1 envelope. */
    private fun fileWithSettings(body: String) = """
        {
            "version": 1,
            "rules": [{"packageName": "com.app1", "mode": "DELAY"}],
            "settings": {$body}
        }
    """.trimIndent()

    // --- Round trip ------------------------------------------------------------------------

    @Test
    fun `settings survive an export-import round trip field for field`() {
        val result = exporter.importRules(export())

        assertNull(result.error)
        assertEquals(1, result.version)
        assertEquals(settings(), result.settings)
        assertEquals(0, result.invalidSettingsCount)
    }

    @Test
    fun `the exported file is valid JSON at version 1 with settings as a top-level object`() {
        val json = JSONObject(export())

        assertEquals(1, json.getInt("version"))
        val settings = json.getJSONObject("settings")
        assertEquals(true, settings.getBoolean("contentFilterEnabled"))
        assertEquals("DELAY", settings.getString("contentFilterMode"))
        assertEquals(48, settings.getInt("strictModeChallengeLength"))
        assertEquals("Not today.", settings.getString("customHardBlockMessages"))
        // The other payloads are untouched by its presence.
        assertEquals(1, json.getJSONArray("rules").length())
        assertNotNull(json.getJSONArray("groups"))
    }

    @Test
    fun `settings, rules and history all survive the same file`() {
        val json = export(
            history = listOf(
                UsageEvent(
                    id = 7,
                    packageName = "com.instagram.android",
                    timestamp = 1_700_000_000_000,
                    wasBlocked = true,
                    blockMode = "DELAY",
                    userChangedMind = true
                )
            )
        )

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(settings(), result.settings)
        assertEquals(1, result.rules.size)
        assertEquals(1, result.history.size)
    }

    @Test
    fun `custom messages needing JSON escaping survive`() {
        val awkward = "He said \"stop\"\nand a backslash \\ and a tab\there"

        val result = exporter.importRules(
            export(settings = ExportedSettings(customDelayTitles = awkward))
        )

        assertNull(result.error)
        assertEquals(awkward, result.settings?.customDelayTitles)
    }

    /**
     * The history array is spliced in by finding the envelope's closing brace, so a `}` inside a
     * STRING value is the adversarial case for that surgery -- and custom block messages are FREE
     * TEXT the user types, which is a far likelier source of one than a package name ever was.
     * Safe by construction (the root's own brace is always last), and pinned here because getting
     * it wrong would corrupt the file rather than fail a test somewhere obvious.
     */
    @Test
    fun `a closing brace inside a custom message cannot break the history splice`() {
        val json = export(
            settings = ExportedSettings(
                customDelayTitles = "Close the app }",
                customHardBlockMessages = "{ not now }"
            ),
            history = listOf(
                UsageEvent(
                    id = 1,
                    packageName = "com.app.with}brace",
                    timestamp = 1000,
                    wasBlocked = true,
                    blockMode = "DELAY",
                    userChangedMind = false
                )
            )
        )

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals("Close the app }", result.settings?.customDelayTitles)
        assertEquals("{ not now }", result.settings?.customHardBlockMessages)
        assertEquals("com.app.with}brace", result.history.single().packageName)
        assertEquals(1, result.rules.size)
    }

    // --- Backward compatibility, both directions ----------------------------------------------

    /** Nothing to export means no `settings` key at all -- byte-identical to an older backup. */
    @Test
    fun `an export with no settings writes no settings key`() {
        val json = export(settings = null)

        assertFalse(json.contains("settings"))
        assertNull(exporter.importRules(json).settings)
    }

    /** An all-null block carries nothing, so writing the key would be noise. */
    @Test
    fun `an export whose settings are all absent writes no settings key`() {
        assertFalse(export(settings = ExportedSettings()).contains("settings"))
    }

    /** THE backward-compatibility case: every backup written before settings existed. */
    @Test
    fun `a version 1 file without settings imports exactly as before`() {
        val result = exporter.importRules(
            """
            {
                "version": 1,
                "rules": [{"packageName": "com.app1", "mode": "DELAY", "delaySeconds": 15}],
                "groups": []
            }
            """.trimIndent()
        )

        assertNull(result.error)
        assertNull("no settings block means leave this device alone", result.settings)
        assertEquals(0, result.invalidSettingsCount)
        assertEquals(1, result.rules.size)
    }

    @Test
    fun `an explicitly null settings key is treated as absent`() {
        val result = exporter.importRules(
            """{"version": 1, "rules": [{"packageName": "com.app1", "mode": "DELAY"}], "settings": null}"""
        )

        assertNull(result.error)
        assertNull(result.settings)
    }

    /** An empty object carries no setting, which is the same instruction as carrying none. */
    @Test
    fun `an empty settings object is treated as absent`() {
        val result = exporter.importRules(fileWithSettings(""))

        assertNull(result.error)
        assertNull(result.settings)
        assertEquals(0, result.invalidSettingsCount)
    }

    /**
     * Forward compatibility at the KEY level: a backup from a future Nudge that adds a tenth
     * setting must still restore the nine this build knows, without being penalised for the one it
     * does not.
     */
    @Test
    fun `unknown keys inside settings are ignored rather than counted as failures`() {
        val result = exporter.importRules(
            fileWithSettings(
                """"strictModeEnabled": true, "someFutureSetting": {"nested": [1, 2]}"""
            )
        )

        assertNull(result.error)
        assertEquals(ExportedSettings(strictModeEnabled = true), result.settings)
        assertEquals(0, result.invalidSettingsCount)
    }

    /**
     * An ABSENT key means "this file does not carry that setting", so the importing device keeps
     * its own value -- which is why a partial block is legitimate rather than suspicious.
     */
    @Test
    fun `absent keys stay null instead of defaulting to something`() {
        val result = exporter.importRules(fileWithSettings(""""contentFilterEnabled": false"""))

        assertEquals(ExportedSettings(contentFilterEnabled = false), result.settings)
    }

    /** The empty string is a REAL value ("use the built-in messages"), not an absent one. */
    @Test
    fun `a cleared custom message pool round-trips as an empty string, not as absent`() {
        val result = exporter.importRules(
            export(settings = ExportedSettings(customDelayTitles = ""))
        )

        assertEquals("", result.settings?.customDelayTitles)
    }

    // --- Envelope-level failure ---------------------------------------------------------------

    @Test
    fun `a settings key that is not an object fails loudly`() {
        val result = exporter.importRules(
            """{"version": 1, "rules": [{"packageName": "com.app1", "mode": "DELAY"}], "settings": []}"""
        )

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("settings"))
        assertTrue("nothing may survive an envelope failure", result.rules.isEmpty())
        assertNull(result.settings)
    }

    // --- Per-KEY isolation --------------------------------------------------------------------

    @Test
    fun `a wrongly typed setting is skipped while the rest of the block applies`() {
        val result = exporter.importRules(
            fileWithSettings(
                """
                "contentFilterEnabled": "yes",
                "strictModeChallengeLength": "long",
                "customDelayTitles": 42,
                "strictModeEnabled": true
                """.trimIndent()
            )
        )

        assertNull("one bad key must never fail the file", result.error)
        assertEquals(3, result.invalidSettingsCount)
        assertEquals(ExportedSettings(strictModeEnabled = true), result.settings)
    }

    @Test
    fun `settings skip reasons name the offending key`() {
        val result = exporter.importRules(fileWithSettings(""""emergencyPassEnabled": 1"""))

        assertEquals(1, result.invalidSettingsCount)
        val reason = result.invalidSettingsReasons.single()
        assertTrue(reason, reason.contains("emergencyPassEnabled"))
        assertTrue(reason, reason.contains("true or false"))
    }

    /**
     * Settings skips are their OWN counter. A corrupt settings block must not read to the user as
     * "N of your rules could not be imported" -- losing a toggle and losing a rule are different
     * events, and only one of them stops protecting them.
     */
    @Test
    fun `settings skips are counted apart from rule and history skips`() {
        val result = exporter.importRules(
            """
            {
                "version": 1,
                "rules": [{"packageName": "com.app1", "mode": "TELEPORT"}, {"packageName": "com.app2", "mode": "DELAY"}],
                "history": ["not an event"],
                "settings": {"strictModeEnabled": "sure", "contentFilterEnabled": true}
            }
            """.trimIndent()
        )

        assertNull(result.error)
        assertEquals(1, result.invalidCount)
        assertEquals(1, result.invalidHistoryCount)
        assertEquals(1, result.invalidSettingsCount)
        assertEquals(ExportedSettings(contentFilterEnabled = true), result.settings)
    }

    /** A block whose every key is unreadable carries nothing -- do not touch the device. */
    @Test
    fun `a settings block with nothing readable in it is treated as absent`() {
        val result = exporter.importRules(fileWithSettings(""""strictModeEnabled": "sure""""))

        assertNull(result.error)
        assertNull(result.settings)
        assertEquals(1, result.invalidSettingsCount)
    }

    /**
     * Settings count as something worth importing: a file whose rules are all unreadable but whose
     * settings restore cleanly still did something for the user, so it is not a total failure.
     */
    @Test
    fun `settings alone keep a file from being reported as entirely unreadable`() {
        val result = exporter.importRules(
            """
            {
                "version": 1,
                "rules": [{"packageName": "com.app1", "mode": "TELEPORT"}],
                "settings": {"strictModeEnabled": true}
            }
            """.trimIndent()
        )

        assertNull(result.error)
        assertEquals(1, result.invalidCount)
        assertEquals(ExportedSettings(strictModeEnabled = true), result.settings)
    }

    // --- Value validation ---------------------------------------------------------------------

    @Test
    fun `an unknown content filter mode is skipped rather than persisted`() {
        val result = exporter.importRules(
            fileWithSettings(""""contentFilterMode": "TELEPORT", "contentFilterEnabled": true""")
        )

        assertNull(result.error)
        assertEquals(1, result.invalidSettingsCount)
        assertNull(result.settings?.contentFilterMode)
        assertEquals(true, result.settings?.contentFilterEnabled)
        assertTrue(result.invalidSettingsReasons.single().contains("TELEPORT"))
    }

    @Test
    fun `every real block mode is accepted as a content filter mode`() {
        listOf("NONE", "HARD_BLOCK", "DELAY", "BREATHING").forEach { mode ->
            val result = exporter.importRules(fileWithSettings(""""contentFilterMode": "$mode""""))

            assertEquals(mode, 0, result.invalidSettingsCount)
            assertEquals(mode, result.settings?.contentFilterMode)
        }
    }

    /**
     * A file must not be able to install a challenge nobody can finish typing.
     *
     * That is a permanent lockout rather than a commitment lock, and it would break Strict Mode's
     * documented safety invariant -- the challenge is ALWAYS solvable -- on the one path where the
     * value never passes through the app's own difficulty picker. An export file is plain,
     * hand-editable JSON, so "nobody would type that" is not a defence.
     */
    @Test
    fun `an out-of-range challenge length is refused, not clamped`() {
        listOf("100000", "0", "-5").forEach { length ->
            val result = exporter.importRules(
                fileWithSettings(""""strictModeChallengeLength": $length""")
            )

            assertEquals(length, 1, result.invalidSettingsCount)
            assertNull(length, result.settings)
        }
    }

    @Test
    fun `every difficulty the app itself offers is accepted`() {
        listOf(
            StrictModeChallenge.LENGTH_EASY,
            StrictModeChallenge.LENGTH_MEDIUM,
            StrictModeChallenge.LENGTH_HARD
        ).forEach { length ->
            val result = exporter.importRules(
                fileWithSettings(""""strictModeChallengeLength": $length""")
            )

            assertEquals(length.toString(), 0, result.invalidSettingsCount)
            assertEquals(length, result.settings?.strictModeChallengeLength)
        }
    }

    @Test
    fun `the accepted challenge length runs right up to the documented maximum`() {
        val result = exporter.importRules(
            fileWithSettings(""""strictModeChallengeLength": ${StrictModeChallenge.MAX_LENGTH}""")
        )

        assertEquals(0, result.invalidSettingsCount)
        assertEquals(StrictModeChallenge.MAX_LENGTH, result.settings?.strictModeChallengeLength)
    }
}
