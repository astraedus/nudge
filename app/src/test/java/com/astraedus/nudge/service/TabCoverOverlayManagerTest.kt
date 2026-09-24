package com.astraedus.nudge.service

import android.content.Context
import android.view.WindowManager
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.surfaces.InstagramSurfaces
import com.astraedus.nudge.domain.surfaces.TabCoverEffect
import com.astraedus.nudge.domain.surfaces.TabCoverPlacement
import com.astraedus.nudge.util.NudgeLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The tab cover's window LIFECYCLE: added once rather than on every content change, MOVED rather than
 * re-added, and torn down on exactly the signals that mean the user left.
 *
 * Layer **L3** (`docs/testing-strategy.md`): a lifecycle decision, driven as values. The class is
 * reached through its `coverViewFactory` seam because every `android.view.View` instance method is a
 * throwing stub in these JVM tests, so the view itself must be a mock; the `WindowManager` is a mock
 * handed over by the service context, which is how production gets it too. What CANNOT be reached this
 * way — that the view is an `AwarenessOverlayWindow` type and not a bare widget — is pinned at source
 * level by `AwarenessOverlayContractTest`, which is why that test's list includes this manager.
 *
 * The bounds below are Instagram's measured `clips_tab` rectangle from the 2026-09-22 spike, run
 * through the same [TabCoverPlacement.of] production uses rather than hand-converted to x/y/w/h.
 */
class TabCoverOverlayManagerTest {

    private val windowManager = mockk<WindowManager>(relaxed = true)
    private val serviceContext = mockk<Context>(relaxed = true)
    private val coverView = mockk<AwarenessOverlayWindow.Container>(relaxed = true)
    private lateinit var manager: TabCoverOverlayManager

    private val instagram = InstagramSurfaces.packageName
    private val reelsLabel = InstagramSurfaces.DESC_REELS_TAB
    private val navColor = InstagramSurfaces.navBarColor(nightMode = false)

    /** `clips_tab` [216,1896][432,2028], measured. */
    private val clipsTab = requireNotNull(TabCoverPlacement.of(216, 1896, 432, 2028))

    /** `tab_bar` [0,1896][1080,2028] — a different, also-real rectangle, for the move case. */
    private val wholeBar = requireNotNull(TabCoverPlacement.of(0, 1896, 1080, 2028))

    @Before
    fun setUp() {
        every { serviceContext.getSystemService(Context.WINDOW_SERVICE) } returns windowManager
        manager = TabCoverOverlayManager(mockk(relaxed = true), mockk<NudgeLogger>(relaxed = true))
        manager.coverViewFactory = { coverView }
        manager.setServiceContext(serviceContext)
    }

    private fun show(placement: TabCoverPlacement = clipsTab, label: String = reelsLabel) {
        manager.apply(instagram, TabCoverEffect.Show(placement), navColor, label)
    }

    // ------------------------------------------------------------------------------------------
    // Nothing shown: every call must be a no-op, because they all arrive constantly.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `hiding when nothing is shown touches no window`() {
        manager.hide()
        manager.apply(instagram, TabCoverEffect.Hide, navColor, reelsLabel)

        verify(exactly = 0) { windowManager.removeView(any()) }
        assertFalse(manager.isVisible())
        assertNull(manager.coveredPackage())
    }

    /**
     * `None` is the answer on the overwhelming majority of events — every content change while the
     * user scrolls a feed. It must cost nothing at all, not even a window call.
     */
    @Test
    fun `the None effect does no window work in either state`() {
        manager.apply(instagram, TabCoverEffect.None, navColor, reelsLabel)
        show()
        manager.apply(instagram, TabCoverEffect.None, navColor, reelsLabel)

        assertTrue(manager.isVisible())
        verify(exactly = 1) { windowManager.addView(any(), any()) }
        verify(exactly = 0) { windowManager.updateViewLayout(any(), any()) }
        verify(exactly = 0) { windowManager.removeView(any()) }
    }

    // ------------------------------------------------------------------------------------------
    // No churn. The cost of getting this wrong is a flickering rectangle and a re-fired window
    // event on every scroll tick (the issue #41 shape).
    // ------------------------------------------------------------------------------------------

    @Test
    fun `showing twice with the same placement adds the window once`() {
        show()
        show()

        verify(exactly = 1) { windowManager.addView(coverView, any()) }
        verify(exactly = 0) { windowManager.updateViewLayout(any(), any()) }
        verify(exactly = 0) { windowManager.removeView(any()) }
        assertEquals(instagram, manager.coveredPackage())
    }

    @Test
    fun `a show with different bounds moves the window instead of re-adding it`() {
        show(clipsTab)
        show(wholeBar)

        verify(exactly = 1) { windowManager.addView(coverView, any()) }
        verify(exactly = 1) { windowManager.updateViewLayout(coverView, any()) }
        verify(exactly = 0) { windowManager.removeView(any()) }
        assertTrue(manager.isVisible())
    }

    // ------------------------------------------------------------------------------------------
    // The window itself.
    // ------------------------------------------------------------------------------------------

    /**
     * The placement's four numbers must reach the layout params unchanged. A cover drawn at the wrong
     * coordinates is an opaque block over something the user did not ask to have covered.
     */
    @Test
    fun `the placement reaches the layout params verbatim`() {
        val params = slot<WindowManager.LayoutParams>()
        show(clipsTab)
        verify { windowManager.addView(coverView, capture(params)) }

        assertEquals(clipsTab.x, params.captured.x)
        assertEquals(clipsTab.y, params.captured.y)
        assertEquals(clipsTab.width, params.captured.width)
        assertEquals(clipsTab.height, params.captured.height)
    }

    /**
     * **`FLAG_NOT_TOUCHABLE` must be absent.** Eating the tap is the whole feature: with that flag the
     * Reels icon would be hidden and the invisible tab would still open Reels, which is worse than
     * shipping nothing. `CounterOverlayManager` sets it for the opposite reason, and this assertion is
     * what stops somebody reconciling the two.
     */
    @Test
    fun `the cover window is touchable, unfocusable, and laid out in screen coordinates`() {
        val params = slot<WindowManager.LayoutParams>()
        show()
        verify { windowManager.addView(coverView, capture(params)) }
        val flags = params.captured.flags

        assertEquals(
            "eating the tap IS the feature; FLAG_NOT_TOUCHABLE would hide the icon and leave the tab live",
            0,
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        assertTrue(
            "the cover must never steal input focus from the host app",
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        )
        assertTrue(
            "bounds come from getBoundsInScreen, so the window is positioned in screen coordinates",
            flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0
        )
        assertTrue(
            "the nav bar sits inside the system-window inset region",
            flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0
        )
        assertEquals(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            params.captured.type
        )
    }

    /**
     * Nudge is an accessibility-service app. An unlabelled opaque region over somebody's nav bar is
     * the wrong end of that: a TalkBack user sweeping the bottom nav would land where the Reels tab
     * was and be told nothing at all. The word comes from the CALLER, so this class never hardcodes a
     * feature name.
     */
    @Test
    fun `the cover is labelled for talkback from the label it was given`() {
        val description = slot<CharSequence>()
        show(label = reelsLabel)
        verify { coverView.contentDescription = capture(description) }

        val spoken = description.captured.toString()
        assertTrue("the description must not be empty", spoken.isNotBlank())
        assertTrue("it must name what was covered, got '$spoken'", spoken.contains(reelsLabel))
        verify { coverView.setBackgroundColor(navColor) }
        verify { coverView.isClickable = true }
    }

    @Test
    fun `a blank label still yields a spoken description`() {
        val description = slot<CharSequence>()
        show(label = "  ")
        verify { coverView.contentDescription = capture(description) }

        assertTrue(description.captured.toString().isNotBlank())
    }

    // ------------------------------------------------------------------------------------------
    // Teardown. The decision is TabCoverPresence's; this asserts the manager DELEGATES it.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `an app window for another package tears the cover down`() {
        show()
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.google.android.keep"))

        verify(exactly = 1) { windowManager.removeView(coverView) }
        assertFalse(manager.isVisible())
        assertNull(manager.coveredPackage())
    }

    @Test
    fun `an app window for the covered package keeps it`() {
        show()
        manager.onForegroundSignal(ForegroundSignal.AppWindow(instagram))

        verify(exactly = 0) { windowManager.removeView(any()) }
        assertTrue(manager.isVisible())
    }

    /**
     * The case that would otherwise eat itself. The cover IS an awareness overlay: its own window
     * fires window and content events carrying Nudge's package, so a manager that tore down on
     * `AwarenessOverlay` would order the cover away the instant it appeared, on its own event. That is
     * the identical shape as issue #41.
     */
    @Test
    fun `an awareness overlay signal does not tear the cover down`() {
        show()
        manager.onForegroundSignal(ForegroundSignal.AwarenessOverlay("dev.astraedus.nudge"))

        verify(exactly = 0) { windowManager.removeView(any()) }
        assertTrue("the cover must not order itself away on its own window event", manager.isVisible())
    }

    /** A content change, a scroll, a click: no window at all, and the most common signal there is. */
    @Test
    fun `a signal carrying no foreground claim does not tear the cover down`() {
        show()
        manager.onForegroundSignal(ForegroundSignal.NotForeground(instagram))
        manager.onForegroundSignal(ForegroundSignal.Transient("com.google.android.inputmethod.latin"))
        manager.onForegroundSignal(ForegroundSignal.PipOnly("com.google.android.youtube"))

        verify(exactly = 0) { windowManager.removeView(any()) }
        assertTrue(manager.isVisible())
    }

    @Test
    fun `going home tears the cover down`() {
        show()
        manager.onForegroundSignal(ForegroundSignal.Home("com.google.android.apps.nexuslauncher"))

        verify(exactly = 1) { windowManager.removeView(coverView) }
        assertFalse(manager.isVisible())
    }

    @Test
    fun `the explicit Hide effect tears the cover down`() {
        show()
        manager.apply(instagram, TabCoverEffect.Hide, navColor, reelsLabel)

        verify(exactly = 1) { windowManager.removeView(coverView) }
        assertFalse(manager.isVisible())
    }

    // ------------------------------------------------------------------------------------------
    // Failure paths. A window that cannot be built or moved must leave NO state claiming it exists.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `no service context means no cover and no claim that one exists`() {
        manager.clearServiceContext()
        show()

        verify(exactly = 0) { windowManager.addView(any(), any()) }
        assertFalse(manager.isVisible())
        assertNull(manager.coveredPackage())
    }

    @Test
    fun `a window manager that refuses the add leaves no stale state`() {
        every { windowManager.addView(any(), any()) } throws IllegalStateException("bad token")
        show()

        assertFalse(manager.isVisible())
        assertNull(manager.coveredPackage())
    }

    /**
     * A cover that cannot be moved is an opaque rectangle at the OLD coordinates, which is worse than
     * no cover. It is dropped so the next decision can re-add it cleanly.
     */
    @Test
    fun `a move that fails drops the cover rather than leaving it misplaced`() {
        show(clipsTab)
        every { windowManager.updateViewLayout(any(), any()) } throws IllegalArgumentException("gone")
        show(wholeBar)

        assertFalse(manager.isVisible())
        assertNull(manager.coveredPackage())
    }

    @Test
    fun `clearing the service context takes the cover with it`() {
        show()
        manager.clearServiceContext()

        verify(exactly = 1) { windowManager.removeView(coverView) }
        assertFalse(manager.isVisible())
    }
}
