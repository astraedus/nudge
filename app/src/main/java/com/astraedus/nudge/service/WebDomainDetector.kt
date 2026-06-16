package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects the current URL/domain shown in a browser's URL bar by inspecting the
 * accessibility tree.
 *
 * Supports multiple browsers via [BROWSER_URL_BAR_IDS]: each package maps to an
 * ordered list of candidate URL-bar resource id *suffixes* (the part after
 * `<pkg>:id/`). [detectUrl] tries each fully-qualified id in turn until one
 * yields non-blank text.
 */
@Singleton
class WebDomainDetector @Inject constructor() {

    companion object {
        /**
         * Per-package URL-bar view id suffixes (the portion after `<pkg>:id/`),
         * tried in order. Covers Chromium-family, Firefox-family, Samsung
         * Internet, Edge, Opera, DuckDuckGo, Brave, Kiwi.
         */
        val BROWSER_URL_BAR_IDS: Map<String, List<String>> = mapOf(
            // Chromium family
            "com.android.chrome" to listOf("url_bar", "omnibox_url_text"),
            "com.chrome.beta" to listOf("url_bar", "omnibox_url_text"),
            "com.chrome.dev" to listOf("url_bar", "omnibox_url_text"),
            "com.brave.browser" to listOf("url_bar", "omnibox_url_text"),
            "com.microsoft.emmx" to listOf("url_bar", "omnibox_url_text"),
            "com.kiwibrowser.browser" to listOf("url_bar", "omnibox_url_text"),
            // Firefox family (GeckoView toolbar)
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            "org.mozilla.fenix" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            // Samsung Internet
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text", "sites_url"),
            // Opera
            "com.opera.browser" to listOf("url_field", "url_bar"),
            "com.opera.mini.native" to listOf("url_field", "url_bar"),
            // DuckDuckGo
            "com.duckduckgo.mobile.android" to listOf("omnibarTextInput")
        )

        /**
         * Resolve the fully-qualified candidate URL-bar view ids for a package.
         * Pure; unit-testable without Android. Returns empty list if the package
         * is not a recognized browser.
         */
        fun urlBarViewIdsFor(packageName: String): List<String> {
            val suffixes = BROWSER_URL_BAR_IDS[packageName] ?: return emptyList()
            return suffixes.map { "$packageName:id/$it" }
        }
    }

    fun isBrowser(packageName: String): Boolean = packageName in BROWSER_URL_BAR_IDS

    /**
     * Attempt to read the URL/domain from the browser's URL bar via the
     * accessibility tree. Resilient: any node-resolution failure is swallowed and
     * every fetched node is recycled.
     *
     * @param rootNode The rootInActiveWindow from the accessibility service
     * @param packageName The foreground browser package (selects which view ids
     *   to probe). When null, falls back to trying all known ids.
     * @return The text content of the URL bar, or null if not found
     */
    fun detectUrl(rootNode: AccessibilityNodeInfo?, packageName: String? = null): String? {
        if (rootNode == null) return null

        val viewIds = if (packageName != null) {
            urlBarViewIdsFor(packageName)
        } else {
            // No package context: probe every known browser's ids.
            BROWSER_URL_BAR_IDS.keys.flatMap { urlBarViewIdsFor(it) }
        }

        for (viewId in viewIds) {
            val nodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) {
                null
            }

            if (!nodes.isNullOrEmpty()) {
                val text = nodes[0].text?.toString()
                nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                if (!text.isNullOrBlank()) return text
            }
        }

        return null
    }
}
