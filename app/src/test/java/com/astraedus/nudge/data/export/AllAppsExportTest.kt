package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.BlockRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.testing.aBlockRule
import com.astraedus.nudge.testing.aGroupMember
import com.astraedus.nudge.testing.anAppGroup

/**
 * L4. The backup format for All apps rules (spec 5.8): they ride in their own top-level
 * `extendedRules` array, never in `rules`, so a Nudge that predates them reads the file without
 * them instead of importing each as a rule with no target. Allowed groups travel by name.
 */
class AllAppsExportTest {

    private val exporter = RuleExporter()

    private val work = anAppGroup(id = 1, name = "Work")
    private val social = anAppGroup(id = 2, name = "Social")
    private val members = mapOf(
        1L to listOf(aGroupMember(1, "com.slack")),
        2L to listOf(aGroupMember(2, "com.insta"))
    )

    private val appRule = aBlockRule(id = 10, packageName = "com.yt", mode = "DELAY", delaySeconds = 15)
    private val groupRule = aBlockRule(id = 11, groupId = 2, mode = "HARD_BLOCK")
    private val allAppsRule = aBlockRule(
        id = 12,
        mode = "HARD_BLOCK",
        allApps = true,
        exceptPackages = "com.maps,com.phone",
        // 99 is a stale id: its group was deleted, so it allows nothing and has no name.
        exceptGroupIds = "1,99",
        scheduleDays = "1,2,3,4,5",
        scheduleStartMinute = 540,
        scheduleEndMinute = 1020
    )

    private fun export(vararg rules: BlockRule): JSONObject =
        JSONObject(exporter.exportRules(rules.toList(), listOf(work, social), members))

    @Test
    fun `an All apps rule is written to extendedRules with its except lists, never to rules`() {
        val root = export(appRule, groupRule, allAppsRule)

        val rules = root.getJSONArray("rules")
        assertEquals(2, rules.length())
        for (i in 0 until rules.length()) assertFalse(rules.getJSONObject(i).has("allApps"))

        val extended = root.getJSONArray("extendedRules")
        assertEquals(1, extended.length())
        val entry = extended.getJSONObject(0)
        assertEquals(true, entry.get("allApps"))
        assertTrue(entry.isNull("packageName"))
        assertTrue(entry.isNull("groupName"))
        assertEquals(listOf("com.maps", "com.phone"), entry.getJSONArray("exceptPackages").strings())
        assertEquals(listOf("Work"), entry.getJSONArray("exceptGroups").strings())
        assertEquals("HARD_BLOCK", entry.getString("mode"))
        assertEquals("1,2,3,4,5", entry.getString("scheduleDays"))
        assertEquals(540, entry.getInt("scheduleStartMinute"))
        assertEquals(1020, entry.getInt("scheduleEndMinute"))
        assertEquals(1, root.getInt("version"))
    }

    @Test
    fun `a file with no All apps rule carries no extendedRules key`() {
        assertFalse(export(appRule, groupRule).has("extendedRules"))
    }

    @Test
    fun `a corrupt row naming an app as well as all apps is exported as the app rule the engine reads`() {
        val root = export(allAppsRule.copy(packageName = "com.yt"))
        assertFalse(root.has("extendedRules"))
        assertEquals("com.yt", root.getJSONArray("rules").getJSONObject(0).getString("packageName"))
    }

    /**
     * An older Nudge reads only the keys it knows. Dropping `extendedRules` from the file is
     * exactly what it sees: the app and group rules arrive intact, and the All apps rule is ABSENT
     * (not imported as a rule with no target, which every entry in `rules` would otherwise be).
     */
    @Test
    fun `an older reader sees every other rule and no All apps rule`() {
        val root = export(appRule, groupRule, allAppsRule)
        root.remove("extendedRules")

        val result = exporter.importRules(root.toString())

        assertNull(result.error)
        assertEquals(0, result.invalidCount)
        assertEquals(listOf("com.yt" to null, null to "Social"), result.rules.map { it.packageName to it.groupName })
        assertTrue(result.rules.none { it.allApps })
    }

    @Test
    fun `extendedRules parse back with the except lists and the allApps flag`() {
        val result = exporter.importRules(export(appRule, allAppsRule).toString())

        assertNull(result.error)
        val parsed = result.rules.single { it.allApps }
        assertNull(parsed.packageName)
        assertNull(parsed.groupName)
        assertEquals(listOf("com.maps", "com.phone"), parsed.exceptPackages)
        assertEquals(listOf("Work"), parsed.exceptGroups)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `an extendedRules entry that is not an All apps rule is skipped with a reason`() {
        val json = """
        {
            "version": 1,
            "rules": [{"packageName": "com.yt", "mode": "DELAY"}],
            "extendedRules": [
                {"mode": "HARD_BLOCK", "futureKind": true},
                {"allApps": "true", "mode": "HARD_BLOCK"},
                {"allApps": true, "packageName": "com.yt", "mode": "HARD_BLOCK"},
                {"allApps": true, "groupName": "Work", "mode": "HARD_BLOCK"},
                {"allApps": true, "mode": "TELEPORT"},
                {"allApps": true, "mode": "HARD_BLOCK"}
            ]
        }
        """.trimIndent()

        val result = exporter.importRules(json)

        assertNull(result.error)
        assertEquals(2, result.rules.size)
        assertTrue(result.rules[1].allApps)
        assertEquals(5, result.invalidCount)
        assertEquals("Extended rule 1: Needs a newer version of Nudge", result.invalidReasons[0])
        assertEquals("Extended rule 2: Needs a newer version of Nudge", result.invalidReasons[1])
        assertEquals("Extended rule 3: Names an app or a group as well as all apps", result.invalidReasons[2])
        assertEquals("Extended rule 4: Names an app or a group as well as all apps", result.invalidReasons[3])
        assertTrue(result.invalidReasons[4].startsWith("Extended rule 5: "))
    }

    @Test
    fun `allApps in the rules array is ignored, so it can not turn an app rule into a lock on everything`() {
        val json = """
        {"version": 1, "rules": [{"packageName": "com.yt", "allApps": true, "exceptPackages": ["com.x"], "mode": "DELAY"}]}
        """.trimIndent()

        val rule = exporter.importRules(json).rules.single()

        assertEquals("com.yt", rule.packageName)
        assertFalse(rule.allApps)
        assertTrue(rule.exceptPackages.isEmpty())
    }

    @Test
    fun `unreadable entries in the except lists are dropped and the rule is kept`() {
        val json = """
        {"version": 1, "extendedRules": [{
            "allApps": true, "mode": "HARD_BLOCK",
            "exceptPackages": [" com.maps ", "", 7, null, "com.maps", "com.phone"],
            "exceptGroups": "Work"
        }]}
        """.trimIndent()

        val rule = exporter.importRules(json).rules.single()

        assertEquals(listOf("com.maps", "com.phone"), rule.exceptPackages)
        assertTrue(rule.exceptGroups.isEmpty())
    }

    @Test
    fun `a file whose only rule is an All apps rule is importable`() {
        val json = """{"version": 1, "extendedRules": [{"allApps": true, "mode": "HARD_BLOCK"}]}"""
        val result = exporter.importRules(json)
        assertNull(result.error)
        assertTrue(result.rules.single().allApps)
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
