package com.astraedus.nudge.domain

import org.junit.Assert.assertFalse
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
}
