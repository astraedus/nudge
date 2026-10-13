package com.astraedus.nudge.data.export

import com.astraedus.nudge.domain.ContentFilterAllowlist
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup format for "Allowed words & sites" (issue #78). The field-for-field round trip lives in
 * [SettingsExportTest]; this pins the list-specific rules: an empty list is a real value, entries are
 * isolated one by one, and a hand-edited file cannot store a form the app itself would refuse.
 */
class AllowlistExportTest {

    private val exporter = RuleExporter()

    private fun fileWithSettings(body: String) = """
        {
            "version": 1,
            "rules": [{"packageName": "com.app1", "mode": "DELAY"}],
            "settings": {$body}
        }
    """.trimIndent()

    private fun export(allowlist: List<String>?): String = exporter.exportRules(
        rules = emptyList(),
        groups = emptyList(),
        groupMembers = emptyMap(),
        settings = ExportedSettings(contentFilterAllowlist = allowlist)
    )

    private fun roundTrip(allowlist: List<String>?): ExportedSettings? =
        exporter.importRules(export(allowlist)).settings

    @Test
    fun `the allowlist survives an export-import round trip`() {
        assertEquals(listOf("bbc news", "cnc"), roundTrip(listOf("bbc news", "cnc"))?.contentFilterAllowlist)
    }

    @Test
    fun `it is written as a JSON array of strings`() {
        val array = JSONObject(export(listOf("cnc")))
            .getJSONObject("settings")
            .getJSONArray("contentFilterAllowlist")
        assertEquals(1, array.length())
        assertEquals("cnc", array.getString(0))
    }

    @Test
    fun `an empty list is a real value that restores no exceptions`() {
        assertEquals(emptyList<String>(), roundTrip(emptyList())?.contentFilterAllowlist)
    }

    @Test
    fun `a file without the key leaves the setting absent`() {
        val result = exporter.importRules(fileWithSettings(""""contentFilterStrictKeywords": true"""))
        assertNull(result.settings?.contentFilterAllowlist)
    }

    @Test
    fun `imported entries are canonicalised`() {
        val result = exporter.importRules(
            fileWithSettings(""""contentFilterAllowlist": ["  CNC ", "cnc", "https://www.Shop.com/x"]""")
        )
        assertEquals(listOf("cnc", "shop.com"), result.settings?.contentFilterAllowlist)
        assertEquals(0, result.invalidSettingsCount)
    }

    @Test
    fun `one unreadable entry is skipped and counted, the rest restore`() {
        val result = exporter.importRules(
            fileWithSettings(""""contentFilterAllowlist": ["cnc", 42, "!!!", "bbc news"]""")
        )
        assertEquals(listOf("bbc news", "cnc"), result.settings?.contentFilterAllowlist)
        assertEquals(2, result.invalidSettingsCount)
        assertTrue(result.invalidSettingsReasons.all { "contentFilterAllowlist" in it })
    }

    @Test
    fun `a non-list value is skipped without costing the other settings`() {
        val result = exporter.importRules(
            fileWithSettings(""""contentFilterAllowlist": "cnc", "contentFilterStrictKeywords": true""")
        )
        assertNull(result.settings?.contentFilterAllowlist)
        assertEquals(true, result.settings?.contentFilterStrictKeywords)
        assertEquals(1, result.invalidSettingsCount)
    }

    @Test
    fun `a file cannot exceed the entry cap`() {
        val many = (1..ContentFilterAllowlist.MAX_ENTRIES + 3).joinToString(",") { "\"word$it\"" }
        val result = exporter.importRules(fileWithSettings(""""contentFilterAllowlist": [$many]"""))
        assertEquals(ContentFilterAllowlist.MAX_ENTRIES, result.settings?.contentFilterAllowlist?.size)
        assertEquals(1, result.invalidSettingsCount)
    }
}
