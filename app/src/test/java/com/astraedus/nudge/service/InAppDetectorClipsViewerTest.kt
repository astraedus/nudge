package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.ScreenBounds
import com.astraedus.nudge.util.NudgeLogger
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the DM-opened reel bypass (device-found 2026-08-08).
 *
 * Instagram's full-screen reel player is hosted in `com.instagram.modal.ModalActivity`, which has
 * NO bottom navigation. Detection keyed exclusively on which bottom-nav tab was `selected`, so the
 * player was structurally invisible: opening a reel from a DM produced no detected feature, and a
 * user with a HARD_BLOCK rule on REELS could scroll reels indefinitely. Reels opened from the Reels
 * TAB blocked correctly, which is why the gap went unnoticed.
 *
 * An instrumented capture on a Galaxy S24 recorded ~800 detection attempts on these screens, every
 * one returning null. The fix keys on the player's own containers instead of the nav bar.
 */
class InAppDetectorClipsViewerTest {

    private val ig = "com.instagram.android"

    /** The recorded Pixel 3 Instagram window (`surface-fixtures/home.xml` root). */
    private val window = ScreenBounds(0, 0, 1080, 2028)

    /** Bounds the fake tree reports, per node. Read by the detector through its bounds seam. */
    private val bounds = mutableMapOf<AccessibilityNodeInfo, ScreenBounds?>()

    private val detector = InAppDetector(mockk<NudgeLogger>(relaxed = true)).also { d ->
        d.boundsInScreen = { node -> bounds[node] }
    }

    /** A node as the framework hands it back: [visible] is its `isVisibleToUser`. */
    private fun node(at: ScreenBounds?, visible: Boolean = true): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.isVisibleToUser } returns visible
        bounds[n] = at
        return n
    }

    /**
     * A root whose [AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId] resolves exactly the
     * ids in [found] (to the nodes given) and nothing else. childCount is 0 so the debug harvest
     * terminates.
     */
    private fun rootWithNodes(found: Map<String, List<AccessibilityNodeInfo>>): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 0
        every { root.viewIdResourceName } returns null
        every { root.findAccessibilityNodeInfosByViewId(any()) } answers {
            found[firstArg<String>()] ?: emptyList()
        }
        bounds[root] = window
        return root
    }

    /** Every id in [presentIds] resolves to one on-screen, full-window node: the real player. */
    private fun rootWith(presentIds: Set<String>): AccessibilityNodeInfo =
        rootWithNodes(presentIds.associateWith { listOf(node(window)) })

    /** The exact surface captured from a reel opened out of a DM thread. */
    @Test
    fun `reel opened from a DM is detected as REELS`() {
        val root = rootWith(
            setOf(
                "com.instagram.android:id/clips_viewer_view_pager",
                "com.instagram.android:id/clips_video_container",
                "com.instagram.android:id/clips_media_component"
            )
        )

        assertEquals(InAppDetector.Feature.REELS, detector.detectFeature(ig, root))
    }

    /**
     * The player's tree varies by entry point — the DM variant carries a reply bar, others do not.
     * Any single container is sufficient, so a partial match must still detect.
     */
    @Test
    fun `any single clips container is enough`() {
        listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_media_component"
        ).forEach { id ->
            assertEquals(
                "expected REELS from $id alone",
                InAppDetector.Feature.REELS,
                detector.detectFeature(ig, rootWith(setOf(id)))
            )
        }
    }

    /**
     * The player check must not fire on ordinary browsing. A DM thread shows reel previews but
     * carries none of the player containers — blocking here would make Instagram unusable for
     * messaging, which is not what a REELS rule asks for.
     */
    @Test
    fun `a DM thread is not detected as REELS`() {
        val root = rootWith(
            setOf(
                "com.instagram.android:id/thread_fragment_container",
                "com.instagram.android:id/message_list",
                "com.instagram.android:id/message_composer_bar"
            )
        )

        assertNull(detector.detectFeature(ig, root))
    }

    /** The player check must not hijack a surface with no clips containers and no active tab. */
    @Test
    fun `an unknown surface still returns null`() {
        assertNull(detector.detectFeature(ig, rootWith(emptySet())))
    }

    /** A null root must never throw — detection is best-effort by contract. */
    @Test
    fun `null root returns null`() {
        assertNull(detector.detectFeature(ig, null))
    }

    /** The player containers are Instagram-specific and must not leak into YouTube detection. */
    @Test
    fun `clips containers do not trigger for YouTube`() {
        val root = rootWith(setOf("com.instagram.android:id/clips_viewer_view_pager"))

        assertNull(detector.detectFeature("com.google.android.youtube", root))
    }

    // -----------------------------------------------------------------------------------------
    // Issue #190: an id match the user is not looking at is not the reel player
    // -----------------------------------------------------------------------------------------

    private val pager = "com.instagram.android:id/clips_viewer_view_pager"
    private val media = "com.instagram.android:id/clips_media_component"

    /**
     * The framework's id lookup returns nodes whose views are VISIBLE but not on screen, such as a
     * pager page beside the current one. On a surface with no other signal (a DM thread, Stories, or
     * any screen on a device where Instagram's tab state is unreadable, #18) that used to make the
     * screen count as Reels.
     */
    @Test
    fun `a player node the user cannot see is not REELS`() {
        val root = rootWithNodes(mapOf(pager to listOf(node(ScreenBounds(1080, 0, 2160, 2028), visible = false))))

        assertNull(detector.detectFeature(ig, root))
    }

    /** A player embedded in another screen, at the size of the recorded Home feed inline video. */
    @Test
    fun `an inline-sized player inside another screen is not REELS`() {
        val root = rootWithNodes(mapOf(media to listOf(node(ScreenBounds(0, 611, 1080, 1896)))))

        assertNull(detector.detectFeature(ig, root))
    }

    /** The Reels tab: the player runs from the top of the window to the recorded tab bar (y=1896). */
    @Test
    fun `the Reels tab player above the nav bar is REELS`() {
        val root = rootWithNodes(mapOf(pager to listOf(node(ScreenBounds(0, 0, 1080, 1896)))))

        assertEquals(InAppDetector.Feature.REELS, detector.detectFeature(ig, root))
    }

    /** One off-screen copy must not hide the real player: any on-screen match is enough. */
    @Test
    fun `an off-screen copy next to the on-screen player is still REELS`() {
        val root = rootWithNodes(
            mapOf(
                pager to listOf(
                    node(ScreenBounds(1080, 0, 2160, 2028), visible = false),
                    node(window)
                )
            )
        )

        assertEquals(InAppDetector.Feature.REELS, detector.detectFeature(ig, root))
    }

    /** Any of the three ids still suffices: a small media node does not veto a full-screen pager. */
    @Test
    fun `a full-screen pager counts even when the media node inside it is small`() {
        val root = rootWithNodes(
            mapOf(
                media to listOf(node(ScreenBounds(0, 700, 1080, 1300))),
                pager to listOf(node(window))
            )
        )

        assertEquals(InAppDetector.Feature.REELS, detector.detectFeature(ig, root))
    }

    /** Fails toward the old behaviour: bounds that cannot be read never turn the player into a miss. */
    @Test
    fun `a visible player whose bounds cannot be read is still REELS`() {
        val root = rootWithNodes(mapOf(pager to listOf(node(null))))

        assertEquals(InAppDetector.Feature.REELS, detector.detectFeature(ig, root))
    }
}
