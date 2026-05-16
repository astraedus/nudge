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
) {

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
    fun detectFeature(packageName: String, rootNode: AccessibilityNodeInfo?): Feature? {
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
        // Check for Reels: look for nodes with text/content-description "Reels"
        // that are selected (indicating the Reels tab is active)
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

        // Check for Explore tab
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
        // Look for "Shorts" tab text in bottom navigation
        val shortsNodes = root.findAccessibilityNodeInfosByText("Shorts")
        if (shortsNodes.isNotEmpty()) {
            for (node in shortsNodes) {
                if (node.isSelected || isInSelectedTab(node)) {
                    recycleNodes(shortsNodes)
                    return Feature.SHORTS
                }
            }
        }
        recycleNodes(shortsNodes)

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
