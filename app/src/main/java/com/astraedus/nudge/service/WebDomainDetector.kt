package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects the current URL/domain shown in Chrome's omnibox by inspecting the accessibility tree.
 *
 * Chrome v1 only. Extensible via [BROWSER_PACKAGES] for future browser support.
 */
@Singleton
class WebDomainDetector @Inject constructor() {

    companion object {
        val BROWSER_PACKAGES = setOf("com.android.chrome")

        // Chrome URL bar resource IDs (try in order)
        val CHROME_URL_IDS = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/omnibox_url_text"
        )
    }

    fun isBrowser(packageName: String): Boolean = packageName in BROWSER_PACKAGES

    /**
     * Attempt to read the domain/URL from Chrome's URL bar via the accessibility tree.
     *
     * @param rootNode The rootInActiveWindow from the accessibility service
     * @return The text content of the URL bar, or null if not found
     */
    fun detectUrl(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        for (viewId in CHROME_URL_IDS) {
            val nodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) {
                null
            }

            if (!nodes.isNullOrEmpty()) {
                val text = nodes[0].text?.toString()
                // Recycle nodes we fetched
                nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                if (!text.isNullOrBlank()) return text
            }
        }

        return null
    }
}
