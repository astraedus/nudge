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
    fun `urlBarViewIdsFor Chrome resolves prefixed and bare ids in order`() {
        assertEquals(
            listOf(
                "com.android.chrome:id/url_bar", "url_bar",
                "com.android.chrome:id/omnibox_url_text", "omnibox_url_text"
            ),
            WebDomainDetector.urlBarViewIdsFor("com.android.chrome")
        )
    }

    @Test
    fun `urlBarViewIdsFor Firefox includes bare Compose testTag first`() {
        val ids = WebDomainDetector.urlBarViewIdsFor("org.mozilla.firefox")
        assertEquals(
            listOf(
                "org.mozilla.firefox:id/ADDRESSBAR_URL_BOX", "ADDRESSBAR_URL_BOX",
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title", "url_bar_title"
            ),
            ids
        )
        // The bare Compose testTag is the only form modern Firefox matches.
        assertTrue("ADDRESSBAR_URL_BOX" in ids)
    }

    @Test
    fun `urlBarViewIdsFor Samsung Internet resolves prefixed and bare location bar ids`() {
        assertEquals(
            listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text", "location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/sites_url", "sites_url"
            ),
            WebDomainDetector.urlBarViewIdsFor("com.sec.android.app.sbrowser")
        )
    }

    @Test
    fun `urlBarViewIdsFor unknown package returns empty`() {
        assertTrue(WebDomainDetector.urlBarViewIdsFor("com.instagram.android").isEmpty())
    }

    // ---- cleanAddressBarText (pure address-bar normalization) ----

    @Test
    fun `cleanAddressBarText strips Firefox hint suffix from a bare domain`() {
        assertEquals(
            "instagram.com",
            WebDomainDetector.cleanAddressBarText(" instagram.com. Search or enter address")
        )
    }

    @Test
    fun `cleanAddressBarText strips Firefox hint suffix but keeps the path`() {
        assertEquals(
            "example.com/some/path",
            WebDomainDetector.cleanAddressBarText(" example.com/some/path. Search or enter address")
        )
    }

    @Test
    fun `cleanAddressBarText leaves a plain URL without period-space unchanged`() {
        assertEquals(
            "youtube.com",
            WebDomainDetector.cleanAddressBarText("youtube.com")
        )
    }

    @Test
    fun `cleanAddressBarText returns null for null or blank input`() {
        assertNull(WebDomainDetector.cleanAddressBarText(null))
        assertNull(WebDomainDetector.cleanAddressBarText(""))
        assertNull(WebDomainDetector.cleanAddressBarText("   "))
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
    fun `detectUrl finds modern Firefox URL via tree traversal when ByViewId returns empty`() {
        // Simulate the real device: findAccessibilityNodeInfosByViewId matches NOTHING
        // (the platform won't match the bare Compose testTag), but a child node in the
        // tree carries viewIdResourceName == "ADDRESSBAR_URL_BOX" with the URL in its
        // contentDescription and a blank text.
        val urlNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { urlNode.viewIdResourceName } returns "ADDRESSBAR_URL_BOX"
        every { urlNode.text } returns ""
        every { urlNode.contentDescription } returns " instagram.com. Search or enter address"
        every { urlNode.childCount } returns 0

        // An unrelated sibling with no matching id, to exercise the DFS skipping it.
        val otherNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { otherNode.viewIdResourceName } returns "some_other_view"
        every { otherNode.childCount } returns 0

        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.viewIdResourceName } returns null
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.childCount } returns 2
        every { root.getChild(0) } returns otherNode
        every { root.getChild(1) } returns urlNode

        assertEquals("instagram.com", detector.detectUrl(root, "org.mozilla.firefox"))
    }

    @Test
    fun `detectUrl traversal returns null and does not NPE on a tree with null children`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.viewIdResourceName } returns null
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        // childCount claims children but getChild returns null for them.
        every { root.childCount } returns 3
        every { root.getChild(any()) } returns null

        assertNull(detector.detectUrl(root, "org.mozilla.firefox"))
    }

    @Test
    fun `detectUrl traversal respects the node cap on an unbounded tree without a match`() {
        // Every node reports a child, so DFS would run forever without the maxNodes cap.
        val nonMatch = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { nonMatch.viewIdResourceName } returns "not_a_url_bar"
        every { nonMatch.childCount } returns 1
        every { nonMatch.getChild(any()) } answers {
            mockk<AccessibilityNodeInfo>(relaxed = true).also {
                every { it.viewIdResourceName } returns "not_a_url_bar"
                every { it.childCount } returns 1
                every { it.getChild(any()) } returns nonMatch
            }
        }

        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.viewIdResourceName } returns null
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.childCount } returns 1
        every { root.getChild(any()) } returns nonMatch

        // Should terminate (cap) and return null rather than loop forever.
        assertNull(detector.detectUrl(root, "org.mozilla.firefox"))
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
