package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReelPlayerOnScreen] against the geometry of real Instagram trees (issue #190).
 *
 * The threshold is a claim about two shapes: the full-screen reel player is (nearly) the window's
 * full height, and an inline feed video is not. Both numbers come from `surface-fixtures/` (Pixel 3,
 * Instagram 447, see its README), not from this file. The inline video is the real `video_container`
 * on the Home feed. There is no recorded tree of the Reels tab itself, so its player is the space the
 * recorded tab bar leaves: from the top of the window to the top of `tab_bar`, which is the largest
 * the Reels-tab player can be and still sit above the nav bar it is drawn with.
 */
class ReelPlayerOnScreenFixtureTest {

    private val ig = "com.instagram.android:id/"

    private fun FixtureNode.bounds() = ScreenBounds(left, top, right, bottom)

    /** The window: the dump's root node. */
    private fun window(fixture: String): ScreenBounds = SurfaceFixture.load(fixture).first().bounds()

    private fun nodes(fixture: String, id: String): List<FixtureNode> =
        SurfaceFixture.load(fixture).filter { it.viewId == ig + id }

    @Test
    fun `the Home feed's inline video does not count as the reel player`() {
        for (fixture in listOf("home", "coldstart")) {
            val videos = nodes(fixture, "video_container") + nodes(fixture, "media_group")
            assertTrue("premise: $fixture records an inline video", videos.isNotEmpty())
            for (video in videos) {
                assertFalse(
                    "$fixture ${video.viewId} ${video.bounds()} must not count",
                    ReelPlayerOnScreen.counts(true, video.bounds(), window(fixture))
                )
            }
        }
    }

    @Test
    fun `a player filling the window above the recorded tab bar counts`() {
        for (fixture in listOf("home", "coldstart", "home-scrolled")) {
            val window = window(fixture)
            val tabBar = nodes(fixture, "tab_bar").single().bounds()
            val reelsTabPlayer = ScreenBounds(window.left, window.top, window.right, tabBar.top)
            assertTrue(
                "$fixture: $reelsTabPlayer in $window must count",
                ReelPlayerOnScreen.counts(true, reelsTabPlayer, window)
            )
        }
    }

    @Test
    fun `a player filling the whole window, as in a modal, counts`() {
        val window = window("home")
        assertTrue(ReelPlayerOnScreen.counts(true, window, window))
    }
}
