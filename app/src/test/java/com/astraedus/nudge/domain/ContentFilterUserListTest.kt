package com.astraedus.nudge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ContentFilterUserList]'s canonicalisation, the one form both user lists store, compare and export.
 *
 * Every case here is a form a user can actually type or paste, and each one used to be a silent
 * no-op entry: a wildcard that landed in the word list, a decomposed accent that never matched, a
 * zero-width space, a full list that dropped an arbitrary entry.
 */
class ContentFilterUserListTest {

    @Test
    fun `a wildcard entry becomes its site`() {
        assertEquals("stake.com", ContentFilterUserList.normalize("*.stake.com"))
        assertEquals("stake.com", ContentFilterUserList.normalize("*.WWW.stake.com/casino"))
    }

    @Test
    fun `a wildcard with nothing after it is not an entry`() {
        assertNull(ContentFilterUserList.normalize("*."))
    }

    @Test
    fun `unicode is NFC-normalised so both spellings are one entry`() {
        val nfc = "caf\u00E9"
        val nfd = "cafe\u0301"
        assertEquals(nfc, ContentFilterUserList.normalize(nfd))
        assertEquals(listOf(nfc), ContentFilterUserList.canonicalize(listOf(nfc, nfd)))
    }

    @Test
    fun `zero-width characters cannot make an inert entry`() {
        assertEquals("crypto", ContentFilterUserList.normalize("crypto\u200B"))
    }

    @Test
    fun `the cap is deterministic when the list is full`() {
        val entries = (1..ContentFilterUserList.MAX_ENTRIES + 5).map { "word$it" }
        val kept = ContentFilterUserList.canonicalize(entries)
        assertEquals(ContentFilterUserList.MAX_ENTRIES, kept.size)
        assertEquals("input order must not decide which entries survive", kept, ContentFilterUserList.canonicalize(entries.shuffled()))
        assertEquals(kept.sorted(), kept)
    }
}
