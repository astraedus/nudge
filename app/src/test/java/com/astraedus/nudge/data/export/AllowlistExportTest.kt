package com.astraedus.nudge.data.export

import com.astraedus.nudge.domain.ContentFilterAllowlist
import com.astraedus.nudge.domain.ContentFilterUserList
import com.astraedus.nudge.domain.lock.ImportedSettingsWeakening
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val many = (1..ContentFilterUserList.MAX_ENTRIES + 3).joinToString(",") { "\"word$it\"" }
        val result = exporter.importRules(fileWithSettings(""""contentFilterAllowlist": [$many]"""))
        assertEquals(ContentFilterUserList.MAX_ENTRIES, result.settings?.contentFilterAllowlist?.size)
        assertEquals(1, result.invalidSettingsCount)
    }

    @Test
    fun `the block list survives an export-import round trip`() {
        val json = exporter.exportRules(
            rules = emptyList(),
            groups = emptyList(),
            groupMembers = emptyMap(),
            settings = ExportedSettings(contentFilterBlocklist = listOf("crypto", "stake.com"))
        )
        val settings = JSONObject(json).getJSONObject("settings")
        assertEquals("crypto", settings.getJSONArray("contentFilterBlocklist").getString(0))
        assertEquals(
            listOf("crypto", "stake.com"),
            exporter.importRules(json).settings?.contentFilterBlocklist
        )
    }

    @Test
    fun `an empty block list is a real value`() {
        val result = exporter.importRules(fileWithSettings(""""contentFilterBlocklist": []"""))
        assertEquals(emptyList<String>(), result.settings?.contentFilterBlocklist)
    }

    @Test
    fun `the block list cannot exceed the entry cap either`() {
        val many = (1..ContentFilterUserList.MAX_ENTRIES + 3).joinToString(",") { "\"word$it\"" }
        val result = exporter.importRules(
            fileWithSettings(""""contentFilterBlocklist": [$many]""")
        )
        assertEquals(
            ContentFilterUserList.MAX_ENTRIES,
            result.settings?.contentFilterBlocklist?.size
        )
        assertEquals(1, result.invalidSettingsCount)
    }

    /** A backup from a build older than the block list: the device keeps its blocks, no challenge. */
    @Test
    fun `a file without the block list leaves the device's blocks alone`() {
        val incoming = exporter.importRules(
            fileWithSettings(""""contentFilterStrictKeywords": true""")
        ).settings
        assertNull(incoming?.contentFilterBlocklist)
        assertFalse(
            ImportedSettingsWeakening.isWeakening(
                current = ExportedSettings(contentFilterBlocklist = listOf("crypto")),
                incoming = incoming!!
            )
        )
    }

    /**
     * The importer's cap runs BEFORE the Strict Mode check sees the list. A file holding every block
     * on the device plus one more that sorts first would, once capped, drop the device's last block:
     * that must still read as weakening.
     */
    @Test
    fun `a file whose cap drops one of the device's blocks is weakening`() {
        val device = (1..ContentFilterUserList.MAX_ENTRIES).map { "word%03d".format(it) }
        val body = (listOf("aaa") + device).joinToString(",") { "\"$it\"" }
        val incoming = exporter.importRules(
            fileWithSettings(""""contentFilterBlocklist": [$body]""")
        ).settings!!
        assertTrue(
            ImportedSettingsWeakening.isWeakening(
                current = ExportedSettings(contentFilterBlocklist = device),
                incoming = incoming
            )
        )
    }
}
