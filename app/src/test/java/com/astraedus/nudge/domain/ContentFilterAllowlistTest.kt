package com.astraedus.nudge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Allowed words & sites" (issue #78): the user's exceptions to strict keyword matching.
 *
 * The matcher tests pass their OWN keyword list rather than [ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS],
 * so they pin the allowlist mechanism and not the current contents of a list another change curates.
 * Where the shipped lists are needed (the [ContentFilterMatcher.isBlocked] composition), a token is
 * DERIVED from them, never retyped.
 */
class ContentFilterAllowlistTest {

    private val keywords = listOf("cnc", "bbw", "bbc")

    private fun strictMatch(url: String, vararg entries: String) =
        ContentFilterMatcher.matchesQueryKeyword(url, keywords, ContentFilterAllowlist(entries.toList()))

    private val cncSearch = "https://www.google.com/search?q=cnc+machine"

    // ── the issue's case ──

    @Test
    fun `without an allowlist the ambiguous search blocks`() {
        assertTrue(ContentFilterMatcher.matchesQueryKeyword(cncSearch, keywords))
        assertTrue(strictMatch(cncSearch))
    }

    @Test
    fun `an allowlisted ambiguous word lets the search through`() {
        assertFalse(strictMatch(cncSearch, "cnc"))
    }

    @Test
    fun `allowlist matching is case-insensitive in both directions`() {
        assertFalse(strictMatch("https://www.google.com/search?q=CNC+Machine", "cnc"))
        assertFalse(strictMatch(cncSearch, "CNC"))
    }

    @Test
    fun `a non-allowlisted ambiguous word in the same search still blocks`() {
        assertTrue(strictMatch("https://www.google.com/search?q=cnc+bbw", "cnc"))
    }

    @Test
    fun `an allowlisted word unrelated to the hit excuses nothing`() {
        assertTrue(strictMatch(cncSearch, "machine"))
    }

    @Test
    fun `allowlist entries match whole words only`() {
        // "cnca" is not "cnc": the entry must not reach inside another word.
        assertTrue(strictMatch("https://duckduckgo.com/?q=cnc+cnca", "cnca"))
    }

    // ── phrases ──

    @Test
    fun `a phrase entry excuses the word only inside that phrase`() {
        assertFalse(strictMatch("https://www.bing.com/search?q=bbc+news+today", "bbc news"))
        assertTrue(strictMatch("https://www.bing.com/search?q=bbc", "bbc news"))
        assertTrue(strictMatch("https://www.bing.com/search?q=news+bbc", "bbc news"))
    }

    @Test
    fun `a phrase entry tolerates extra whitespace in the query`() {
        assertFalse(strictMatch("https://www.bing.com/search?q=bbc%20%20news", "bbc news"))
    }

    /**
     * The property Strict Mode's "removing is always free" rests on. A mask-then-rescan design fails
     * it: masking "machine shop" first eats half of "cnc machine" and leaves "cnc" exposed, so adding
     * an entry would BLOCK a search the smaller list allowed (and removing it would unblock one).
     */
    @Test
    fun `adding an overlapping entry never blocks what the smaller list allowed`() {
        val url = "https://www.google.com/search?q=cnc+machine+shop"
        assertFalse(strictMatch(url, "cnc machine"))
        assertFalse(strictMatch(url, "cnc machine", "machine shop"))
        assertFalse(strictMatch(url, "machine shop", "cnc machine"))
    }

    // ── sites ──

    @Test
    fun `an allowlisted site skips the strict layer for itself and its subdomains`() {
        assertFalse(strictMatch("https://cnc-shop.com/search?q=cnc", "cnc-shop.com"))
        assertFalse(strictMatch("https://www.cnc-shop.com/search?q=cnc", "cnc-shop.com"))
        assertFalse(strictMatch("https://parts.eu.cnc-shop.com/search?q=cnc", "cnc-shop.com"))
    }

    @Test
    fun `an allowlisted site does not cover a lookalike or its parent`() {
        assertTrue(strictMatch("https://badcnc-shop.com/search?q=cnc", "cnc-shop.com"))
        assertTrue(strictMatch("https://cnc-shop.com.evil.net/search?q=cnc", "cnc-shop.com"))
        assertTrue(strictMatch("https://shop.com/search?q=cnc", "parts.shop.com"))
    }

    @Test
    fun `an allowlisted site does not excuse the same search on another site`() {
        assertTrue(strictMatch(cncSearch, "cnc-shop.com"))
    }

    @Test
    fun `a pasted URL is stored as its site`() {
        assertEquals("cnc-shop.com", ContentFilterAllowlist.normalize("https://www.CNC-Shop.com/parts?x=1"))
        assertFalse(strictMatch("https://cnc-shop.com/search?q=cnc", "https://www.cnc-shop.com/parts"))
    }

    // ── the hard layers are out of reach ──

    private val blocklist = setOf("blockedsite.example")
    private val ambiguous = ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS.first()
    private val highSignal = ContentFilterMatcher.DEFAULT_KEYWORDS.first()

    private fun verdict(url: String, strict: Boolean, vararg entries: String) =
        ContentFilterMatcher.isBlocked(url, blocklist, strict, ContentFilterAllowlist(entries.toList()))

    @Test
    fun `the full verdict honours an allowlisted ambiguous word under strict matching`() {
        val url = "https://www.google.com/search?q=$ambiguous"
        assertTrue(verdict(url, strict = true))
        assertFalse(verdict(url, strict = true, ambiguous))
    }

    @Test
    fun `an allowlist entry can never rescue a site on the built-in domain list`() {
        val url = "https://www.blockedsite.example/search?q=$ambiguous"
        assertTrue(verdict(url, strict = true, "blockedsite.example", ambiguous))
        assertTrue(verdict(url, strict = false, "blockedsite.example"))
    }

    @Test
    fun `an allowlist entry can never rescue a high-signal keyword`() {
        val url = "https://www.google.com/search?q=$highSignal"
        assertTrue(verdict(url, strict = true, highSignal, "google.com"))
        assertTrue(verdict(url, strict = false, highSignal))
    }

    @Test
    fun `with strict matching off the allowlist changes nothing`() {
        val url = "https://www.google.com/search?q=$ambiguous"
        assertFalse(verdict(url, strict = false))
        assertFalse(verdict(url, strict = false, ambiguous))
    }

    // ── normalisation ──

    @Test
    fun `entries are trimmed, lowercased and whitespace-collapsed`() {
        assertEquals("bbc news", ContentFilterAllowlist.normalize("  BBC \t  News "))
        assertEquals("cnc", ContentFilterAllowlist.normalize("CNC"))
    }

    @Test
    fun `junk is rejected`() {
        assertNull(ContentFilterAllowlist.normalize(""))
        assertNull(ContentFilterAllowlist.normalize("   "))
        assertNull(ContentFilterAllowlist.normalize("!!!"))
        assertNull(ContentFilterAllowlist.normalize("a".repeat(ContentFilterAllowlist.MAX_ENTRY_LENGTH + 1)))
    }

    @Test
    fun `an entry of exactly the maximum length is kept`() {
        val max = "a".repeat(ContentFilterAllowlist.MAX_ENTRY_LENGTH)
        assertEquals(max, ContentFilterAllowlist.normalize(max))
    }

    @Test
    fun `only host-shaped single tokens are sites`() {
        assertTrue(ContentFilterAllowlist.isDomainEntry("cnc-shop.com"))
        assertFalse(ContentFilterAllowlist.isDomainEntry("cnc"))
        assertFalse(ContentFilterAllowlist.isDomainEntry("bbc news"))
        assertFalse(ContentFilterAllowlist.isDomainEntry("e.g."))
    }

    @Test
    fun `canonicalize de-duplicates, sorts and caps`() {
        assertEquals(listOf("bbc", "cnc"), ContentFilterAllowlist.canonicalize(listOf("CNC", "bbc", " cnc ", "")))
        val many = (1..ContentFilterAllowlist.MAX_ENTRIES + 5).map { "word$it" }
        assertEquals(ContentFilterAllowlist.MAX_ENTRIES, ContentFilterAllowlist.canonicalize(many).size)
    }

    @Test
    fun `the constructor drops invalid entries`() {
        assertEquals(setOf("cnc"), ContentFilterAllowlist(listOf("cnc", "  ", "!!!")).entries)
        assertTrue(ContentFilterAllowlist.EMPTY.isEmpty)
    }
}
