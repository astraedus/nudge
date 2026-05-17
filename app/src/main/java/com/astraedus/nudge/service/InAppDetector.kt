package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.util.NudgeLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects in-app features (Reels, Shorts, Explore) by inspecting the accessibility tree.
 *
 * Detection is best-effort -- apps change their UI frequently. When detection fails
 * we return null (no feature detected) rather than crashing, so the service falls back
 * to whole-app rule evaluation.
 */
@Singleton
class InAppDetector @Inject constructor(
    private val logger: NudgeLogger
) : InAppDetectorApi {

    enum class Feature(val displayName: String, val key: String) {
        REELS("Instagram Reels", "REELS"),
        SHORTS("YouTube Shorts", "SHORTS"),
        EXPLORE("Instagram Explore", "EXPLORE"),
        TIKTOK_FEED("TikTok Feed", "TIKTOK_FEED")
    }

    companion object {
        /** Packages that support in-app feature detection. */
        val SUPPORTED_PACKAGES = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )
    }

    /**
     * Attempt to detect which in-app feature is active for the given package.
     *
     * @return The detected [Feature], or null if no specific feature is detected
     *   (user is in a non-feature part of the app, or detection failed).
     */
    override fun detectFeature(packageName: String, rootNode: AccessibilityNodeInfo?): Feature? {
        if (rootNode == null) {
            logger.d("feature detection skipped package=$packageName reason=null_root")
            return null
        }
        return try {
            val feature = when (packageName) {
                "com.instagram.android" -> detectInstagram(rootNode)
                "com.google.android.youtube" -> detectYouTube(rootNode)
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> Feature.TIKTOK_FEED
                else -> null
            }
            logger.d("feature detection result package=$packageName feature=$feature")
            feature
        } catch (e: Exception) {
            logger.w("feature detection failed package=$packageName", e)
            null
        }
    }

    private fun detectInstagram(root: AccessibilityNodeInfo): Feature? {
        // Use resource IDs for reliable tab detection. Instagram's bottom nav tabs:
        //   feed_tab (Home), clips_tab (Reels), search_tab (Search/Explore), profile_tab (Profile)
        // The tab FrameLayout itself has selected=false, but its child tab_icon ImageView
        // has selected=true for the active tab.
        val activeTab = findActiveInstagramTab(root)
        logger.d("instagram active tab: $activeTab")
        return when (activeTab) {
            "clips_tab" -> Feature.REELS
            "search_tab" -> Feature.EXPLORE
            "feed_tab" -> Feature.REELS  // Home feed = reels-equivalent
            else -> {
                // Fallback: text-based detection for older Instagram versions
                detectInstagramByText(root)
            }
        }
    }

    /**
     * Find which Instagram bottom nav tab is active by checking resource IDs.
     * Returns the tab ID suffix (e.g. "feed_tab", "clips_tab") or null if not found.
     */
    private fun findActiveInstagramTab(root: AccessibilityNodeInfo): String? {
        val tabIds = listOf("feed_tab", "clips_tab", "search_tab", "profile_tab")
        for (tabId in tabIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(
                "com.instagram.android:id/$tabId"
            )
            if (nodes.isNotEmpty()) {
                for (node in nodes) {
                    if (isTabActive(node)) {
                        recycleNodes(nodes)
                        return tabId
                    }
                }
                recycleNodes(nodes)
            }
        }
        return null
    }

    /**
     * Check if a tab node is active by looking for selected=true on the node
     * itself or any of its descendants (up to 3 levels deep).
     */
    private fun isTabActive(node: AccessibilityNodeInfo): Boolean {
        if (node.isSelected) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isSelected) return true
            // Check grandchildren too
            for (j in 0 until child.childCount) {
                val grandchild = child.getChild(j) ?: continue
                if (grandchild.isSelected) return true
            }
        }
        return false
    }

    /** Fallback text-based detection for older Instagram versions. */
    private fun detectInstagramByText(root: AccessibilityNodeInfo): Feature? {
        val reelsNodes = root.findAccessibilityNodeInfosByText("Reels")
        if (reelsNodes.isNotEmpty()) {
            for (node in reelsNodes) {
                if (node.isSelected || isInSelectedTab(node)) {
                    recycleNodes(reelsNodes)
                    return Feature.REELS
                }
            }
        }
        recycleNodes(reelsNodes)

        val exploreNodes = root.findAccessibilityNodeInfosByText("Explore")
        if (exploreNodes.isNotEmpty()) {
            for (node in exploreNodes) {
                if (node.isSelected || isInSelectedTab(node)) {
                    recycleNodes(exploreNodes)
                    return Feature.EXPLORE
                }
            }
        }
        recycleNodes(exploreNodes)

        return null
    }

    private fun detectYouTube(root: AccessibilityNodeInfo): Feature? {
        // Method 1: Check if Shorts tab is selected (user navigated via bottom tab)
        val shortsNodes = root.findAccessibilityNodeInfosByText("Shorts")
        if (shortsNodes.isNotEmpty()) {
            for (node in shortsNodes) {
                if (node.isSelected || isInSelectedTab(node) || hasSelectedChild(node)) {
                    recycleNodes(shortsNodes)
                    return Feature.SHORTS
                }
            }
        }
        recycleNodes(shortsNodes)

        // Method 2: Check for Shorts player container (user tapped a Short from home feed)
        val reelRecycler = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/reel_recycler"
        )
        if (reelRecycler.isNotEmpty()) {
            recycleNodes(reelRecycler)
            return Feature.SHORTS
        }

        // Method 3: Check for reel player page (another common Shorts container ID)
        val reelPlayer = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/reel_player_page_container"
        )
        if (reelPlayer.isNotEmpty()) {
            recycleNodes(reelPlayer)
            return Feature.SHORTS
        }

        return null
    }

    /**
     * Walk up the parent chain to check if any ancestor is marked as selected.
     * This handles cases where the tab text itself is not selected but its container is.
     */
    private fun isInSelectedTab(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isSelected) return true
            val next = current.parent
            current = next
            depth++
        }
        return false
    }

    /**
     * Check if any immediate child of the node is selected.
     * Instagram sets selected=true on the child tab_icon ImageView, not the
     * parent FrameLayout that carries the content-description.
     */
    private fun hasSelectedChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isSelected) return true
        }
        return false
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Exception) {
                // Already recycled -- ignore
            }
        }
    }
}
