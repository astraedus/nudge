package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.BuildConfig
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

    /**
     * Signatures of surfaces already reported by [dumpViewIdsForDiagnosis], so each unrecognised
     * screen is logged once per process rather than once per accessibility event. Debug builds
     * only; bounded in practice by the handful of distinct screens these apps have.
     */
    private val loggedUnknownSurfaces = mutableSetOf<String>()

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

        /** Cap for the debug-only view-id harvest; keeps the walk off the hot path's budget. */
        private const val DIAGNOSTIC_NODE_LIMIT = 800

        /**
         * Containers unique to Instagram's full-screen reel player, harvested from a real device
         * (Galaxy S24 / Android 16) while watching a reel opened from a DM.
         *
         * Checked instead of the bottom-nav tabs because the player is hosted in a modal activity
         * with no nav bar. Verified absent from the home feed (which shows inline video under
         * `media_group` / `carousel_video_media_group`) and from a DM thread, so these do not
         * over-match ordinary browsing.
         *
         * More than one is listed because the player's tree varies between entry points — the
         * DM-opened variant additionally carries a reply bar. Any single match is sufficient.
         */
        private val INSTAGRAM_CLIPS_VIEWER_IDS = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_media_component"
        )
    }

    /** True if any of [viewIds] resolves in [root]. Nodes are recycled before returning. */
    private fun findsAnyViewId(root: AccessibilityNodeInfo, viewIds: List<String>): Boolean {
        for (id in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                recycleNodes(nodes)
                return true
            }
            recycleNodes(nodes)
        }
        return false
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
            if (feature == null) dumpViewIdsForDiagnosis(packageName, rootNode)
            logger.d("feature detection result package=$packageName feature=$feature")
            feature
        } catch (e: Exception) {
            logger.w("feature detection failed package=$packageName", e)
            null
        }
    }

    /**
     * DIAGNOSTIC (debug builds only): log the distinct view IDs present when detection found
     * nothing, so an undetected surface can be identified from logcat.
     *
     * Exists because the usual external tools cannot see these surfaces: `uiautomator dump` waits
     * for an idle window and a continuously playing reel/short never idles, so it hangs and gets
     * Killed; `dumpsys activity top` times out on the same screens. The accessibility tree this
     * service already walks has no such constraint.
     *
     * Reads ONLY `viewIdResourceName` — never text or contentDescription, which on these screens
     * would be the user's private messages and captions. Bounded to [DIAGNOSTIC_NODE_LIMIT] nodes,
     * matching the bounded-harvest convention used by the Strict Mode escape guard.
     */
    private fun dumpViewIdsForDiagnosis(packageName: String, root: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        val ids = LinkedHashSet<String>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited < DIAGNOSTIC_NODE_LIMIT) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { ids += it }
            for (i in 0 until node.childCount) {
                queue += node.getChild(i) ?: continue
            }
        }
        // Log each DISTINCT surface once. Detection runs on a firehose of content-change events —
        // a measured ~800 failed detections in three minutes of Instagram use — so logging every
        // miss buries the signal. What matters is "which surfaces do we not recognise", and that
        // set is tiny.
        val signature = ids.joinToString(",")
        if (!loggedUnknownSurfaces.add(signature)) return
        logger.d("undetected surface package=$packageName nodes=$visited viewIds=$signature")
    }

    private fun detectInstagram(root: AccessibilityNodeInfo): Feature? {
        // The reel PLAYER first, before any tab reasoning. A reel opened from a DM (or a share
        // link, or a profile) runs in com.instagram.modal.ModalActivity, which has NO bottom nav
        // at all — so tab-based detection cannot see it even in principle, and the user scrolled
        // reels indefinitely with a HARD_BLOCK rule active. Keying on the player's own container
        // covers every entry route, including the Reels tab, where these IDs are also present.
        if (findsAnyViewId(root, INSTAGRAM_CLIPS_VIEWER_IDS)) {
            logger.d("instagram clips viewer detected")
            return Feature.REELS
        }

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
