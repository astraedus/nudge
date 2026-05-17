package com.astraedus.nudge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDomainMatcherTest {

    // ═══ extractDomain tests ═══

    @Test
    fun `extractDomain with path returns base domain`() {
        assertEquals("instagram.com", WebDomainMatcher.extractDomain("instagram.com/reels"))
    }

    @Test
    fun `extractDomain with full https URL and path`() {
        assertEquals("youtube.com", WebDomainMatcher.extractDomain("https://www.youtube.com/watch?v=abc"))
    }

    @Test
    fun `extractDomain strips mobile subdomain`() {
        assertEquals("youtube.com", WebDomainMatcher.extractDomain("m.youtube.com"))
    }

    @Test
    fun `extractDomain with bare domain`() {
        assertEquals("instagram.com", WebDomainMatcher.extractDomain("instagram.com"))
    }

    @Test
    fun `extractDomain with www subdomain`() {
        assertEquals("instagram.com", WebDomainMatcher.extractDomain("www.instagram.com"))
    }

    @Test
    fun `extractDomain returns null for empty string`() {
        assertNull(WebDomainMatcher.extractDomain(""))
    }

    @Test
    fun `extractDomain returns null for blank string`() {
        assertNull(WebDomainMatcher.extractDomain("   "))
    }

    @Test
    fun `extractDomain returns null for chrome internal URLs`() {
        assertNull(WebDomainMatcher.extractDomain("chrome://newtab"))
    }

    @Test
    fun `extractDomain returns null for about URLs`() {
        assertNull(WebDomainMatcher.extractDomain("about:blank"))
    }

    @Test
    fun `extractDomain with http scheme`() {
        assertEquals("tiktok.com", WebDomainMatcher.extractDomain("http://www.tiktok.com/foryou"))
    }

    @Test
    fun `extractDomain with single-word host returns null`() {
        // "localhost" has no dot, so it's not a valid domain
        assertNull(WebDomainMatcher.extractDomain("localhost:3000/api"))
    }

    @Test
    fun `extractDomain with port number on valid domain`() {
        assertEquals("example.com", WebDomainMatcher.extractDomain("example.com:8080/path"))
    }

    @Test
    fun `extractDomain with query params and fragment`() {
        assertEquals("youtube.com", WebDomainMatcher.extractDomain("https://youtube.com/watch?v=abc#t=10"))
    }

    @Test
    fun `extractDomain with lm subdomain`() {
        assertEquals("facebook.com", WebDomainMatcher.extractDomain("lm.facebook.com/link"))
    }

    @Test
    fun `extractDomain with unknown subdomain keeps full domain`() {
        assertEquals("api.example.com", WebDomainMatcher.extractDomain("api.example.com/v1/users"))
    }

    // ═══ matches tests ═══

    @Test
    fun `matches returns true for exact domain in list`() {
        assertTrue(WebDomainMatcher.matches("instagram.com/p/abc", "instagram.com,facebook.com"))
    }

    @Test
    fun `matches returns false when domain not in list`() {
        assertFalse(WebDomainMatcher.matches("reddit.com", "instagram.com"))
    }

    @Test
    fun `matches with www subdomain against bare domain rule`() {
        assertTrue(WebDomainMatcher.matches("www.instagram.com/reels", "instagram.com"))
    }

    @Test
    fun `matches with mobile subdomain against bare domain rule`() {
        assertTrue(WebDomainMatcher.matches("m.youtube.com/shorts", "youtube.com"))
    }

    @Test
    fun `matches with full URL against domain list`() {
        assertTrue(WebDomainMatcher.matches("https://www.tiktok.com/@user", "tiktok.com,www.tiktok.com"))
    }

    @Test
    fun `matches returns false for empty URL`() {
        assertFalse(WebDomainMatcher.matches("", "instagram.com"))
    }

    @Test
    fun `matches returns false for internal URL`() {
        assertFalse(WebDomainMatcher.matches("chrome://settings", "settings.com"))
    }

    @Test
    fun `matches is case insensitive`() {
        assertTrue(WebDomainMatcher.matches("Instagram.com/explore", "instagram.com"))
    }

    @Test
    fun `matches handles spaces in domain list`() {
        assertTrue(WebDomainMatcher.matches("youtube.com", "instagram.com, youtube.com , tiktok.com"))
    }

    @Test
    fun `matches with rule domain having www prefix normalizes`() {
        assertTrue(WebDomainMatcher.matches("instagram.com/p/123", "www.instagram.com"))
    }
}
