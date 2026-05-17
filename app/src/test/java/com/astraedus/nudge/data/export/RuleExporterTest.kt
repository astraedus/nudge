package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuleExporterTest {

    private lateinit var exporter: RuleExporter

    @Before
    fun setUp() {
        exporter = RuleExporter()
    }

    @Test
    fun `export single rule produces valid JSON`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = "com.instagram.android",
                mode = "DELAY",
                delaySeconds = 15,
                enabled = true,
                showCounter = true
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val parsed = JSONObject(json)

        assertEquals(1, parsed.getInt("version"))
        assertTrue(parsed.has("exportedAt"))
        assertEquals(1, parsed.getJSONArray("rules").length())

        val rule = parsed.getJSONArray("rules").getJSONObject(0)
        assertEquals("com.instagram.android", rule.getString("packageName"))
        assertEquals("DELAY", rule.getString("mode"))
        assertEquals(15, rule.getInt("delaySeconds"))
        assertEquals(true, rule.getBoolean("enabled"))
        assertEquals(true, rule.getBoolean("showCounter"))
    }

    @Test
    fun `export resolves groupId to group name`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = null,
                groupId = 5,
                mode = "HARD_BLOCK",
                delaySeconds = 0,
                enabled = true
            )
        )
        val groups = listOf(AppGroup(id = 5, name = "Social Media"))
        val members = mapOf(5L to listOf(
            AppGroupMember(groupId = 5, packageName = "com.instagram.android"),
            AppGroupMember(groupId = 5, packageName = "com.twitter.android")
        ))

        val json = exporter.exportRules(rules, groups, members)
        val parsed = JSONObject(json)

        val rule = parsed.getJSONArray("rules").getJSONObject(0)
        assertTrue(rule.isNull("packageName"))
        assertEquals("Social Media", rule.getString("groupName"))

        val group = parsed.getJSONArray("groups").getJSONObject(0)
        assertEquals("Social Media", group.getString("name"))
        assertEquals(2, group.getJSONArray("members").length())
    }

    @Test
    fun `export handles null optional fields`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = "com.example.app",
                mode = "BREATHING",
                delaySeconds = 30,
                dailyLimitMinutes = null,
                scheduleDays = null,
                scheduleStartMinute = null,
                scheduleEndMinute = null,
                inAppFeatures = null,
                autoKickAfter = null,
                enabled = true
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val parsed = JSONObject(json)
        val rule = parsed.getJSONArray("rules").getJSONObject(0)

        assertTrue(rule.isNull("dailyLimitMinutes"))
        assertTrue(rule.isNull("scheduleDays"))
        assertTrue(rule.isNull("scheduleStartMinute"))
        assertTrue(rule.isNull("scheduleEndMinute"))
        assertTrue(rule.isNull("inAppFeatures"))
        assertTrue(rule.isNull("autoKickAfter"))
    }

    @Test
    fun `roundtrip preserves all fields`() {
        val rules = listOf(
            BlockRule(
                id = 42,
                packageName = "com.instagram.android",
                mode = "DELAY",
                delaySeconds = 30,
                dailyLimitMinutes = 60,
                enabled = true,
                scheduleDays = "1,2,3,4,5",
                scheduleStartMinute = 540,
                scheduleEndMinute = 1020,
                inAppFeatures = "REELS,SHORTS",
                grayscale = true,
                showCounter = true,
                autoKickAfter = 25,
                showTimeRemaining = true,
                autoKickCooldownSeconds = 120
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)

        val imported = result.rules[0]
        assertEquals("com.instagram.android", imported.packageName)
        assertEquals("DELAY", imported.mode)
        assertEquals(30, imported.delaySeconds)
        assertEquals(60, imported.dailyLimitMinutes)
        assertEquals(true, imported.enabled)
        assertEquals("1,2,3,4,5", imported.scheduleDays)
        assertEquals(540, imported.scheduleStartMinute)
        assertEquals(1020, imported.scheduleEndMinute)
        assertEquals("REELS,SHORTS", imported.inAppFeatures)
        assertEquals(true, imported.grayscale)
        assertEquals(true, imported.showCounter)
        assertEquals(25, imported.autoKickAfter)
        assertEquals(true, imported.showTimeRemaining)
        assertEquals(120, imported.autoKickCooldownSeconds)
    }

    @Test
    fun `roundtrip with groups preserves group data`() {
        val rules = listOf(
            BlockRule(id = 1, groupId = 3, mode = "HARD_BLOCK", enabled = true)
        )
        val groups = listOf(AppGroup(id = 3, name = "Time Wasters"))
        val members = mapOf(3L to listOf(
            AppGroupMember(groupId = 3, packageName = "com.tiktok.android")
        ))

        val json = exporter.exportRules(rules, groups, members)
        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.groups.size)
        assertEquals("Time Wasters", result.groups[0].name)
        assertEquals(listOf("com.tiktok.android"), result.groups[0].members)
        assertEquals("Time Wasters", result.rules[0].groupName)
    }

    @Test
    fun `export produces pretty-printed JSON`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = "com.example.app", mode = "DELAY", enabled = true)
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())

        // Pretty-printed JSON has newlines and indentation
        assertTrue(json.contains("\n"))
        assertTrue(json.contains("  "))
    }

    @Test
    fun `roundtrip preserves webDomains field`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = "com.instagram.android",
                mode = "DELAY",
                delaySeconds = 15,
                enabled = true,
                webDomains = "instagram.com,www.instagram.com"
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertEquals("instagram.com,www.instagram.com", result.rules[0].webDomains)
    }

    @Test
    fun `export with null webDomains produces null in JSON`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = "com.example.app",
                mode = "HARD_BLOCK",
                enabled = true,
                webDomains = null
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val parsed = JSONObject(json)
        val rule = parsed.getJSONArray("rules").getJSONObject(0)

        assertTrue(rule.isNull("webDomains"))
    }

    @Test
    fun `import handles missing webDomains field gracefully`() {
        // Simulate an export from an older version without webDomains
        val json = """
        {
          "version": 1,
          "exportedAt": 1700000000000,
          "rules": [{
            "packageName": "com.example.app",
            "groupName": null,
            "mode": "DELAY",
            "delaySeconds": 15,
            "dailyLimitMinutes": null,
            "enabled": true,
            "scheduleDays": null,
            "scheduleStartMinute": null,
            "scheduleEndMinute": null,
            "inAppFeatures": null,
            "grayscale": false,
            "showCounter": false,
            "autoKickAfter": null,
            "showTimeRemaining": false,
            "autoKickCooldownSeconds": 60
          }],
          "groups": []
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertNull(result.rules[0].webDomains)
    }
}
