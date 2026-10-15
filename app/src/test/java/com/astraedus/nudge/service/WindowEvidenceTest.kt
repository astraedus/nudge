package com.astraedus.nudge.service

import com.astraedus.nudge.NudgeIdentity
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.sitting.SittingEvent
import com.astraedus.nudge.domain.sitting.SittingTracker
import com.astraedus.nudge.domain.windows.WindowFixtures.floatingApp
import com.astraedus.nudge.domain.windows.WindowFixtures.fullscreenApp
import com.astraedus.nudge.domain.windows.WindowFixtures.snapshot
import com.astraedus.nudge.domain.windows.WindowFixtures.window
import com.astraedus.nudge.domain.windows.WindowKind
import com.astraedus.nudge.domain.windows.WindowSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issues [#76](https://github.com/astraedus/nudge/issues/76) and
 * [#71](https://github.com/astraedus/nudge/issues/71): the classifier now asks the WINDOW, not only
 * the event's package. Layer L1, through the production classifier wired from the service's own
 * package sets, and through the real [SittingTracker] for the end-to-end #76 case.
 *
 * Why not L2 (a recorded capture): neither report came with a device we can capture on (a Samsung
 * S24 Ultra with SwiftKey, a Huawei on Android 10), and `scripts/a11y-capture.sh` does not record
 * the window list. The window list each test builds is the shape the platform reports
 * (`AccessibilityWindowInfo` types), and the bench-device QA script in the PR covers the freeform
 * case on a Pixel 3. The service now logs the window list on every change at debug level
 * (`windows changed ...`), so the next capture of either bug carries it.
 */
class WindowEvidenceTest {

    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"
    private val swiftKey = "com.touchtype.swiftkey"
    private val gms = "com.google.android.gms"
    private val writingTools = "com.samsung.android.app.writingtoolkit"

    private val igWindow = 10
    private val imeWindow = 20
    private val clipboardWindow = 30

    private var windows: WindowSnapshot? = null

    private val classifier = EventClassifier(
        ownPackageName = NudgeIdentity.APPLICATION_ID,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = { NudgeAccessibilityService.IME_PACKAGES },
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES,
        windows = { windows }
    )

    private fun stateChange(pkg: String, windowId: Int = AccessibilityEventRecord.UNDEFINED) =
        AccessibilityEventRecord(type = A11yEventType.WINDOW_STATE_CHANGED, packageName = pkg, windowId = windowId)

    private fun classify(record: AccessibilityEventRecord, currentIme: String? = swiftKey) =
        classifier.classify(record, currentIme, launcherPackages = emptySet(), pipOnlyPackages = emptySet())

    /** Instagram fullscreen and focused (the DM), the keyboard up over it. */
    private fun typingInInstagram(vararg extra: com.astraedus.nudge.domain.windows.ScreenWindow) = snapshot(
        fullscreenApp(igWindow, instagram, focused = true),
        window(imeWindow, WindowKind.INPUT_METHOD),
        *extra
    )

    // --- #76: what appears with the keyboard is not the user leaving -------------------------

    @Test
    fun `IME only - the keyboard's own window events are transient`() {
        windows = typingInInstagram()
        assertTrue(classify(stateChange(swiftKey, imeWindow)) is ForegroundSignal.Transient)
    }

    @Test
    fun `a keyboard on no list is transient by its WINDOW even when the settings read missed it`() {
        windows = typingInInstagram()
        // currentIme = null: the default-keyboard read failed. The window type still says keyboard.
        assertTrue(classify(stateChange("org.example.newkeyboard", imeWindow), currentIme = null) is ForegroundSignal.Transient)
    }

    @Test
    fun `autofill panel from another package inside the keyboard window is transient`() {
        // Inline autofill suggestions are rendered by the autofill service INSIDE the IME's window.
        windows = typingInInstagram()
        assertTrue(classify(stateChange(gms, imeWindow)) is ForegroundSignal.Transient)
    }

    @Test
    fun `autofill panel reported with a window id the list does not show is transient while typing`() {
        // An embedded inline-suggestion view is not a window of its own, so its id may be unknown.
        // Keyboard up, Instagram focused, the package owning no window at all: drawn with the keyboard.
        windows = typingInInstagram()
        assertTrue(classify(stateChange(gms, windowId = 4242)) is ForegroundSignal.Transient)
    }

    @Test
    fun `a system overlay beside the keyboard (Samsung clipboard) is transient`() {
        windows = typingInInstagram(window(clipboardWindow, WindowKind.SYSTEM))
        assertTrue(classify(stateChange("com.samsung.android.clipboarduiservice", clipboardWindow)) is ForegroundSignal.Transient)
    }

    @Test
    fun `an unfocused application panel from another package is transient`() {
        windows = typingInInstagram(floatingApp(40, writingTools, focused = false))
        assertTrue(classify(stateChange(writingTools, 40)) is ForegroundSignal.Transient)
    }

    @Test
    fun `a full-screen normal switch is still an app window, and still counts as leaving`() {
        // The control that keeps this fix from being a bypass: YouTube really came to the front.
        windows = snapshot(fullscreenApp(igWindow, instagram, focused = false), fullscreenApp(50, youtube, focused = true))
        assertEquals(ForegroundSignal.AppWindow(youtube), classify(stateChange(youtube, 50)))

        val tracker = SittingTracker()
        tracker.onSignal(ForegroundSignal.AppWindow(instagram), 0L)
        tracker.onSignal(classify(stateChange(youtube, 50)), 1_000L)
        val back = tracker.onSignal(ForegroundSignal.AppWindow(instagram), 1_000L + SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS)
        assertTrue("two minutes in YouTube must still end the Instagram sitting", back is SittingEvent.Started)
    }

    @Test
    fun `no window info fails open to the package-only answer`() {
        windows = null
        assertEquals(ForegroundSignal.AppWindow(gms), classify(stateChange(gms, imeWindow)))
        assertEquals(ForegroundSignal.AppWindow(writingTools), classify(stateChange(writingTools, 40)))
    }

    @Test
    fun `an app window the list does not show yet, with no keyboard up, is an app window`() {
        // A brand-new app can be newer than the list. Absence is never a reason to skip evaluating it.
        windows = snapshot(fullscreenApp(igWindow, instagram, focused = true))
        assertEquals(ForegroundSignal.AppWindow(youtube), classify(stateChange(youtube, windowId = 77)))
    }

    @Test
    fun `issue 76 end to end - keyboard up for three minutes, closed, the sitting survives`() {
        assertEquals("the fix must hold", SittingEvent.Unchanged, replay76(withWindows = true))
    }

    @Test
    fun `issue 76 counterfactual - without window evidence the same stream revokes the grant`() {
        // Proves the stream really contains the defect, so the test above cannot pass by luck.
        assertTrue(replay76(withWindows = false) is SittingEvent.Started)
    }

    /**
     * Sitting in Instagram; the keyboard opens with an autofill panel from another package and a
     * clipboard overlay; the user types for three minutes (IME events only); the keyboard closes
     * and Instagram's window fires. Returns the sitting event that last window produced.
     */
    private fun replay76(withWindows: Boolean): SittingEvent {
        val tracker = SittingTracker()
        val withKeyboard = typingInInstagram(window(clipboardWindow, WindowKind.SYSTEM))
        fun at(nowMs: Long, record: AccessibilityEventRecord, list: WindowSnapshot?): SittingEvent {
            windows = if (withWindows) list else null
            return tracker.onSignal(classify(record), nowMs)
        }
        at(0L, stateChange(instagram, igWindow), snapshot(fullscreenApp(igWindow, instagram)))
        at(1_000L, stateChange(swiftKey, imeWindow), withKeyboard)
        at(1_200L, stateChange(gms, imeWindow), withKeyboard)
        at(1_400L, stateChange("com.samsung.android.clipboarduiservice", clipboardWindow), withKeyboard)
        // ... three minutes of typing: IME clicks, transient, never reach the sitting ...
        return at(181_000L, stateChange(instagram, igWindow), snapshot(fullscreenApp(igWindow, instagram)))
    }

    // --- #71: a floating window is not a bypass of the overlay --------------------------------

    @Test
    fun `freeform blocked window - the user IS in it, but touching it is not a bypass`() {
        val overlay = fullscreenApp(1, NudgeIdentity.APPLICATION_ID, focused = false)
        windows = snapshot(overlay, floatingApp(60, youtube, focused = true))
        val signal = classify(stateChange(youtube, 60))
        assertEquals("a focused floating window is where the user is", ForegroundSignal.AppWindow(youtube), signal)

        val floating = windows!!.isFloating(youtube)
        assertTrue(floating)
        assertFalse(
            "the relaunch loop: a floating window above the overlay must never read as a bypass",
            BlockLaunchGate.isGenuineBypass(A11yEventType.WINDOW_STATE_CHANGED, signal, pending = null, nowMs = 0L, floating = floating)
        )
        assertTrue(
            "counterfactual: the same event without the floating fact is the pre-fix bypass",
            BlockLaunchGate.isGenuineBypass(A11yEventType.WINDOW_STATE_CHANGED, signal, pending = null, nowMs = 0L, floating = false)
        )
    }

    @Test
    fun `a fullscreen return into the blocked app is still a bypass`() {
        windows = snapshot(fullscreenApp(60, youtube, focused = true))
        val signal = classify(stateChange(youtube, 60))
        assertTrue(
            BlockLaunchGate.isGenuineBypass(
                A11yEventType.WINDOW_STATE_CHANGED, signal, pending = null, nowMs = 0L,
                floating = windows!!.isFloating(youtube)
            )
        )
    }

    @Test
    fun `the content-change fallback is asked the same window question`() {
        // A content change from a system overlay the user touched (it owns the active window) is
        // still not an app the user is in.
        windows = typingInInstagram(window(clipboardWindow, WindowKind.SYSTEM))
        val record = AccessibilityEventRecord(
            type = A11yEventType.WINDOW_CONTENT_CHANGED,
            packageName = "com.samsung.android.clipboarduiservice",
            windowId = clipboardWindow
        )
        val signal = classifier.classifyVerifiedContentChangeAsSwitch(record, swiftKey, emptySet(), emptySet())
        assertTrue(signal is ForegroundSignal.Transient)
    }
}
