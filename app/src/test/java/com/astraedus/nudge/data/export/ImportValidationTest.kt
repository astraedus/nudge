package com.astraedus.nudge.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImportValidationTest {

    private lateinit var exporter: RuleExporter

    @Before
    fun setUp() {
        exporter = RuleExporter()
    }

    @Test
    fun `invalid JSON returns error`() {
        val result = exporter.importRules("not json at all {{{")
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Invalid JSON"))
    }

    @Test
    fun `empty JSON object returns version error`() {
        val result = exporter.importRules("{}")
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("version"))
    }

    @Test
    fun `missing version returns error`() {
        val json = """{"rules": []}"""
        val result = exporter.importRules(json)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("version"))
    }

    @Test
    fun `version 0 returns error`() {
        val json = """{"version": 0, "rules": []}"""
        val result = exporter.importRules(json)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("version"))
    }

    @Test
    fun `future version returns upgrade error`() {
        val json = """{"version": 99, "rules": []}"""
        val result = exporter.importRules(json)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("newer"))
        assertTrue(result.error!!.contains("update"))
    }

    @Test
    fun `invalid block mode returns error`() {
        val json = """
        {
            "version": 1,
            "exportedAt": 1000,
            "rules": [
                {
                    "packageName": "com.example.app",
                    "mode": "INVALID_MODE",
                    "delaySeconds": 15,
                    "enabled": true,
                    "grayscale": false,
                    "showCounter": false,
                    "showTimeRemaining": false,
                    "autoKickCooldownSeconds": 60
                }
            ]
        }
        """.trimIndent()
        val result = exporter.importRules(json)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Invalid data"))
    }

    @Test
    fun `valid minimal rule parses successfully`() {
        val json = """
        {
            "version": 1,
            "exportedAt": 1000,
            "rules": [
                {
                    "packageName": "com.example.app",
                    "mode": "DELAY",
                    "delaySeconds": 15,
                    "enabled": true,
                    "grayscale": false,
                    "showCounter": false,
                    "showTimeRemaining": false,
                    "autoKickCooldownSeconds": 60
                }
            ]
        }
        """.trimIndent()
        val result = exporter.importRules(json)
        assertNull(result.error)
        assertEquals(1, result.rules.size)
        assertEquals("com.example.app", result.rules[0].packageName)
        assertEquals("DELAY", result.rules[0].mode)
    }

    @Test
    fun `missing optional fields use defaults`() {
        val json = """
        {
            "version": 1,
            "exportedAt": 1000,
            "rules": [
                {
                    "packageName": "com.example.app",
                    "mode": "HARD_BLOCK"
                }
            ]
        }
        """.trimIndent()
        val result = exporter.importRules(json)
        assertNull(result.error)
        assertEquals(1, result.rules.size)

        val rule = result.rules[0]
        assertEquals(15, rule.delaySeconds)
        assertEquals(true, rule.enabled)
        assertEquals(false, rule.grayscale)
        assertEquals(false, rule.showCounter)
        assertNull(rule.dailyLimitMinutes)
        assertNull(rule.scheduleDays)
        assertNull(rule.autoKickAfter)
        assertEquals(false, rule.showTimeRemaining)
        assertEquals(60, rule.autoKickCooldownSeconds)
    }

    @Test
    fun `null packageName and groupName are handled`() {
        val json = """
        {
            "version": 1,
            "exportedAt": 1000,
            "rules": [
                {
                    "packageName": null,
                    "groupName": null,
                    "mode": "BREATHING",
                    "delaySeconds": 30
                }
            ]
        }
        """.trimIndent()
        val result = exporter.importRules(json)
        assertNull(result.error)
        assertNull(result.rules[0].packageName)
        assertNull(result.rules[0].groupName)
    }

    @Test
    fun `groups parse correctly`() {
        val json = """
        {
            "version": 1,
            "exportedAt": 1000,
            "rules": [],
            "groups": [
                {
                    "name": "Social",
                    "members": ["com.instagram.android", "com.twitter.android"]
                }
            ]
        }
        """.trimIndent()
        val result = exporter.importRules(json)
        assertNull(result.error)
        assertEquals(1, result.groups.size)
        assertEquals("Social", result.groups[0].name)
        assertEquals(2, result.groups[0].members.size)
        assertEquals("com.instagram.android", result.groups[0].members[0])
    }

    @Test
    fun `missing groups array defaults to empty`() {
        val json = """
        {
            "version": 1,
            "exportedAt": 1000,
            "rules": []
        }
        """.trimIndent()
        val result = exporter.importRules(json)
        assertNull(result.error)
        assertEquals(0, result.groups.size)
    }

    @Test
    fun `all three block modes are accepted`() {
        val modes = listOf("HARD_BLOCK", "DELAY", "BREATHING")
        for (mode in modes) {
            val json = """
            {
                "version": 1,
                "exportedAt": 1000,
                "rules": [{"packageName": "com.test", "mode": "$mode"}]
            }
            """.trimIndent()
            val result = exporter.importRules(json)
            assertNull("Mode $mode should be valid", result.error)
            assertEquals(mode, result.rules[0].mode)
        }
    }

    @Test
    fun `multiple rules parse correctly`() {
        val json = """
        {
            "version": 1,
            "exportedAt": 1000,
            "rules": [
                {"packageName": "com.app1", "mode": "DELAY", "delaySeconds": 15},
                {"packageName": "com.app2", "mode": "HARD_BLOCK"},
                {"packageName": "com.app3", "mode": "BREATHING", "delaySeconds": 60}
            ]
        }
        """.trimIndent()
        val result = exporter.importRules(json)
        assertNull(result.error)
        assertEquals(3, result.rules.size)
        assertEquals("com.app1", result.rules[0].packageName)
        assertEquals("com.app2", result.rules[1].packageName)
        assertEquals("com.app3", result.rules[2].packageName)
    }
}
