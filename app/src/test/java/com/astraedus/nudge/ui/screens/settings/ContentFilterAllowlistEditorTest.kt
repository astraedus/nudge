package com.astraedus.nudge.ui.screens.settings

import com.astraedus.nudge.domain.ContentFilterAllowlist
import com.astraedus.nudge.domain.ContentFilterUserList
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Settings editor's one decision ([allowlistSubmit]): what a submitted entry becomes, and whether
 * Strict Mode stands in front of it.
 */
class ContentFilterAllowlistEditorTest {

    @Test
    fun `a new entry is stored in canonical form with no challenge when strict mode is off`() {
        assertEquals(
            UserListSubmit.Add(entry = "cnc", needsUnlock = false),
            allowlistSubmit("  CNC ", current = emptySet(), strictModeEnabled = false)
        )
    }

    @Test
    fun `a new entry under strict mode needs the unlock challenge`() {
        assertEquals(
            UserListSubmit.Add(entry = "cnc", needsUnlock = true),
            allowlistSubmit("cnc", current = setOf("bbc news"), strictModeEnabled = true)
        )
    }

    @Test
    fun `a pasted URL becomes its site`() {
        assertEquals(
            UserListSubmit.Add(entry = "cnc-shop.com", needsUnlock = false),
            allowlistSubmit("https://www.cnc-shop.com/catalog", current = emptySet(), strictModeEnabled = false)
        )
    }

    @Test
    fun `junk is rejected with a reason`() {
        assertEquals(
            UserListSubmit.Rejected(ALLOWLIST_INVALID),
            allowlistSubmit("!!!", current = emptySet(), strictModeEnabled = false)
        )
    }

    @Test
    fun `a duplicate is rejected before any challenge is raised`() {
        assertEquals(
            UserListSubmit.Rejected(ALLOWLIST_DUPLICATE),
            allowlistSubmit("CNC", current = setOf("cnc"), strictModeEnabled = true)
        )
    }

    @Test
    fun `a full list refuses another entry`() {
        val full = (1..ContentFilterUserList.MAX_ENTRIES).map { "word$it" }.toSet()
        assertEquals(
            UserListSubmit.Rejected(ALLOWLIST_FULL),
            allowlistSubmit("cnc", current = full, strictModeEnabled = false)
        )
    }
}
