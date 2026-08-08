package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
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

    private val detector = InAppDetector(mockk<NudgeLogger>(relaxed = true))

    private val ig = "com.instagram.android"

    /**
     * A root whose [AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId] resolves exactly the
     * ids in [presentIds] and nothing else. childCount is 0 so the debug harvest terminates.
     */
    private fun rootWith(presentIds: Set<String>): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 0
        every { root.viewIdResourceName } returns null
        every { root.findAccessibilityNodeInfosByViewId(any()) } answers {
            val id = firstArg<String>()
            if (id in presentIds) listOf(mockk<AccessibilityNodeInfo>(relaxed = true)) else emptyList()
        }
        return root
    }

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
}
