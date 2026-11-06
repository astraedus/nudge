package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L4. The backup format for the first-unlock schedule (spec 5.8, 5.3).
 *
 * A rule with a `firstUnlockMinutes` window rides in `extendedRules` whatever its target. An older
 * Nudge does not know that key, so from `rules` it would import the rule with no window at all --
 * always on, the opposite of "a lock for a few minutes after you unlock". In `extendedRules` that
 * Nudge skips the rule and restores everything else, so the user loses one rule on that phone
 * rather than gaining a lock that never ends.
 */
class FirstUnlockExportTest {

    private val exporter = RuleExporter()

    private val work = AppGroup(id = 1, name = "Work")
    private val social = AppGroup(id = 2, name = "Social")
    private val members = mapOf(
        1L to listOf(AppGroupMember(1, "com.slack")),
        2L to listOf(AppGroupMember(2, "com.insta"))
    )

    private val plainAppRule = BlockRule(id = 10, packageName = "com.yt", mode = "DELAY", delaySeconds = 15)
    private val plainGroupRule = BlockRule(id = 11, groupId = 2, mode = "HARD_BLOCK")
    private val firstUnlockAppRule = BlockRule(
        id = 12,
        packageName = "com.reddit",
        mode = "HARD_BLOCK",
        firstUnlockMinutes = 30,
        armedAt = 1_700_000_000_000
    )
    private val firstUnlockGroupRule = BlockRule(
        id = 13,
        groupId = 1,
        mode = "DELAY",
        delaySeconds = 20,
        firstUnlockMinutes = 45
    )
    private val firstUnlockAllAppsRule = BlockRule(
        id = 14,
        mode = "HARD_BLOCK",
        allApps = true,
        exceptPackages = "com.maps",
        firstUnlockMinutes = 15
    )

    private fun export(vararg rules: BlockRule): JSONObject =
        JSONObject(exporter.exportRules(rules.toList(), listOf(work, social), members))

    // --- The split -------------------------------------------------------------------------

    @Test
    fun `first-unlock app, group and All apps rules all go to extendedRules`() {
        val root = export(
            plainAppRule,
            plainGroupRule,
            firstUnlockAppRule,
            firstUnlockGroupRule,
            firstUnlockAllAppsRule
        )

        val rules = root.getJSONArray("rules")
        assertEquals("nothing extra may land in rules", 2, rules.length())
        assertEquals("com.yt", rules.getJSONObject(0).getString("packageName"))
        assertTrue(rules.getJSONObject(1).isNull("packageName"))
        assertEquals("Social", rules.getJSONObject(1).getString("groupName"))
        for (i in 0 until rules.length()) {
            assertFalse("plain rules never carry a window", rules.getJSONObject(i).has("firstUnlockMinutes"))
        }

        val extended = root.getJSONArray("extendedRules")
        assertEquals(3, extended.length())

        val app = extended.getJSONObject(0)
        assertEquals("com.reddit", app.getString("packageName"))
        assertEquals(false, app.get("allApps"))
        assertEquals(30, app.getInt("firstUnlockMinutes"))

        val group = extended.getJSONObject(1)
        assertEquals("Work", group.getString("groupName"))
        assertTrue(group.isNull("packageName"))
        assertEquals(45, group.getInt("firstUnlockMinutes"))

        val allApps = extended.getJSONObject(2)
        assertEquals(true, allApps.get("allApps"))
        assertTrue(allApps.isNull("packageName"))
        assertTrue(allApps.isNull("groupName"))
        assertEquals(15, allApps.getInt("firstUnlockMinutes"))
    }

    @Test
    fun `a file with no first-unlock and no All apps rule carries no extendedRules key`() {
        assertFalse(export(plainAppRule, plainGroupRule).has("extendedRules"))
    }

    @Test
    fun `firstUnlockMinutes is written only when the rule carries a window`() {
        val root = export(
            plainAppRule,
            BlockRule(id = 15, mode = "HARD_BLOCK", allApps = true),
            firstUnlockAppRule
        )

        assertFalse(root.getJSONArray("rules").getJSONObject(0).has("firstUnlockMinutes"))

        val allApps = root.getJSONArray("extendedRules").getJSONObject(0)
        assertEquals(true, allApps.get("allApps"))
        assertFalse("never written as JSON null", allApps.has("firstUnlockMinutes"))
        assertEquals(30, root.getJSONArray("extendedRules").getJSONObject(1).getInt("firstUnlockMinutes"))
    }

    @Test
    fun `armedAt never appears in the file`() {
        val json = exporter.exportRules(
            listOf(firstUnlockAppRule.copy(armedAt = 1_700_000_000_123)),
            listOf(work, social),
            members
        )

        assertFalse(json.contains("armedAt"))
        assertFalse("the stamp itself must not leak either", json.contains("1700000000123"))
    }

    // --- Parsing back ----------------------------------------------------------------------

    @Test
    fun `first-unlock rules parse back with the same minutes and target`() {
        val result = exporter.importRules(
            export(plainAppRule, firstUnlockAppRule, firstUnlockGroupRule, firstUnlockAllAppsRule).toString()
        )

        assertNull(result.error)
        assertEquals(4, result.rules.size)

        val app = result.rules.single { it.packageName == "com.reddit" }
        assertEquals(30, app.firstUnlockMinutes)
        assertFalse(app.allApps)

        val group = result.rules.single { it.groupName == "Work" }
        assertEquals(45, group.firstUnlockMinutes)

        val allApps = result.rules.single { it.allApps }
        assertEquals(15, allApps.firstUnlockMinutes)
        assertEquals(listOf("com.maps"), allApps.exceptPackages)

        assertNull(
            "a plain rule carries no window",
            result.rules.single { it.packageName == "com.yt" }.firstUnlockMinutes
        )
    }

    @Test
    fun `firstUnlockMinutes in the rules array is ignored, so a hand edit can not add a window`() {
        val json = """
            {"version": 1, "rules": [{"packageName": "com.yt", "mode": "DELAY", "firstUnlockMinutes": 30}]}
        """.trimIndent()

        val rule = exporter.importRules(json).rules.single()

        assertEquals("com.yt", rule.packageName)
        assertNull(rule.firstUnlockMinutes)
    }

    @Test
    fun `an invalid firstUnlockMinutes skips that entry with a reason and the rest of the file imports`() {
        val json = """
            {
                "version": 1,
                "rules": [{"packageName": "com.yt", "mode": "DELAY"}],
                "extendedRules": [
                    {"packageName": "com.reddit", "mode": "HARD_BLOCK", "firstUnlockMinutes": 0},
                    {"packageName": "com.insta", "mode": "HARD_BLOCK", "firstUnlockMinutes": -5},
                    {"packageName": "com.x", "mode": "HARD_BLOCK", "firstUnlockMinutes": "soon"},
                    {"packageName": "com.slack", "mode": "HARD_BLOCK", "firstUnlockMinutes": 12.5},
                    {"packageName": "com.maps", "mode": "HARD_BLOCK", "firstUnlockMinutes": 20}
                ]
            }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull("one bad window must never fail the file", result.error)
        assertEquals(listOf("com.yt", "com.maps"), result.rules.map { it.packageName })
        assertEquals(20, result.rules.last().firstUnlockMinutes)
        assertEquals(4, result.invalidCount)
        assertEquals(4, result.invalidReasons.size)
        assertTrue(result.invalidReasons[0].startsWith("Extended rule 1: "))
        assertTrue(result.invalidReasons.all { it.contains("firstUnlockMinutes") })
    }

    @Test
    fun `an extended entry that names both an app and a group is skipped rather than handed to the repository`() {
        val json = """
            {"version": 1, "extendedRules": [
                {"packageName": "com.yt", "groupName": "Work", "mode": "HARD_BLOCK", "firstUnlockMinutes": 30}
            ]}
        """.trimIndent()

        val result = exporter.importRules(json)

        assertTrue(result.rules.isEmpty())
        assertEquals(1, result.invalidCount)
        assertTrue(result.invalidReasons.single().contains("both an app and a group"))
    }

    @Test
    fun `a first-unlock entry that names no target is skipped rather than written as a rule targeting nothing`() {
        // The repository refuses a row with no target on write, so handing it this one would turn
        // one bad entry into a failed import. It is the same class as the two-target row above,
        // caught here because `rules` catches it and `extendedRules` has its own shape.
        val json = """
            {"version": 1, "extendedRules": [
                {"mode": "HARD_BLOCK", "firstUnlockMinutes": 30}
            ]}
        """.trimIndent()

        val result = exporter.importRules(json)

        assertTrue(result.rules.isEmpty())
        assertEquals(1, result.invalidCount)
        assertTrue(result.invalidReasons.single().contains("no app and no group"))
    }

    /**
     * An older Nudge reads only the keys it knows. Dropping `extendedRules` is exactly what it
     * sees: the app and group rules arrive intact, and no first-unlock rule survives -- in
     * particular none is imported from `rules` with no window (which would be always on).
     */
    @Test
    fun `an older reader sees every other rule and no first-unlock rule`() {
        val root = export(
            plainAppRule,
            plainGroupRule,
            firstUnlockAppRule,
            firstUnlockGroupRule,
            firstUnlockAllAppsRule
        )
        root.remove("extendedRules")

        val result = exporter.importRules(root.toString())

        assertNull(result.error)
        assertEquals(0, result.invalidCount)
        assertEquals(listOf("com.yt", null), result.rules.map { it.packageName })
        assertEquals(listOf(null, "Social"), result.rules.map { it.groupName })
        assertTrue(result.rules.none { it.firstUnlockMinutes != null })
    }

    // --- Settings: the logical day's start -------------------------------------------------

    @Test
    fun `dayStartMinute survives a round trip and is written as a number`() {
        val json = exporter.exportRules(
            rules = emptyList(),
            groups = emptyList(),
            groupMembers = emptyMap(),
            settings = ExportedSettings(dayStartMinute = 300)
        )

        assertEquals(300, JSONObject(json).getJSONObject("settings").getInt("dayStartMinute"))
        assertEquals(300, exporter.importRules(json).settings?.dayStartMinute)
    }

    @Test
    fun `a file without dayStartMinute leaves the phone's own value alone`() {
        val result = exporter.importRules(
            """
            {"version": 1, "rules": [{"packageName": "com.yt", "mode": "DELAY"}],
             "settings": {"strictModeEnabled": true}}
            """.trimIndent()
        )

        assertNull(result.error)
        assertEquals(ExportedSettings(strictModeEnabled = true), result.settings)
        assertNull("absent means this file does not carry it", result.settings?.dayStartMinute)
    }

    @Test
    fun `an out-of-range or non-numeric dayStartMinute is a skipped key, never a failed file`() {
        for (value in listOf("1440", "-1", "\"4am\"", "12.5")) {
            val result = exporter.importRules(
                """
                {"version": 1,
                 "rules": [{"packageName": "com.yt", "mode": "DELAY"}],
                 "settings": {"dayStartMinute": $value, "strictModeEnabled": true}}
                """.trimIndent()
            )

            assertNull("$value must not fail the file", result.error)
            assertEquals(value, 1, result.invalidSettingsCount)
            assertTrue(value, result.invalidSettingsReasons.single().contains("dayStartMinute"))
            assertEquals(
                "the rest of the settings still apply",
                ExportedSettings(strictModeEnabled = true),
                result.settings
            )
            assertEquals(1, result.rules.size)
        }
    }
}
