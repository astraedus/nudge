package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.domain.model.BlockMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The class-level invariant, not the BlockMode.NONE instance of it: anything the app can EXPORT
     * must survive being IMPORTED back. Import validated against a hand-written mode whitelist that
     * went stale when NONE was added, and because one bad rule aborts the whole array, a single
     * NONE rule made an entire backup unrestorable. Iterating BlockMode here means the next mode
     * added fails this test instead of silently eating a user's rules.
     *
     * Also asserts `delaySeconds` comes back unchanged, not just the mode name: HOLD (and DELAY,
     * BREATHING) carry their duration in that column, and a mode that imports but loses its
     * duration is just as broken as one that fails to import at all -- see
     * [BlockMode.usesDuration].
     */
    @Test
    fun `every block mode survives an export-import round trip`() {
        BlockMode.entries.forEach { mode ->
            val rules = listOf(
                BlockRule(
                    id = 1,
                    packageName = "com.instagram.android",
                    mode = mode.name,
                    delaySeconds = 15,
                    enabled = true
                )
            )

            val json = exporter.exportRules(rules, emptyList(), emptyMap())
            val result = exporter.importRules(json)

            assertNull("mode ${mode.name} failed to import: ${result.error}", result.error)
            assertEquals("mode ${mode.name} reported invalid rules", 0, result.invalidCount)
            assertEquals("mode ${mode.name} was dropped on import", 1, result.rules.size)
            assertEquals(mode.name, result.rules.first().mode)
            assertEquals(
                "mode ${mode.name} lost its delaySeconds on import",
                15,
                result.rules.first().delaySeconds
            )
        }
    }

    /**
     * A dedicated round trip for HOLD (issue #35), the mode that made [usesDuration] worth asking
     * as a question rather than hand-checking `mode == DELAY || mode == BREATHING` at each call
     * site. HOLD reuses `delaySeconds` exactly like DELAY -- this pins that a rule switched to HOLD
     * keeps a distinctive duration through a full export/import cycle, on top of the enum-wide
     * invariant above.
     */
    @Test
    fun `HOLD rule round trip preserves mode and delaySeconds`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = "com.instagram.android",
                mode = BlockMode.HOLD.name,
                delaySeconds = 20,
                enabled = true
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(0, result.invalidCount)
        assertEquals(1, result.rules.size)
        assertEquals("HOLD", result.rules.first().mode)
        assertEquals(20, result.rules.first().delaySeconds)
    }

    /**
     * An unrecognized mode is still refused -- tolerating one would write a rule the BlockEngine
     * cannot evaluate. Here it is the file's ONLY rule, so nothing is importable and the import
     * fails loudly; per-entry isolation (issue #20) is covered by [ImportSkipInvalidTest].
     */
    @Test
    fun `an unknown block mode is still rejected`() {
        val json = exporter.exportRules(
            listOf(BlockRule(id = 1, packageName = "com.x", mode = "TELEPORT", delaySeconds = 15)),
            emptyList(),
            emptyMap()
        )

        val result = exporter.importRules(json)

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("TELEPORT"))
        assertEquals(1, result.invalidCount)
        assertTrue(result.rules.isEmpty())
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

    /**
     * The whole point of `webBlockMode` (issue #21) is a rule that blocks a WEBSITE while leaving
     * the APP open — `mode = NONE` plus an independent web mode. Losing the field on a backup
     * restore would silently turn that block off again, which is the exact bug it fixed.
     */
    @Test
    fun `roundtrip preserves an independent webBlockMode on a NONE-mode rule`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = "com.instagram.android",
                mode = "NONE",
                delaySeconds = 15,
                enabled = true,
                webDomains = "instagram.com",
                webBlockMode = "HARD_BLOCK"
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertEquals("NONE", result.rules[0].mode)
        assertEquals("HARD_BLOCK", result.rules[0].webBlockMode)
    }

    @Test
    fun `import of an older export without webBlockMode inherits the app mode`() {
        // No webBlockMode key at all -- what every pre-#21 export looks like. Null is the
        // "inherit the app-level mode" value, i.e. exactly the behaviour those exports had.
        val json = """
        {
          "version": 1,
          "exportedAt": 1700000000000,
          "rules": [{
            "packageName": "com.example.app",
            "groupName": null,
            "mode": "DELAY",
            "delaySeconds": 15,
            "enabled": true,
            "webDomains": "example.com"
          }],
          "groups": []
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertNull(result.rules[0].webBlockMode)
    }

    /**
     * v1.18.0's Tab Vanish / Following steer round trip. `tabVanish=false` on this rule means the
     * user deliberately turned OFF the default-on cover — losing that on a restore would silently
     * turn it back on, the same class of regression `webBlockMode`'s round trip above guards.
     */
    @Test
    fun `roundtrip preserves tabVanish=false and followingSteer=true`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = "com.instagram.android",
                mode = "HARD_BLOCK",
                enabled = true,
                tabVanish = false,
                followingSteer = true
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertFalse(result.rules[0].tabVanish)
        assertTrue(result.rules[0].followingSteer)
    }

    /**
     * A file with neither key — every backup written before v1.18.0 — must restore as
     * `tabVanish=true` (matching the entity's new default: those rules had no cover of their own,
     * and "on" is the correct reading of them now) and `followingSteer=false` (a brand-new,
     * opt-in, experimental capability no old export could have carried).
     */
    @Test
    fun `import of a pre-v1_18_0 export without tabVanish or followingSteer keys defaults them on and off`() {
        val json = """
        {
          "version": 1,
          "exportedAt": 1700000000000,
          "rules": [{
            "packageName": "com.instagram.android",
            "groupName": null,
            "mode": "HARD_BLOCK",
            "delaySeconds": 15,
            "enabled": true
          }],
          "groups": []
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertTrue(result.rules[0].tabVanish)
        assertFalse(result.rules[0].followingSteer)
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

    @Test
    fun `roundtrip preserves autoKickAfterMinutes field`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = "com.instagram.android",
                mode = "DELAY",
                delaySeconds = 15,
                enabled = true,
                autoKickAfterMinutes = 30
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertEquals(30, result.rules[0].autoKickAfterMinutes)
    }

    @Test
    fun `roundtrip preserves null autoKickAfterMinutes field`() {
        val rules = listOf(
            BlockRule(
                id = 1,
                packageName = "com.example.app",
                mode = "HARD_BLOCK",
                enabled = true,
                autoKickAfterMinutes = null
            )
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val parsed = JSONObject(json)
        val rule = parsed.getJSONArray("rules").getJSONObject(0)
        assertTrue(rule.isNull("autoKickAfterMinutes"))

        val result = exporter.importRules(json)
        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertNull(result.rules[0].autoKickAfterMinutes)
    }

    @Test
    fun `serialized JSON contains autoKickAfterMinutes key`() {
        val rules = listOf(
            BlockRule(id = 1, packageName = "com.example.app", mode = "DELAY", enabled = true)
        )

        val json = exporter.exportRules(rules, emptyList(), emptyMap())
        val parsed = JSONObject(json)
        val rule = parsed.getJSONArray("rules").getJSONObject(0)

        assertTrue(rule.has("autoKickAfterMinutes"))
    }

    @Test
    fun `import handles missing autoKickAfterMinutes field gracefully`() {
        // Simulate an export from an older version (pre-autoKickAfterMinutes) without the key
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
            "autoKickCooldownSeconds": 60,
            "webDomains": null
          }],
          "groups": []
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(1, result.rules.size)
        val rule = result.rules[0]
        assertNull(rule.autoKickAfterMinutes)
        // Other fields still parse correctly from this old-format export
        assertEquals("com.example.app", rule.packageName)
        assertEquals("DELAY", rule.mode)
        assertEquals(15, rule.delaySeconds)
        assertEquals(true, rule.enabled)
        assertEquals(60, rule.autoKickCooldownSeconds)
        assertNull(rule.webDomains)
    }
}
