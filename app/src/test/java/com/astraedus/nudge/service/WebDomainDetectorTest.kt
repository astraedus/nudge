package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDomainDetectorTest {

    private val detector = WebDomainDetector()

    // ---- isBrowser ----

    @Test
    fun `isBrowser returns true for Chrome`() {
        assertTrue(detector.isBrowser("com.android.chrome"))
    }

    @Test
    fun `isBrowser returns true for Firefox`() {
        assertTrue(detector.isBrowser("org.mozilla.firefox"))
    }

    @Test
    fun `isBrowser returns true for Samsung Internet`() {
        assertTrue(detector.isBrowser("com.sec.android.app.sbrowser"))
    }

    @Test
    fun `isBrowser returns true for Brave Edge Opera DuckDuckGo`() {
        assertTrue(detector.isBrowser("com.brave.browser"))
        assertTrue(detector.isBrowser("com.microsoft.emmx"))
        assertTrue(detector.isBrowser("com.opera.browser"))
        assertTrue(detector.isBrowser("com.duckduckgo.mobile.android"))
    }

    @Test
    fun `isBrowser returns false for Instagram`() {
        assertFalse(detector.isBrowser("com.instagram.android"))
    }

    @Test
    fun `isBrowser returns false for empty string`() {
        assertFalse(detector.isBrowser(""))
    }

    // ---- urlBarViewIdsFor (pure id resolution) ----

    @Test
    fun `urlBarViewIdsFor Chrome resolves fully-qualified ids in order`() {
        assertEquals(
            listOf("com.android.chrome:id/url_bar", "com.android.chrome:id/omnibox_url_text"),
            WebDomainDetector.urlBarViewIdsFor("com.android.chrome")
        )
    }

    @Test
    fun `urlBarViewIdsFor Firefox resolves gecko toolbar id`() {
        assertEquals(
            listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title"
            ),
            WebDomainDetector.urlBarViewIdsFor("org.mozilla.firefox")
        )
    }

    @Test
    fun `urlBarViewIdsFor Samsung Internet resolves location bar id`() {
        assertEquals(
            listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/sites_url"
            ),
            WebDomainDetector.urlBarViewIdsFor("com.sec.android.app.sbrowser")
        )
    }

    @Test
    fun `urlBarViewIdsFor unknown package returns empty`() {
        assertTrue(WebDomainDetector.urlBarViewIdsFor("com.instagram.android").isEmpty())
    }

    // ---- detectUrl (mockk on AccessibilityNodeInfo) ----

    @Test
    fun `detectUrl returns null for null root node`() {
        assertNull(detector.detectUrl(null, "com.android.chrome"))
    }

    @Test
    fun `detectUrl reads Chrome url_bar`() {
        val urlNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { urlNode.text } returns "instagram.com"
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar") } returns listOf(urlNode)

        assertEquals("instagram.com", detector.detectUrl(root, "com.android.chrome"))
    }

    @Test
    fun `detectUrl reads Firefox gecko toolbar id`() {
        val urlNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { urlNode.text } returns "example.org"
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        // First id returns empty, so the detector should fall through to it.
        every {
            root.findAccessibilityNodeInfosByViewId("org.mozilla.firefox:id/mozac_browser_toolbar_url_view")
        } returns listOf(urlNode)

        assertEquals("example.org", detector.detectUrl(root, "org.mozilla.firefox"))
    }

    @Test
    fun `detectUrl reads Samsung Internet location bar`() {
        val urlNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { urlNode.text } returns "youtube.com"
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every {
            root.findAccessibilityNodeInfosByViewId("com.sec.android.app.sbrowser:id/location_bar_edit_text")
        } returns listOf(urlNode)

        assertEquals("youtube.com", detector.detectUrl(root, "com.sec.android.app.sbrowser"))
    }

    @Test
    fun `detectUrl falls through to second id when first is empty`() {
        val urlNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { urlNode.text } returns "site.com"
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar") } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/omnibox_url_text") } returns listOf(urlNode)

        assertEquals("site.com", detector.detectUrl(root, "com.android.chrome"))
    }

    @Test
    fun `detectUrl returns null when no node matches`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()

        assertNull(detector.detectUrl(root, "com.android.chrome"))
    }

    @Test
    fun `detectUrl swallows exceptions from node lookup`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findAccessibilityNodeInfosByViewId(any()) } throws RuntimeException("boom")

        assertNull(detector.detectUrl(root, "com.android.chrome"))
    }
}
