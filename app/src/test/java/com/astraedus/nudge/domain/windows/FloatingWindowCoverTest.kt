package com.astraedus.nudge.domain.windows

import com.astraedus.nudge.domain.windows.WindowFixtures.FREEFORM
import com.astraedus.nudge.domain.windows.WindowFixtures.floatingApp
import com.astraedus.nudge.domain.windows.WindowFixtures.fullscreenApp
import com.astraedus.nudge.domain.windows.WindowFixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L1: where the #71 cover goes, when BACK is pressed, and when the cover comes off. */
class FloatingWindowCoverTest {

    private val launcher = "com.huawei.android.launcher"
    private val blocked = "com.instagram.android"

    private val floatingBlocked = snapshot(fullscreenApp(1, launcher, focused = false), floatingApp(7, blocked, focused = true))

    @Test
    fun `a floating blocked app is covered over its window`() {
        assertEquals(FREEFORM, FloatingWindowCover.coverFor(floatingBlocked, blocked))
    }

    @Test
    fun `a fullscreen blocked app gets no cover, the overlay blocks it the ordinary way`() {
        assertNull(FloatingWindowCover.coverFor(snapshot(fullscreenApp(1, blocked)), blocked))
    }

    @Test
    fun `no window list never licenses a cover`() {
        assertNull(FloatingWindowCover.coverFor(null, blocked))
    }

    @Test
    fun `BACK is pressed once per window, and only while that window holds focus`() {
        assertEquals(7, FloatingWindowCover.backTarget(floatingBlocked, blocked, alreadyPressed = emptySet()))
        assertNull("never twice on one window", FloatingWindowCover.backTarget(floatingBlocked, blocked, setOf(7)))
        val unfocused = snapshot(fullscreenApp(1, launcher, focused = true), floatingApp(7, blocked, focused = false))
        assertNull("BACK would hit another app", FloatingWindowCover.backTarget(unfocused, blocked, emptySet()))
    }

    @Test
    fun `the cover follows the window when it is dragged`() {
        val moved = WindowBounds(50, 300, 830, 1400)
        val s = snapshot(floatingApp(7, blocked, focused = true, bounds = moved))
        assertEquals(
            FloatingCoverEffect.Show(blocked, moved),
            FloatingWindowCover.maintain(s, covered = blocked, shownBounds = FREEFORM, grantAlive = false)
        )
    }

    @Test
    fun `an unchanged window leaves the cover alone`() {
        assertEquals(
            FloatingCoverEffect.None,
            FloatingWindowCover.maintain(floatingBlocked, covered = blocked, shownBounds = FREEFORM, grantAlive = false)
        )
    }

    @Test
    fun `the cover comes off when the window closes`() {
        assertEquals(
            FloatingCoverEffect.Hide,
            FloatingWindowCover.maintain(snapshot(fullscreenApp(1, launcher)), blocked, FREEFORM, grantAlive = false)
        )
    }

    @Test
    fun `the cover comes off when the window is maximised to fullscreen`() {
        assertEquals(
            FloatingCoverEffect.Hide,
            FloatingWindowCover.maintain(snapshot(fullscreenApp(7, blocked)), blocked, FREEFORM, grantAlive = false)
        )
    }

    @Test
    fun `the cover comes off when the user earns their way in`() {
        assertEquals(
            FloatingCoverEffect.Hide,
            FloatingWindowCover.maintain(floatingBlocked, blocked, FREEFORM, grantAlive = true)
        )
    }

    @Test
    fun `an unreadable window list keeps the cover, it is no evidence the window went away`() {
        assertEquals(FloatingCoverEffect.None, FloatingWindowCover.maintain(null, blocked, FREEFORM, grantAlive = false))
        assertEquals(
            "a grant still takes it off without a list",
            FloatingCoverEffect.Hide,
            FloatingWindowCover.maintain(null, blocked, FREEFORM, grantAlive = true)
        )
    }

    @Test
    fun `nothing covered means nothing to maintain`() {
        assertEquals(FloatingCoverEffect.None, FloatingWindowCover.maintain(floatingBlocked, null, null, grantAlive = false))
    }
}
