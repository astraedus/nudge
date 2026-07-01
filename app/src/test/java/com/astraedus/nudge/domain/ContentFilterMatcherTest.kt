package com.astraedus.nudge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterMatcherTest {

    private val blocklist = setOf(
        "blockedsite.com",
        "anotherblocked.net"
    )

    // ---- matchesDomain ----

    @Test
    fun `matchesDomain exact base domain`() {
        assertTrue(ContentFilterMatcher.matchesDomain("https://blockedsite.com/page", blocklist))
    }

    @Test
    fun `matchesDomain www-stripped base`() {
        assertTrue(ContentFilterMatcher.matchesDomain("https://www.blockedsite.com", blocklist))
    }

    @Test
    fun `matchesDomain subdomain of blocked base`() {
        // m.blockedsite.com -> extractDomain normalizes m. prefix to blockedsite.com
        assertTrue(ContentFilterMatcher.matchesDomain("m.blockedsite.com/foo", blocklist))
    }

    @Test
    fun `matchesDomain deep subdomain via parent strip`() {
        // cdn.assets.anotherblocked.net is not a known-subdomain prefix, but parent
        // stripping should reach anotherblocked.net.
        assertTrue(ContentFilterMatcher.matchesDomain("https://cdn.assets.anotherblocked.net/x", blocklist))
    }

    @Test
    fun `matchesDomain bare domain`() {
        assertTrue(ContentFilterMatcher.matchesDomain("blockedsite.com", blocklist))
    }

    @Test
    fun `matchesDomain non-match mainstream site`() {
        assertFalse(ContentFilterMatcher.matchesDomain("https://wikipedia.org/wiki/Cat", blocklist))
    }

    @Test
    fun `matchesDomain internal scheme returns false`() {
        assertFalse(ContentFilterMatcher.matchesDomain("chrome://settings", blocklist))
        assertFalse(ContentFilterMatcher.matchesDomain("about:blank", blocklist))
    }

    @Test
    fun `matchesDomain blank returns false`() {
        assertFalse(ContentFilterMatcher.matchesDomain("   ", blocklist))
    }

    @Test
    fun `matchesDomain empty blocklist returns false`() {
        assertFalse(ContentFilterMatcher.matchesDomain("blockedsite.com", emptySet()))
    }

    // ---- matchesKeyword ----

    private val keywords = ContentFilterMatcher.DEFAULT_KEYWORDS

    @Test
    fun `matchesKeyword in path`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://somesite.tld/porn/videos", keywords))
    }

    @Test
    fun `matchesKeyword in query string`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://google.com/search?q=xvideos", keywords))
    }

    @Test
    fun `matchesKeyword in host of unknown domain`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://freepornsite.example/", keywords))
    }

    @Test
    fun `matchesKeyword case insensitive`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://site.tld/PORN", keywords))
    }

    @Test
    fun `matchesKeyword non-match mainstream`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("https://www.wikipedia.org/wiki/Cat", keywords))
        assertFalse(ContentFilterMatcher.matchesKeyword("https://news.ycombinator.com", keywords))
    }

    @Test
    fun `matchesKeyword false-positive guard sussex`() {
        // "sussex" contains "sex" but we deliberately do NOT use "sex" as a token.
        assertFalse(ContentFilterMatcher.matchesKeyword("https://www.sussex.gov.uk/services", keywords))
    }

    @Test
    fun `matchesKeyword false-positive guard essex and analysis`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("https://essex.gov.uk", keywords))
        assertFalse(ContentFilterMatcher.matchesKeyword("https://stats.com/analysis/report", keywords))
        assertFalse(ContentFilterMatcher.matchesKeyword("https://maps.com/panama-canal", keywords))
    }

    @Test
    fun `matchesKeyword internal scheme returns false`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("chrome://flags#porn", keywords))
        assertFalse(ContentFilterMatcher.matchesKeyword("about:blank", keywords))
    }

    @Test
    fun `matchesKeyword blank returns false`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("   ", keywords))
    }

    @Test
    fun `matchesKeyword empty keyword list returns false`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("https://anything.tld/porn", emptyList()))
    }

    // ---- new DEFAULT_KEYWORDS additions ----

    @Test
    fun `matchesKeyword new coined tokens match`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://redgifs.com/watch/x", keywords))
        assertTrue(ContentFilterMatcher.matchesKeyword("https://site.tld/porngif/123", keywords))
        assertTrue(ContentFilterMatcher.matchesKeyword("https://stripchat.com", keywords))
    }

    @Test
    fun `matchesKeyword benign gif URL does not match (no bare gif token)`() {
        // Guards that we did NOT add bare "gif" — a benign giphy URL must not match.
        assertFalse(ContentFilterMatcher.matchesKeyword("https://giphy.com/gifs/cat", keywords))
    }

    // ---- extractSearchQuery ----

    @Test
    fun `extractSearchQuery google q param decodes`() {
        assertEquals("bbc porn", ContentFilterMatcher.extractSearchQuery("https://www.google.com/search?q=bbc%20porn"))
    }

    @Test
    fun `extractSearchQuery plus decodes to space`() {
        assertEquals("some terms", ContentFilterMatcher.extractSearchQuery("https://www.google.com/search?q=some+terms"))
    }

    @Test
    fun `extractSearchQuery ddg q param`() {
        assertEquals("cats", ContentFilterMatcher.extractSearchQuery("https://duckduckgo.com/?q=cats"))
    }

    @Test
    fun `extractSearchQuery bing q param`() {
        assertEquals("dogs", ContentFilterMatcher.extractSearchQuery("https://www.bing.com/search?q=dogs"))
    }

    @Test
    fun `extractSearchQuery yandex text param`() {
        assertEquals("weather", ContentFilterMatcher.extractSearchQuery("https://yandex.com/search/?text=weather"))
    }

    @Test
    fun `extractSearchQuery baidu wd param`() {
        assertEquals("news", ContentFilterMatcher.extractSearchQuery("https://www.baidu.com/s?wd=news"))
    }

    @Test
    fun `extractSearchQuery amazon k param`() {
        assertEquals("usb cable", ContentFilterMatcher.extractSearchQuery("https://www.amazon.com/s?k=usb+cable"))
    }

    @Test
    fun `extractSearchQuery youtube search_query param`() {
        assertEquals("music", ContentFilterMatcher.extractSearchQuery("https://www.youtube.com/results?search_query=music"))
    }

    @Test
    fun `extractSearchQuery path style search`() {
        assertEquals("some terms", ContentFilterMatcher.extractSearchQuery("https://example.com/search/some+terms"))
    }

    @Test
    fun `extractSearchQuery path style s`() {
        assertEquals("query here", ContentFilterMatcher.extractSearchQuery("https://example.com/s/query%20here"))
    }

    @Test
    fun `extractSearchQuery lowercases result`() {
        assertEquals("bbc news", ContentFilterMatcher.extractSearchQuery("https://www.google.com/search?q=BBC+News"))
    }

    @Test
    fun `extractSearchQuery non-search URL returns null`() {
        assertNull(ContentFilterMatcher.extractSearchQuery("https://news.site/article"))
    }

    @Test
    fun `extractSearchQuery internal scheme returns null`() {
        assertNull(ContentFilterMatcher.extractSearchQuery("chrome://settings?q=bbc"))
    }

    @Test
    fun `extractSearchQuery blank returns null`() {
        assertNull(ContentFilterMatcher.extractSearchQuery("   "))
    }

    // ---- matchesQueryKeyword ----

    private val ambiguous = ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS

    @Test
    fun `matchesQueryKeyword single term`() {
        assertTrue(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=bbc", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword multi word query with slang`() {
        assertTrue(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=bbc+porn", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword host only never inspected`() {
        // bbc.com news has no query -> host is NEVER inspected -> false.
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://bbc.com/news", ambiguous))
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://www.bbc.co.uk", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword whole word guard`() {
        // "clubbc" / "abbcd" as query terms contain "bbc" as a substring but not a whole word.
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=clubbc", ambiguous))
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=abbcd", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword legit news query with the word bbc still matches`() {
        // Intended aggressive-opt-in tradeoff: a legit multi-word news query containing the
        // WHOLE WORD "bbc" (e.g. "bbc news") WILL return true. This is the accepted cost of
        // the opt-in strict-keyword mode — the host is never inspected, but the query is.
        assertTrue(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=bbc+news", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword non-search URL returns false`() {
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://news.site/article", ambiguous))
    }
}
