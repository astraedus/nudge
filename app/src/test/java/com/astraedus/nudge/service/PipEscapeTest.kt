package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for issue #19: picture-in-picture defeats blocking — and for the v1.12.0 field failure of
 * the first attempt at fixing it.
 *
 * **What actually happens.** An app in PiP keeps playing in a floating always-on-top window. Our
 * block overlay is fullscreen and the top resumed activity and still loses; that is platform
 * behaviour. Nudge cannot flip the per-app PiP permission on the user's behalf, so the fix is
 * detect-and-deep-link.
 *
 * **Field failure 1 — detection never fired, silently.** On a live Pixel 3 / API 31 bubble the
 * accessibility window list contains TWO windows flagged `pictureInPicture=true`:
 * SystemUI's `Picture-in-Picture menu` (TYPE_SYSTEM, listed first) and the app's own window
 * (TYPE_APPLICATION). The first implementation took the first flagged window, resolved its owner to
 * `com.android.systemui`, never matched the blocked package, and returned false without logging.
 * Hence [PipWindowProbe.pipPackages] filtering to application windows, and returning a SET.
 *
 * **Field failure 2 — the stat-inflation loop outlived the block.** The first fix only looked for
 * an escape while a block overlay was live. In reality the overlay dismisses and the orphaned
 * bubble keeps firing events carrying the app's package, each read as a fresh foreground entry:
 * nine re-blocks in five minutes, +11 on the all-time Blocked count, firing while the tester was
 * inside Nudge itself. Hence [PipWindowProbe.pipOnlyPackages] and a gate over the whole pipeline.
 */
class PipEscapeTest {

    private val youtube = "com.google.android.youtube"
    private val systemui = "com.android.systemui"
    private val launcher = "com.google.android.apps.nexuslauncher"

    /**
     * The window list exactly as `dumpsys accessibility` reported it on the Pixel 3 with a live
     * YouTube PiP bubble and the launcher in front. Order matters: the SystemUI PiP menu really
     * does come first.
     */
    private fun livePixelPipWindows() = listOf(
        PipWindow(packageName = null, isPictureInPicture = false, isApplicationWindow = false),
        PipWindow(packageName = null, isPictureInPicture = false, isApplicationWindow = false),
        PipWindow(packageName = systemui, isPictureInPicture = true, isApplicationWindow = false),
        PipWindow(packageName = youtube, isPictureInPicture = true, isApplicationWindow = true),
        PipWindow(packageName = launcher, isPictureInPicture = false, isApplicationWindow = true)
    )

    // --- pipPackages: the regression that made detection silently impossible ---

    @Test
    fun `the app is found even though SystemUI's PiP menu is flagged PiP and sorts first`() {
        // Field failure 1, pinned. Taking the first flagged window yields com.android.systemui,
        // which never matches a blocked package, so detection returned false forever.
        assertEquals(setOf(youtube), PipWindowProbe.pipPackages(livePixelPipWindows()))
    }

    @Test
    fun `a PiP-flagged system window alone yields no app`() {
        assertEquals(
            emptySet<String>(),
            PipWindowProbe.pipPackages(
                listOf(
                    PipWindow(systemui, isPictureInPicture = true, isApplicationWindow = false)
                )
            )
        )
    }

    @Test
    fun `nothing in PiP yields nothing`() {
        assertEquals(emptySet<String>(), PipWindowProbe.pipPackages(emptyList()))
        assertEquals(
            emptySet<String>(),
            PipWindowProbe.pipPackages(
                listOf(PipWindow(youtube, isPictureInPicture = false, isApplicationWindow = true))
            )
        )
    }

    @Test
    fun `a PiP window whose owner could not be resolved is skipped, never guessed at`() {
        // getRoot() can fail or return null. Callers compare the result against packages they are
        // blocking, so a guess would suppress a real block.
        assertEquals(
            emptySet<String>(),
            PipWindowProbe.pipPackages(
                listOf(
                    PipWindow(null, isPictureInPicture = true, isApplicationWindow = true),
                    PipWindow("  ", isPictureInPicture = true, isApplicationWindow = true)
                )
            )
        )
    }

    // --- pipOnlyPackages: "has a window" is not "is the foreground app" ---

    @Test
    fun `an app in PiP while the user is elsewhere is PiP-only`() {
        // The orphaned-bubble case: the launcher (or Nudge itself) is in front, YouTube is a bubble.
        assertEquals(
            setOf(youtube),
            PipWindowProbe.pipOnlyPackages(setOf(youtube), activeWindowPackage = launcher)
        )
    }

    @Test
    fun `an app in PiP that IS the active window is not PiP-only`() {
        // Expanding the bubble back to fullscreen must re-block normally, not be swallowed.
        assertEquals(
            emptySet<String>(),
            PipWindowProbe.pipOnlyPackages(setOf(youtube), activeWindowPackage = youtube)
        )
    }

    @Test
    fun `an unreadable active window still counts PiP as PiP-only`() {
        // A window in PiP is by definition not the fullscreen foreground app; the active-window
        // read is only a guard for the expand transition. A missed block self-corrects when PiP
        // ends, whereas guessing the other way resurrects the re-block storm.
        assertEquals(
            setOf(youtube),
            PipWindowProbe.pipOnlyPackages(setOf(youtube), activeWindowPackage = null)
        )
    }

    @Test
    fun `the whole live-device pipeline resolves to exactly the escaping app`() {
        // End to end over the real captured window list: raw windows -> app in PiP -> PiP-only.
        val pip = PipWindowProbe.pipPackages(livePixelPipWindows())
        val pipOnly = PipWindowProbe.pipOnlyPackages(pip, activeWindowPackage = launcher)

        assertTrue("the escaping app is gated", youtube in pipOnly)
        assertFalse("SystemUI must never be gated", systemui in pipOnly)
        assertFalse("the real foreground app must never be gated", launcher in pipOnly)
    }

    // The throttle that used to live here moved with the window read itself, which is now ONE
    // read shared by PiP, the classifier and the floating-window cover (issues #76, #71). Its three
    // guards (one read per burst, a re-read once the window elapses, no false cache hit at clock 0)
    // are in `WindowSnapshotReaderTest`, against `WindowSnapshotReader.shouldRead`.

    // --- the explainer's "was this app actually blocked" gate ---

    @Test
    fun `only apps we actually blocked this session are worth explaining`() {
        NudgeAccessibilityService.clearBlockedThisSession()
        try {
            assertFalse(
                "an app floating in PiP that Nudge never blocked is unremarkable",
                NudgeAccessibilityService.hasBlockedThisSession(youtube)
            )

            NudgeAccessibilityService.markOverlayActive(youtube)
            assertTrue(
                "showing a block overlay for it is what makes a later PiP an escape",
                NudgeAccessibilityService.hasBlockedThisSession(youtube)
            )

            // The record must OUTLIVE the overlay: the reported repro reaches PiP minutes after the
            // block, via an emergency pass, with no overlay on screen. Tying the explainer to a live
            // block is exactly what made the first fix miss the real-world case.
            NudgeAccessibilityService.markOverlayInactive()
            assertTrue(
                NudgeAccessibilityService.hasBlockedThisSession(youtube)
            )
        } finally {
            NudgeAccessibilityService.clearBlockedThisSession()
        }
    }

    @Test
    fun `a blank package is never recorded as blocked`() {
        NudgeAccessibilityService.clearBlockedThisSession()
        try {
            NudgeAccessibilityService.markOverlayActive(null)
            NudgeAccessibilityService.markOverlayActive("")
            NudgeAccessibilityService.markOverlayActive("   ")
            assertFalse(NudgeAccessibilityService.hasBlockedThisSession(""))
            assertFalse(NudgeAccessibilityService.hasBlockedThisSession("   "))
        } finally {
            NudgeAccessibilityService.clearBlockedThisSession()
        }
    }
}
