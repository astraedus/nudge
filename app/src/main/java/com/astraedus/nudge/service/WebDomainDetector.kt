package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects the current URL/domain shown in a browser's URL bar by inspecting the
 * accessibility tree.
 *
 * Supports multiple browsers via [BROWSER_URL_BAR_IDS]: each package maps to an
 * ordered list of candidate URL-bar id *suffixes*. [detectUrl] first tries the
 * fully-qualified ids (`<pkg>:id/<suffix>`) via `findAccessibilityNodeInfosByViewId`
 * (fast, reliable for classic resource ids like Chrome's `url_bar`). If that yields
 * nothing it falls back to a bounded tree traversal matching `viewIdResourceName`
 * against the bare suffixes — required for Jetpack-Compose testTags like Firefox's
 * `ADDRESSBAR_URL_BOX`, which the platform `…ByViewId` lookup does NOT reliably match.
 * The matched node's text — or, if blank, its contentDescription — is cleaned and
 * returned.
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
            // Firefox family. Modern Firefox uses a Jetpack-Compose toolbar whose address
            // bar is exposed as a bare Compose testTag "ADDRESSBAR_URL_BOX" (no pkg prefix);
            // the legacy GeckoView ids are kept after it for older builds.
            "org.mozilla.firefox" to listOf("ADDRESSBAR_URL_BOX", "mozac_browser_toolbar_url_view", "url_bar_title"),
            "org.mozilla.fenix" to listOf("ADDRESSBAR_URL_BOX", "mozac_browser_toolbar_url_view", "url_bar_title"),
            // Samsung Internet
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text", "sites_url"),
            // Opera
            "com.opera.browser" to listOf("url_field", "url_bar"),
            "com.opera.mini.native" to listOf("url_field", "url_bar"),
            // DuckDuckGo
            "com.duckduckgo.mobile.android" to listOf("omnibarTextInput")
        )

        /**
         * Resolve the candidate URL-bar view ids for a package, in probe order.
         * For each configured suffix we emit BOTH the fully-qualified id
         * (`<pkg>:id/<suffix>`) and the bare suffix, because Jetpack-Compose
         * testTags (e.g. Firefox's `ADDRESSBAR_URL_BOX`) are exposed to
         * accessibility WITHOUT a package prefix. Emitting the bare form for
         * classic resource ids (`url_bar`, …) is harmless — it simply won't match.
         * Pure; unit-testable without Android. Returns empty list if the package
         * is not a recognized browser.
         */
        fun urlBarViewIdsFor(packageName: String): List<String> {
            val suffixes = BROWSER_URL_BAR_IDS[packageName] ?: return emptyList()
            return suffixes.flatMap { listOf("$packageName:id/$it", it) }
        }

        /**
         * Fully-qualified (`<pkg>:id/<suffix>`) candidate ids for a package, in order.
         * These are reliably matched by `findAccessibilityNodeInfosByViewId` (fast path),
         * so classic resource ids (Chrome's `url_bar`, etc.) go through here. Pure.
         */
        fun qualifiedUrlBarViewIdsFor(packageName: String): List<String> {
            val suffixes = BROWSER_URL_BAR_IDS[packageName] ?: return emptyList()
            return suffixes.map { "$packageName:id/$it" }
        }

        /**
         * Bare-suffix candidate ids for a package (e.g. Firefox's Compose testTag
         * `ADDRESSBAR_URL_BOX`). The platform `findAccessibilityNodeInfosByViewId` does
         * NOT reliably match unprefixed Compose testTags, so these are matched by walking
         * the tree and comparing `viewIdResourceName` directly. Pure.
         */
        fun bareUrlBarViewIdsFor(packageName: String): List<String> =
            BROWSER_URL_BAR_IDS[packageName] ?: emptyList()

        /**
         * Normalizes an address-bar string into the bare URL.
         *
         * Some browsers (modern Firefox's Compose toolbar) put the URL in the node's
         * contentDescription as `" <url>. <localized hint>"`, e.g.
         * `" instagram.com. Search or enter address"`. A displayed URL never contains a
         * period immediately followed by whitespace, so we cut at the FIRST `\.\s` match
         * to strip the hint suffix — locale-agnostically. If there is no such match the
         * trimmed text is returned unchanged (so Chrome's plain `text` URLs pass through).
         *
         * The path is intentionally preserved; [WebDomainMatcher.extractDomain] strips it
         * downstream. Pure/Android-free (takes a String) for unit-testing.
         *
         * @return the cleaned URL, or null if blank.
         */
        fun cleanAddressBarText(raw: String?): String? {
            val trimmed = raw?.trim()
            if (trimmed.isNullOrBlank()) return null
            val match = Regex("\\.\\s").find(trimmed)
            val cut = if (match != null) trimmed.substring(0, match.range.first) else trimmed
            val result = cut.trim()
            return result.ifBlank { null }
        }
    }

    fun isBrowser(packageName: String): Boolean = packageName in BROWSER_URL_BAR_IDS

    /**
     * Attempt to read the URL/domain from the browser's URL bar via the
     * accessibility tree. Two strategies, in order:
     *
     *  1. **Fast path** — `findAccessibilityNodeInfosByViewId` over fully-qualified ids
     *     (`<pkg>:id/<suffix>`). Reliable for classic resource ids (Chrome `url_bar`, …).
     *  2. **Traversal fallback** — if the fast path yields nothing, a bounded depth-first
     *     walk of the tree matching `node.viewIdResourceName` against the bare candidate
     *     ids. This is required for Jetpack-Compose testTags like Firefox's
     *     `ADDRESSBAR_URL_BOX`, which the platform `…ByViewId` lookup does NOT match.
     *
     * Either way the matched node's `text` (or, if blank, `contentDescription`) is run
     * through [cleanAddressBarText]. Resilient: node-resolution failures are swallowed
     * and fetched/traversed nodes are recycled.
     *
     * @param rootNode The rootInActiveWindow from the accessibility service
     * @param packageName The foreground browser package (selects which view ids to
     *   probe). When null, falls back to trying all known browsers' ids.
     * @return The cleaned URL text from the URL bar, or null if not found
     */
    fun detectUrl(rootNode: AccessibilityNodeInfo?, packageName: String? = null): String? {
        if (rootNode == null) return null

        val qualifiedIds = if (packageName != null) {
            qualifiedUrlBarViewIdsFor(packageName)
        } else {
            BROWSER_URL_BAR_IDS.keys.flatMap { qualifiedUrlBarViewIdsFor(it) }
        }

        // 1. Fast path: ByViewId on fully-qualified ids.
        for (viewId in qualifiedIds) {
            val nodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) {
                null
            }

            if (!nodes.isNullOrEmpty()) {
                val cleaned = cleanAddressBarText(readUrlRaw(nodes[0]))
                nodes.forEach { recycleQuietly(it) }
                if (!cleaned.isNullOrBlank()) return cleaned
            }
        }

        // 2. Traversal fallback: match bare viewIdResourceName (Compose testTags).
        val bareIds = if (packageName != null) {
            bareUrlBarViewIdsFor(packageName).toSet()
        } else {
            BROWSER_URL_BAR_IDS.values.flatten().toSet()
        }
        if (bareIds.isEmpty()) return null

        val match = findNodeByViewId(rootNode, bareIds) ?: return null
        val cleaned = cleanAddressBarText(readUrlRaw(match))
        recycleQuietly(match)
        return cleaned?.ifBlank { null }
    }

    /** Read the URL from a URL-bar node: text first, then contentDescription if blank. */
    private fun readUrlRaw(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        return if (!text.isNullOrBlank()) text else node.contentDescription?.toString()
    }

    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        try { node.recycle() } catch (_: Exception) {}
    }

    /**
     * Bounded depth-first search for the first node whose `viewIdResourceName` is in
     * [targetViewIds]. Matches WITHOUT requiring a package prefix, so bare Compose
     * testTags are found. Visits at most [maxNodes] nodes as a cost guard (the native
     * tree is small — Firefox's web content lives in a GeckoView surface not exposed
     * as a11y nodes — but the cap protects against pathological trees). Traversed
     * non-matching children are recycled; the matched node is returned un-recycled for
     * the caller to read then recycle. The root itself is never recycled here.
     */
    private fun findNodeByViewId(
        root: AccessibilityNodeInfo,
        targetViewIds: Set<String>,
        maxNodes: Int = 600
    ): AccessibilityNodeInfo? {
        var visited = 0
        // Stack holds nodes we own and must recycle (children we fetched), except any
        // node we return. The root is owned by the caller, so it is never recycled here.
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var rootSeen = false

        while (stack.isNotEmpty()) {
            if (visited >= maxNodes) break
            val node = stack.removeLast()
            visited++
            val isRoot = !rootSeen && node === root
            if (isRoot) rootSeen = true

            val viewId = try { node.viewIdResourceName } catch (_: Exception) { null }
            if (viewId != null && viewId in targetViewIds) {
                // Found it. Recycle everything else still queued (we own those), keep
                // the match. Don't recycle the root.
                stack.forEach { if (it !== root) recycleQuietly(it) }
                return node
            }

            val childCount = try { node.childCount } catch (_: Exception) { 0 }
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) stack.addLast(child)
            }

            // Done with this node; recycle if we own it (everything but the root).
            if (!isRoot) recycleQuietly(node)
        }

        return null
    }
}
