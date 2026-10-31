package com.astraedus.nudge.ui.screens.settings

import com.astraedus.nudge.domain.ContentFilterUserList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block list editor's decisions: [blocklistSubmit] (adding is strengthening, so never gated) and
 * [blocklistRemoveNeedsUnlock] (removal weakens, so Strict Mode gates it).
 */
class ContentFilterBlocklistEditorTest {

    @Test
    fun `removing a block needs the unlock under strict mode, and only then`() {
        val current = setOf("crypto", "stake.com")
        assertTrue(blocklistRemoveNeedsUnlock("crypto", current, strictModeEnabled = true))
        assertFalse(blocklistRemoveNeedsUnlock("crypto", current, strictModeEnabled = false))
    }

    @Test
    fun `a removal that loses nothing is never gated`() {
        assertFalse(
            blocklistRemoveNeedsUnlock("absent", setOf("crypto"), strictModeEnabled = true)
        )
    }

    @Test
    fun `a new entry is stored in canonical form with no challenge`() {
        assertEquals(
            UserListSubmit.Add(entry = "crypto", needsUnlock = false),
            blocklistSubmit("  CRYPTO ", current = emptySet())
        )
    }

    @Test
    fun `a pasted URL becomes its site`() {
        assertEquals(
            UserListSubmit.Add(entry = "stake.com", needsUnlock = false),
            blocklistSubmit("https://www.stake.com/casino", current = emptySet())
        )
    }

    @Test
    fun `junk is rejected with a reason`() {
        assertEquals(
            UserListSubmit.Rejected(BLOCKLIST_INVALID),
            blocklistSubmit("!!!", current = emptySet())
        )
    }

    @Test
    fun `a duplicate is rejected`() {
        assertEquals(
            UserListSubmit.Rejected(BLOCKLIST_DUPLICATE),
            blocklistSubmit("CRYPTO", current = setOf("crypto"))
        )
    }

    @Test
    fun `a full list refuses another entry`() {
        val full = (1..ContentFilterUserList.MAX_ENTRIES).map { "word$it" }.toSet()
        assertEquals(
            UserListSubmit.Rejected(BLOCKLIST_FULL),
            blocklistSubmit("crypto", current = full)
        )
    }
}
