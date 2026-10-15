package com.astraedus.nudge.domain.windows

import com.astraedus.nudge.domain.windows.WindowFixtures.FREEFORM
import com.astraedus.nudge.domain.windows.WindowFixtures.FULL
import com.astraedus.nudge.domain.windows.WindowFixtures.floatingApp
import com.astraedus.nudge.domain.windows.WindowFixtures.fullscreenApp
import com.astraedus.nudge.domain.windows.WindowFixtures.snapshot
import com.astraedus.nudge.domain.windows.WindowFixtures.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1: the pure queries the classifier (#76) and the floating-window cover (#71) decide from.
 * Every query must answer on POSITIVE evidence only; an unknown is always "no claim".
 */
class WindowSnapshotTest {

    private val instagram = "com.instagram.android"
    private val clipboard = "com.samsung.android.clipboarduiservice"
    private val youtube = "com.google.android.youtube"

    @Test
    fun `a window id the list does not contain makes no claim`() {
        val s = snapshot(fullscreenApp(1, instagram))
        assertNull(s.kindOf(99))
        assertFalse("unknown must never read as a non-app window", s.isNonApplicationWindow(99))
    }

    @Test
    fun `keyboard, system and accessibility-overlay windows are non-application windows`() {
        val s = snapshot(
            fullscreenApp(1, instagram),
            window(2, WindowKind.INPUT_METHOD),
            window(3, WindowKind.SYSTEM),
            window(4, WindowKind.ACCESSIBILITY_OVERLAY),
            window(5, WindowKind.OTHER)
        )
        assertFalse(s.isNonApplicationWindow(1))
        listOf(2, 3, 4, 5).forEach { assertTrue("window $it", s.isNonApplicationWindow(it)) }
    }

    @Test
    fun `focus is elsewhere only when the package has windows, none focused, and another app is`() {
        val panel = "com.samsung.android.app.writingtoolkit"
        val s = snapshot(fullscreenApp(1, instagram, focused = true), floatingApp(2, panel, focused = false))
        assertTrue(s.focusIsElsewhere(panel))
        assertFalse("the focused app is never 'elsewhere'", s.focusIsElsewhere(instagram))
    }

    @Test
    fun `a package with no window in the list is not evidence of anything`() {
        // It could simply be newer than the list. Treating absence as "not the app" would stop a
        // brand-new app from being evaluated.
        val s = snapshot(fullscreenApp(1, instagram))
        assertFalse(s.focusIsElsewhere(youtube))
    }

    @Test
    fun `no application window holding focus is no evidence about who the user is in`() {
        val s = snapshot(fullscreenApp(1, instagram, focused = false), floatingApp(2, youtube, focused = false))
        assertFalse(s.focusIsElsewhere(youtube))
        assertFalse(s.focusIsElsewhere(instagram))
    }

    @Test
    fun `a fullscreen app that stops at an opaque nav bar is still fullscreen`() {
        val s = snapshot(fullscreenApp(1, instagram, bounds = FULL))
        assertTrue(s.isFullscreen(s.windows.single()))
        assertFalse(s.isFloating(instagram))
    }

    @Test
    fun `a freeform window is floating and reports its bounds`() {
        val s = snapshot(fullscreenApp(1, instagram), floatingApp(2, youtube, focused = true))
        assertTrue(s.isFloating(youtube))
        assertEquals(FREEFORM, s.floatingBoundsOf(youtube))
    }

    @Test
    fun `a split-screen half is not fullscreen`() {
        val half = WindowBounds(0, 0, 1080, 1150)
        val s = snapshot(fullscreenApp(1, youtube, bounds = half))
        assertTrue(s.isFloating(youtube))
    }

    @Test
    fun `an app with ANY fullscreen window is not floating, popups included`() {
        val popup = WindowBounds(600, 200, 1000, 700)
        val s = snapshot(fullscreenApp(1, youtube), fullscreenApp(2, youtube, focused = false, bounds = popup))
        assertFalse(s.isFloating(youtube))
    }

    @Test
    fun `a floating app's popup goes under the same cover`() {
        val popup = WindowBounds(800, 500, 1050, 900)
        val s = snapshot(floatingApp(1, youtube, focused = true), floatingApp(2, youtube, focused = false, bounds = popup))
        assertEquals(WindowBounds(120, 500, 1050, 1700), s.floatingBoundsOf(youtube))
    }

    @Test
    fun `picture-in-picture is never floating (issue 19 owns it)`() {
        val pip = ScreenWindow(1, WindowKind.APPLICATION, youtube, false, false, true, WindowBounds(600, 1800, 1050, 2100))
        assertFalse(snapshot(pip).isFloating(youtube))
    }

    @Test
    fun `an unknown display size never licenses a cover`() {
        val s = WindowSnapshot(listOf(floatingApp(1, youtube, focused = true)), 0, 0)
        assertNull(s.floatingBoundsOf(youtube))
    }

    @Test
    fun `the focused floating window is the BACK target, an unfocused one is not`() {
        assertEquals(2, snapshot(fullscreenApp(1, instagram, focused = false), floatingApp(2, youtube, focused = true)).focusedFloatingWindowOf(youtube))
        assertNull(snapshot(fullscreenApp(1, instagram, focused = true), floatingApp(2, youtube, focused = false)).focusedFloatingWindowOf(youtube))
        assertNull("a fullscreen app is never a floating target", snapshot(fullscreenApp(1, youtube)).focusedFloatingWindowOf(youtube))
    }

    @Test
    fun `non-application windows never own a package`() {
        // The clipboard panel is drawn by a package, but the list only resolves owners of app
        // windows; the classifier must identify it by its window kind, never by a guessed package.
        val s = snapshot(fullscreenApp(1, instagram), window(2, WindowKind.SYSTEM))
        assertTrue(s.applicationWindowsOf(clipboard).isEmpty())
    }
}
