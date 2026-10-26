package com.astraedus.nudge.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Blocked words & sites": the user's own additions to the content filter.
 *
 * Matching is deliberately blunt: a word is a raw substring of the URL bar text, so `sex` catches
 * sussex.ac.uk. The allow list is the correction path, and these tests pin both directions: what the
 * user's blocks catch, and what the allow list can and cannot take back.
 */
class ContentFilterBlocklistTest {

    private fun blocked(
        url: String,
        vararg entries: String,
        allow: List<String> = emptyList(),
        strictKeywords: Boolean = false
    ): Boolean = ContentFilterMatcher.isBlocked(
        url,
        emptySet(),
        strictKeywords,
        ContentFilterAllowlist(allow),
        ContentFilterBlocklist(entries.toList())
    )

    @Test
    fun `a user word blocks by raw substring`() {
        assertTrue(blocked("https://crypto.com/", "crypto"))
        assertTrue(blocked("https://www.google.com/search?q=cryptocurrency", "crypto"))
    }

    @Test
    fun `over-blocking is accepted, not designed around`() {
        assertTrue(blocked("https://www.sussex.ac.uk/", "sex"))
        assertTrue(blocked("https://sexeducation.com/", "sex"))
    }

    @Test
    fun `matching is case-insensitive in both directions`() {
        assertTrue(blocked("https://CRYPTO.com/", "crypto"))
        assertTrue(blocked("https://crypto.com/", "CRYPTO"))
    }

    @Test
    fun `a site entry covers its host and subdomains but not lookalikes`() {
        assertTrue(blocked("https://stake.com/", "stake.com"))
        assertTrue(blocked("https://www.stake.com/casino", "https://www.stake.com/x"))
        assertFalse(blocked("https://notstake.com/", "stake.com"))
    }

    @Test
    fun `a phrase matches plus and percent-encoded separators`() {
        assertTrue(blocked("https://www.google.com/search?q=crypto+gambling", "crypto gambling"))
        assertTrue(blocked("https://www.google.com/search?q=crypto%20gambling", "crypto gambling"))
        assertFalse(blocked("https://www.google.com/search?q=crypto+mining", "crypto gambling"))
    }

    @Test
    fun `the allow list rescues a user word on that host`() {
        assertFalse(blocked("https://sexeducation.com/", "sex", allow = listOf("sexeducation.com")))
        assertFalse(
            blocked("https://learn.sexeducation.com/", "sex", allow = listOf("sexeducation.com"))
        )
        assertTrue(
            blocked("https://notsexeducation.com/", "sex", allow = listOf("sexeducation.com"))
        )
    }

    @Test
    fun `the allow list phrase excuses a hit inside it`() {
        assertFalse(
            blocked("https://www.google.com/search?q=crypto+news", "crypto", allow = listOf("crypto news"))
        )
        assertTrue(
            blocked("https://www.google.com/search?q=crypto+bet", "crypto", allow = listOf("crypto news"))
        )
    }

    @Test
    fun `user blocks are live with strict matching off`() {
        assertTrue(blocked("https://crypto.com/", "crypto", strictKeywords = false))
    }

    @Test
    fun `the allow list never reaches the curated domain list`() {
        val blocked = ContentFilterMatcher.isBlocked(
            "https://blocked.example/",
            setOf("blocked.example"),
            false,
            ContentFilterAllowlist(listOf("blocked.example")),
            ContentFilterBlocklist.EMPTY
        )
        assertTrue(blocked)
    }

    @Test
    fun `the allow list never reaches a built-in keyword`() {
        val keyword = ContentFilterMatcher.DEFAULT_KEYWORDS.first()
        val blocked = ContentFilterMatcher.isBlocked(
            "https://example.com/$keyword",
            emptySet(),
            false,
            ContentFilterAllowlist(listOf("example.com")),
            ContentFilterBlocklist.EMPTY
        )
        assertTrue(blocked)
    }

    @Test
    fun `an empty list blocks nothing`() {
        assertFalse(blocked("https://crypto.com/"))
    }

    @Test
    fun `browser-internal schemes are skipped`() {
        assertFalse(blocked("chrome://settings/crypto", "crypto"))
        assertFalse(blocked("about:crypto", "crypto"))
    }

    @Test
    fun `entries are canonicalised like the allow list's`() {
        val list = ContentFilterBlocklist(
            listOf("https://www.CNC-Shop.com/parts", "  CRYPTO ", "!!!")
        )
        assertTrue(list.entries.containsAll(setOf("cnc-shop.com", "crypto")))
        assertFalse(list.entries.contains("!!!"))
    }
}
