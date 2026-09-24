package com.astraedus.nudge.domain.sitting

import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model at the heart of issue #28 bug 1.
 *
 * The pre-fix rule was "any foreign package's window event means the user left", and these tests
 * exist to make that rule unexpressible: a picker, a share sheet, a permission dialog and a volume
 * panel all arrive here as ordinary [ForegroundSignal.AppWindow]s or [ForegroundSignal.SystemSurface]s
 * with package names nobody hardcoded, and none of them may end a sitting.
 */
class SittingTrackerTest {

    private val window = 5L * 60L * 1000L
    private fun tracker() = SittingTracker(returnWindowMs = window)

    private val keep = "com.google.android.keep"
    private val picker = "com.google.android.providers.media.module"
    private val shareSheet = "com.android.intentresolver"
    private val pixelPermissions = "com.google.android.permissioncontroller"
    private val launcher = "com.google.android.apps.nexuslauncher"

    private fun SittingTracker.app(pkg: String, at: Long) =
        onSignal(ForegroundSignal.AppWindow(pkg), at)

    @Test
    fun `the first app window starts a sitting`() {
        val t = tracker()
        assertEquals(SittingEvent.Started(keep, null), t.app(keep, 0))
        assertEquals(keep, t.currentApp)
    }

    @Test
    fun `staying in the same app changes nothing`() {
        val t = tracker()
        t.app(keep, 0)
        assertEquals(SittingEvent.Unchanged, t.app(keep, 1_000))
        assertEquals(SittingEvent.Unchanged, t.app(keep, 2_000))
    }

    // --- the reported bug, one test per reported sub-flow ------------------------------------

    /**
     * The exact repro from the issue: Tinder's "Add photos". Captured on a Pixel 3 as
     * `app/src/test/resources/a11y-captures/picker-subflow-keeps-sitting.jsonl`, where the pre-fix
     * build logged `passthrough cleared on app switch package=com.google.android.providers.media.module`
     * and re-blocked on return.
     */
    @Test
    fun `a photo picker excursion does not end the sitting`() {
        val t = tracker()
        t.app(keep, 0)
        assertEquals(SittingEvent.Unchanged, t.app(picker, 1_000))
        assertEquals(SittingEvent.Unchanged, t.app(keep, 9_000))
        assertEquals(keep, t.currentApp)
    }

    @Test
    fun `a share sheet excursion does not end the sitting`() {
        val t = tracker()
        t.app(keep, 0)
        assertEquals(SittingEvent.Unchanged, t.app(shareSheet, 500))
        assertEquals(SittingEvent.Unchanged, t.app(keep, 4_000))
    }

    /**
     * The Pixel's permission dialog is `com.google.android.permissioncontroller`, NOT the
     * `com.android.permissioncontroller` that was in `SYSTEM_PACKAGES`. Under the old model that one
     * character difference revoked the grant; under this one it does not matter which of the two it
     * is, or whether we have ever heard of the package at all.
     */
    @Test
    fun `an unrecognised permission dialog package does not end the sitting`() {
        val t = tracker()
        t.app(keep, 0)
        assertEquals(SittingEvent.Unchanged, t.app(pixelPermissions, 200))
        assertEquals(SittingEvent.Unchanged, t.app(keep, 3_000))
    }

    @Test
    fun `a package nobody has ever heard of does not end the sitting`() {
        val t = tracker()
        t.app(keep, 0)
        assertEquals(SittingEvent.Unchanged, t.app("com.some.oem.volumepanel", 100))
        assertEquals(SittingEvent.Unchanged, t.app(keep, 2_000))
    }

    @Test
    fun `several different sub-flows in a row still do not end the sitting`() {
        val t = tracker()
        t.app(keep, 0)
        t.app(picker, 1_000)
        t.app(shareSheet, 2_000)
        t.app(pixelPermissions, 3_000)
        assertEquals(SittingEvent.Unchanged, t.app(keep, 4_000))
        assertEquals(keep, t.currentApp)
    }

    // --- what DOES end a sitting -------------------------------------------------------------

    /**
     * The re-pin of the old `a different app still clears passthrough` test. The behaviour is not
     * deleted, it is moved onto the return window: a genuine app switch is one you do not come back
     * from within [window].
     */
    @Test
    fun `another app held in front past the return window ends the sitting`() {
        val t = tracker()
        t.app(keep, 0)
        t.app("com.other.app", 1_000)
        val event = t.app("com.other.app", 1_000 + window)
        assertEquals(
            SittingEvent.Started(
                "com.other.app",
                SittingEvent.Ended(keep, SittingEndCause.ANOTHER_APP_HELD_FOREGROUND)
            ),
            event
        )
    }

    /**
     * The away clock must still be read when the user comes BACK, not only while they are gone —
     * otherwise a ten-minute detour followed by a return would be indistinguishable from a
     * two-second one, because the return event's package matches the sitting's.
     */
    @Test
    fun `returning after longer than the return window restarts the sitting`() {
        val t = tracker()
        t.app(keep, 0)
        t.app("com.other.app", 1_000)
        val event = t.app(keep, 1_000 + window + 1)
        assertEquals(
            SittingEvent.Started(
                keep,
                SittingEvent.Ended(keep, SittingEndCause.ANOTHER_APP_HELD_FOREGROUND)
            ),
            event
        )
    }

    /** The away clock measures from when the user LEFT, not from the last app they passed through. */
    @Test
    fun `the away clock is not restarted by hopping between other apps`() {
        val t = tracker()
        t.app(keep, 0)
        t.app("com.a", 1_000)
        t.app("com.b", 1_000 + window - 10)
        val event = t.app(keep, 1_000 + window + 1)
        assertTrue("a long absence must end the sitting even via several apps", event is SittingEvent.Started)
    }

    @Test
    fun `going home ends the sitting immediately`() {
        val t = tracker()
        t.app(keep, 0)
        assertEquals(
            SittingEvent.Ended(keep, SittingEndCause.WENT_HOME),
            t.onSignal(ForegroundSignal.Home(launcher), 1_000)
        )
        assertNull(t.currentApp)
    }

    @Test
    fun `ending a sitting that does not exist is a no-op`() {
        val t = tracker()
        assertEquals(SittingEvent.Unchanged, t.onScreenOff(0))
        assertEquals(SittingEvent.Unchanged, t.onSignal(ForegroundSignal.Home(launcher), 0))
    }

    // --- the screen going off (#54, backlog F5) -------------------------------------------------

    /**
     * ISSUE [#54](https://github.com/astraedus/nudge/issues/54), the reported case, in the smallest
     * form it exists in.
     *
     * The user who asked for HOLD mode, by email 2026-09-24: *"I hold 60 second to unlock and then
     * after 3-5 minute of use it again Askin for hold and also even after just 1 minute ahead asking
     * for hold to unlock ... I have some client info on it and chat with them"*. Reading a chat
     * inside the blocked app produces no touches, the display times out (Pixel default: 30 seconds),
     * and the pre-fix rule ended the sitting on the broadcast alone — so unlocking straight back
     * into the same app was a brand-new arrival and cost the full hold again.
     */
    @Test
    fun `a display timeout the user returns from does not end the sitting`() {
        val t = tracker()
        t.app(keep, 0)

        assertEquals(SittingEvent.Unchanged, t.onScreenOff(30_000))
        assertEquals(keep, t.currentApp)

        // The keyguard, then straight back into the app they never left.
        t.onSignal(ForegroundSignal.SystemSurface("com.android.systemui"), 75_000)
        assertEquals(SittingEvent.Unchanged, t.app(keep, 76_000))
        assertEquals(keep, t.currentApp)
        assertNull("the return closes the away clock", t.awaySinceMs)
    }

    /**
     * Backlog F5, unchanged: *complete Instagram's delay, lock the phone, unlock hours later
     * straight back into Instagram, no delay.* Hours is far past the return window, so the sitting
     * is over and the next entry is a fresh block — and it says SCREEN_OFF, not the away clock's
     * default cause.
     */
    @Test
    fun `a phone locked past the return window ends the sitting, and says why`() {
        val t = tracker()
        t.app(keep, 0)
        t.onScreenOff(1_000)

        assertEquals(
            SittingEvent.Started(keep, SittingEvent.Ended(keep, SittingEndCause.SCREEN_OFF)),
            t.app(keep, 1_000 + window)
        )
        assertEquals(keep, t.currentApp)
    }

    /**
     * **The counterfactual, on one stream.** The pre-fix rule was `end(SCREEN_OFF)` with no clock
     * read at all, so BOTH rows below ended the sitting and the two cases were indistinguishable.
     * Running the identical sequence at the two durations either side of the threshold, and getting
     * two different answers, is what proves the LENGTH of the absence is now what decides. Anything
     * that reverts to answering on the broadcast alone fails the first row.
     */
    @Test
    fun `a screen-off is decided by its length, not by the broadcast`() {
        listOf(
            Triple("a display timeout", window - 1, false),
            Triple("a locked phone", window, true)
        ).forEach { (label, awayMs, endsIt) ->
            val t = tracker()
            t.app(keep, 0)
            t.onScreenOff(0)
            val event = t.app(keep, awayMs)
            if (endsIt) {
                assertEquals(
                    label,
                    SittingEvent.Started(keep, SittingEvent.Ended(keep, SittingEndCause.SCREEN_OFF)),
                    event
                )
            } else {
                assertEquals(label, SittingEvent.Unchanged, event)
            }
        }
    }

    /**
     * A screen-off with a sitting already away must not restart the clock. The user left when they
     * switched apps, not when the display blanked; measuring from the later moment would hand out a
     * free window nobody earned, and a phone that blanks every thirty seconds would extend it
     * forever.
     */
    @Test
    fun `a screen-off does not restart an away clock another app already started`() {
        val t = tracker()
        t.app(keep, 0)
        t.app(picker, 1_000)
        t.onScreenOff(60_000)

        assertEquals(1_000L, t.awaySinceMs)
        assertEquals(
            SittingEvent.Started(keep, SittingEvent.Ended(keep, SittingEndCause.ANOTHER_APP_HELD_FOREGROUND)),
            t.app(keep, 1_000 + window)
        )
    }

    /** ...and the reverse: an app coming forward during a screen-off keeps the screen-off's clock. */
    @Test
    fun `unlocking into a different app measures from when the screen went dark`() {
        val t = tracker()
        t.app(keep, 0)
        t.onScreenOff(1_000)

        // They unlock into another app well inside the window: keep's sitting is still theirs.
        assertEquals(SittingEvent.Unchanged, t.app(picker, 2_000))
        assertEquals(1_000L, t.awaySinceMs)
        assertEquals(keep, t.currentApp)

        // Past it, keep's sitting ends and the cause is still the evidence that started the clock.
        assertEquals(
            SittingEvent.Started(picker, SittingEvent.Ended(keep, SittingEndCause.SCREEN_OFF)),
            t.app(picker, 1_000 + window)
        )
    }

    /**
     * Going home is still instant, screen off or not. Home is the one gesture that unambiguously
     * means "I am leaving this app", and the whole point of #54's fix is that a screen-off is NOT
     * that gesture — so the two must not start behaving alike in either direction.
     */
    @Test
    fun `unlocking to the launcher still ends the sitting at once`() {
        val t = tracker()
        t.app(keep, 0)
        t.onScreenOff(1_000)

        assertEquals(
            SittingEvent.Ended(keep, SittingEndCause.WENT_HOME),
            t.onSignal(ForegroundSignal.Home(launcher), 2_000)
        )
        assertNull(t.currentApp)
        assertNull(t.awaySinceMs)
    }

    /** A grant earned after the screen came back on closes the clock, like any other return. */
    @Test
    fun `a grant closes an away clock a screen-off started`() {
        val t = tracker()
        t.app(keep, 0)
        t.onScreenOff(1_000)

        assertEquals(SittingEvent.Unchanged, t.onGrantEarned(keep))
        assertNull(t.awaySinceMs)
        assertEquals(SittingEvent.Unchanged, t.app(keep, 1_000 + window))
    }

    // --- signals that must be structurally incapable of ending a sitting ---------------------

    @Test
    fun `no non-app signal can end a sitting`() {
        val inert = listOf(
            ForegroundSignal.SystemSurface("com.android.systemui"),
            ForegroundSignal.OwnUi("dev.astraedus.nudge"),
            ForegroundSignal.AwarenessOverlay("dev.astraedus.nudge"),
            ForegroundSignal.Transient("com.futo.inputmethod.latin"),
            ForegroundSignal.PipOnly("com.google.android.youtube"),
            ForegroundSignal.NotForeground("com.instagram.android")
        )
        inert.forEach { signal ->
            val t = tracker()
            t.app(keep, 0)
            assertEquals(
                "$signal must never end a sitting",
                SittingEvent.Unchanged,
                t.onSignal(signal, 1_000)
            )
            assertEquals(keep, t.currentApp)
        }
    }

    /**
     * The IME is the canonical "surfaced without the user leaving" window (issue #5), and the shade
     * is the canonical "system surface that is not the launcher". Neither may start the away clock,
     * or a long keyboard session would age a sitting out from under a user who never left.
     */
    @Test
    fun `a keyboard or a system surface does not start the away clock`() {
        val t = tracker()
        t.app(keep, 0)
        t.onSignal(ForegroundSignal.Transient("com.futo.inputmethod.latin"), 1_000)
        t.onSignal(ForegroundSignal.SystemSurface("com.android.systemui"), 2_000)
        assertNull(t.awaySinceMs)
        assertEquals(SittingEvent.Unchanged, t.app(keep, 10L * 60L * 1000L))
    }

    // --- grants -------------------------------------------------------------------------------

    /**
     * A grant is unambiguous evidence about where the user is. Without this, completing app B's
     * delay while the tracker still believed the sitting belonged to app A would leave B's new grant
     * attached to A's sitting, and the next transition would revoke a pass the user just earned.
     */
    @Test
    fun `earning a grant moves the sitting to that app immediately`() {
        val t = tracker()
        t.app(keep, 0)
        val event = t.onGrantEarned("com.instagram.android")
        assertEquals(
            SittingEvent.Started(
                "com.instagram.android",
                SittingEvent.Ended(keep, SittingEndCause.ANOTHER_APP_HELD_FOREGROUND)
            ),
            event
        )
        assertEquals("com.instagram.android", t.currentApp)
    }

    @Test
    fun `earning a grant for the app already sat with clears the away clock`() {
        val t = tracker()
        t.app(keep, 0)
        t.app(picker, 1_000)
        assertEquals(SittingEvent.Unchanged, t.onGrantEarned(keep))
        assertNull(t.awaySinceMs)
    }

    @Test
    fun `reset forgets everything`() {
        val t = tracker()
        t.app(keep, 0)
        t.reset()
        assertNull(t.currentApp)
        assertEquals(SittingEvent.Started("com.other", null), t.app("com.other", 1))
    }
}
