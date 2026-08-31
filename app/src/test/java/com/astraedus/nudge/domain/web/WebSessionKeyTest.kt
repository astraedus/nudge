package com.astraedus.nudge.domain.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSessionKeyTest {

    @Test
    fun `a domain becomes a prefixed key`() {
        assertEquals("web:instagram.com", WebSessionKey.forDomain("instagram.com"))
    }

    /**
     * The whole point of normalising on the way in: `www.instagram.com` and `instagram.com` must be
     * ONE session, or a user could refill a time budget by switching between the two spellings the
     * same site serves.
     */
    @Test
    fun `subdomain spellings of one site share a key`() {
        val key = WebSessionKey.forDomain("instagram.com")
        assertEquals(key, WebSessionKey.forDomain("www.instagram.com"))
        assertEquals(key, WebSessionKey.forDomain("m.instagram.com"))
        assertEquals(key, WebSessionKey.forDomain("  INSTAGRAM.COM "))
    }

    @Test
    fun `different sites get different keys`() {
        assertTrue(WebSessionKey.forDomain("instagram.com") != WebSessionKey.forDomain("youtube.com"))
    }

    @Test
    fun `a blank domain has no key`() {
        assertNull(WebSessionKey.forDomain(""))
        assertNull(WebSessionKey.forDomain("   "))
    }

    /**
     * The key shares a namespace with real package names, so telling them apart has to be exact --
     * a rule package must never be mistaken for a website session or vice versa.
     */
    @Test
    fun `a real package is never a web key`() {
        assertFalse(WebSessionKey.isWebKey("com.instagram.android"))
        assertFalse(WebSessionKey.isWebKey("com.android.chrome"))
        assertFalse(WebSessionKey.isWebKey("web"))
        assertFalse(WebSessionKey.isWebKey("web:"))
        assertNull(WebSessionKey.domainOf("com.instagram.android"))
    }

    @Test
    fun `a key round-trips to its domain`() {
        val key = WebSessionKey.forDomain("youtube.com")!!
        assertTrue(WebSessionKey.isWebKey(key))
        assertEquals("youtube.com", WebSessionKey.domainOf(key))
    }
}
