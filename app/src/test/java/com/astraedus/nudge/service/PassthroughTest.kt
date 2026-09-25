package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityEvent
import com.astraedus.nudge.NudgeIdentity
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.sitting.SittingTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PassthroughTest {

    private lateinit var manager: PassthroughManager

    /**
     * The real classifier, wired from the service's own package sets.
     *
     * The overlay-bypass check no longer re-derives "is this a real app window" from package names;
     * it takes the already-computed [ForegroundSignal]. So these tests must build that signal by
     * CLASSIFYING an event, exactly as the service does. Hand-constructing a
     * `ForegroundSignal.AppWindow("com.android.systemui")` would compile, pass, and prove nothing
     * about the pipeline that actually runs on a device.
     */
    private val classifier = EventClassifier(
        ownPackageName = OWN_PACKAGE,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = NudgeAccessibilityService.IME_PACKAGES,
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES
    )

    private fun signalFor(
        packageName: String,
        type: A11yEventType,
        currentImePackage: String? = null,
        launcherPackages: Set<String> = emptySet()
    ): ForegroundSignal = classifier.classify(
        AccessibilityEventRecord(type = type, packageName = packageName),
        currentImePackage = currentImePackage,
        launcherPackages = launcherPackages,
        pipOnlyPackages = emptySet()
    )

    @Before
    fun setUp() {
        manager = PassthroughManager()
    }

    @Test
    fun `grantPassthrough marks package as passed through`() {
        manager.grant("com.example.alpha")

        assertTrue(manager.isGranted("com.example.alpha"))
        assertFalse(manager.isGranted("com.example.beta"))
    }

    @Test
    fun `passthrough clears on a real app switch`() {
        manager.grant("com.example.alpha")

        // Issue #28: a foreign app window no longer revokes on sight. It revokes once that app has
        // held the foreground past the return window, which is what a real switch looks like.
        manager.onForegroundSignal(ForegroundSignal.AppWindow("com.example.beta"), 0)
        assertTrue(
            "a brief excursion is a sub-flow, not a switch",
            manager.isGranted("com.example.alpha")
        )
        manager.onForegroundSignal(
            ForegroundSignal.AppWindow("com.example.beta"),
            SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS
        )

        assertNull(manager.lastPackage)
        assertNull(manager.lastFeature)
        assertFalse(manager.isGranted("com.example.alpha"))
    }

    @Test
    fun `package passthrough prevents foreground re-evaluation`() {
        manager.grant("com.example.alpha")

        assertTrue(manager.shouldSkipForegroundEvaluation("com.example.alpha"))
        assertFalse(manager.shouldSkipForegroundEvaluation("com.example.beta"))
    }

    // --- ISSUE #54: a completed hold survives the screen timing out ------------------------------

    /**
     * The reported flow, replayed as the service runs it.
     *
     * A Nudge user, by email 2026-09-24, on the HOLD mode he asked for in #35: *"I hold 60 second to
     * unlock and then after 3-5 minute of use it again Askin for hold and also even after just 1
     * minute ahead asking for hold to unlock ... I have some client info on it and chat with them"*.
     *
     * Phrased in [PassthroughManager.shouldSkipForegroundEvaluation] because that is literally the
     * early return in `evaluateForegroundPackage` that stands between the user and a second hold: it
     * is false here iff the overlay comes back. Every signal is CLASSIFIED from an event rather than
     * hand-built, so the keyguard has to genuinely land as a `SystemSurface` and the app's return as
     * a genuine `AppWindow` for this to pass.
     *
     * The screen-off is injected directly because `ACTION_SCREEN_OFF` is a broadcast and never
     * enters the accessibility stream at all — which is also why no captured event log could ever
     * have contained this bug.
     */
    @Test
    fun `a completed hold survives a display timeout the user comes straight back from`() {
        val reddit = "com.reddit.frontpage"
        val keyguard = "com.android.systemui"

        // The user arrives, meets the 60s HOLD, completes it. `onTimerComplete` grants.
        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 0)
        manager.grant(reddit)
        assertTrue(manager.shouldSkipForegroundEvaluation(reddit))

        // They read a client chat for 40 seconds without touching anything. The display times out.
        manager.onScreenOff(40_000)

        // They tap the screen, pass the keyguard, and are back where they were.
        manager.onForegroundSignal(signalFor(keyguard, A11yEventType.WINDOW_STATE_CHANGED), 42_000)
        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 42_500)

        assertTrue(
            "a 40-second display timeout is not a departure; the hold must not be charged again",
            manager.shouldSkipForegroundEvaluation(reddit)
        )
    }

    /**
     * **The counterfactual, on the same stream.** Backlog F5 is the reason the screen-off end cause
     * exists: *complete Instagram's delay, lock the phone, unlock hours later straight back into
     * Instagram, no delay.* Only the duration differs from the test above, and the answer flips — so
     * the fix cannot have been "stop revoking on screen-off", which is the way it would most
     * plausibly have been got wrong.
     */
    @Test
    fun `a phone locked past the return window still costs a fresh block`() {
        val reddit = "com.reddit.frontpage"

        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 0)
        manager.grant(reddit)

        manager.onScreenOff(40_000)
        manager.onForegroundSignal(
            signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED),
            40_000 + SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS
        )

        assertFalse(
            "F5: a sitting cannot span a genuinely locked phone",
            manager.shouldSkipForegroundEvaluation(reddit)
        )
        assertNull(manager.lastPackage)
    }

    /**
     * Unlocking to the launcher instead of back into the app is a real departure and still revokes.
     * Home is the one gesture that unambiguously means "I am leaving"; #54's fix is that a
     * screen-off is not that gesture, and it must not have made Home into one either.
     */
    @Test
    fun `unlocking to the launcher after a screen-off still revokes the grant`() {
        val reddit = "com.reddit.frontpage"
        val launcher = "com.google.android.apps.nexuslauncher"

        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 0)
        manager.grant(reddit)

        manager.onScreenOff(5_000)
        manager.onForegroundSignal(
            signalFor(
                launcher,
                A11yEventType.WINDOW_STATE_CHANGED,
                launcherPackages = setOf(launcher)
            ),
            6_000
        )

        assertFalse(manager.shouldSkipForegroundEvaluation(reddit))
        assertNull(manager.lastPackage)
    }

    /**
     * DEVICE-OBSERVED REGRESSION, reproduced at the model layer: complete a delay, press Home, come
     * straight back, and the block must be there. No screen-off anywhere in this stream.
     */
    @Test
    fun `going home revokes a completed grant with no screen-off involved`() {
        val reddit = "com.reddit.frontpage"
        val launcher = "com.google.android.apps.nexuslauncher"

        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 0)
        manager.grant(reddit)
        assertTrue(manager.shouldSkipForegroundEvaluation(reddit))

        manager.onForegroundSignal(
            signalFor(launcher, A11yEventType.WINDOW_STATE_CHANGED, launcherPackages = setOf(launcher)),
            2_000
        )
        assertFalse("Home must revoke at once", manager.shouldSkipForegroundEvaluation(reddit))

        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 4_000)
        assertFalse(
            "coming straight back from Home must still be blocked",
            manager.shouldSkipForegroundEvaluation(reddit)
        )
    }

    /**
     * The full device sequence a slow QA run actually produces, with the display timeout in it.
     *
     * A bench run of "complete the delay, press Home, reopen" failed on this change and looked like
     * a Home regression. It was not reproducible at this layer, and the reason is in this stream:
     * an agent driving the phone takes tens of seconds per step while the Pixel blanks the display
     * after thirty, so the screen times out BETWEEN getting inside the app and pressing Home. Before
     * #54 that timeout revoked the grant, and the block the run then saw was credited to Home
     * without Home having done anything. After #54 it correctly does not.
     *
     * So this pins the thing that actually matters and that the bench run could not see: with a
     * display timeout in the middle, a REAL Home still revokes, and the app is still blocked on
     * return. If a future change breaks Home behind a screen-off, this fails here rather than
     * costing another device round.
     */
    @Test
    fun `a display timeout before going home does not save the grant from home`() {
        val reddit = "com.reddit.frontpage"
        val launcher = "com.google.android.apps.nexuslauncher"

        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 0)
        manager.grant(reddit)

        // The display times out while the user sits there, then they touch it and are back.
        manager.onScreenOff(30_000)
        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 35_000)
        assertTrue(
            "the timeout itself is not a departure; that is the whole of #54",
            manager.shouldSkipForegroundEvaluation(reddit)
        )

        // NOW they actually go home, screen on, deliberately.
        manager.onForegroundSignal(
            signalFor(launcher, A11yEventType.WINDOW_STATE_CHANGED, launcherPackages = setOf(launcher)),
            40_000
        )
        assertFalse("Home is still a departure, timeout or no timeout", manager.shouldSkipForegroundEvaluation(reddit))

        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 42_000)
        assertFalse(
            "and coming back from Home still costs a fresh block",
            manager.shouldSkipForegroundEvaluation(reddit)
        )
    }

    /**
     * ISSUE [#64](https://github.com/astraedus/nudge/issues/64) at the GRANT level: the hold the user
     * paid for must survive a sub-flow they came back from, even when the return never arrives as a
     * window event.
     *
     * Reported by email 2026-09-25 — *"the hold requirement itself is being triggered again while I
     * am still actively using the app and my phone screen has remained ON the entire time"* — across
     * Reddit, Gemini and Files. Reddit opens links in a Chrome Custom Tab, which arms the away clock
     * by design (issue #28 allows that, because the RETURN is supposed to cancel it). [#58](https://github.com/astraedus/nudge/issues/58)
     * measured on the bench Pixel 3 that the same gesture on the same build sometimes delivers only
     * a content change and no `WINDOW_STATE_CHANGED`, so the return is modelled here as arriving
     * with no window event at all — which is precisely the case the old model had no answer for.
     *
     * Phrased in [PassthroughManager.shouldSkipForegroundEvaluation] for the same reason the #54
     * test is: it is literally the early return in `evaluateForegroundPackage` standing between this
     * user and a second sixty-second hold.
     */
    @Test
    fun `a completed hold survives a sub-flow when only the scrolling proves the user came back`() {
        val reddit = "com.reddit.frontpage"
        val customTab = "com.android.chrome"

        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 0)
        manager.grant(reddit)

        // They tap a link. The Custom Tab is another app in front, so the away clock starts.
        manager.onForegroundSignal(signalFor(customTab, A11yEventType.WINDOW_STATE_CHANGED), 20_000)

        // They close it and carry on scrolling Reddit — and the return arrives only as interaction,
        // never as a window event (#58's measured delivery flakiness).
        manager.onInteraction(reddit, 40_000)
        manager.onInteraction(reddit, 55_000)

        // Six minutes later they open a post, which IS a window event.
        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 400_000)

        assertTrue(
            "they never left; opening a post must not cost the sixty-second hold again",
            manager.shouldSkipForegroundEvaluation(reddit)
        )
    }

    /**
     * **The counterfactual, on the same stream minus the scrolling.** This is the bug as shipped: with
     * no interaction to close the absence, the away clock armed by the Custom Tab is still running
     * six minutes later and the post tap is read as a return from a long departure. It also proves
     * the test above is not passing because the sub-flow quietly stopped arming the clock.
     */
    @Test
    fun `without that evidence the same sub-flow revokes the hold six minutes later`() {
        val reddit = "com.reddit.frontpage"
        val customTab = "com.android.chrome"

        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 0)
        manager.grant(reddit)
        manager.onForegroundSignal(signalFor(customTab, A11yEventType.WINDOW_STATE_CHANGED), 20_000)
        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 400_000)

        assertFalse(
            "an absence nobody contradicted is still an absence",
            manager.shouldSkipForegroundEvaluation(reddit)
        )
    }

    /**
     * The bypass direction, at the grant level. A stray interaction event from an app the user left
     * long ago must not resurrect their grant — which is why an interaction runs the same return
     * branch a window event runs instead of merely cancelling the clock.
     */
    @Test
    fun `an interaction arriving after a real departure revokes rather than rescues`() {
        val reddit = "com.reddit.frontpage"

        manager.onForegroundSignal(signalFor(reddit, A11yEventType.WINDOW_STATE_CHANGED), 0)
        manager.grant(reddit)
        manager.onForegroundSignal(signalFor("com.whatsapp", A11yEventType.WINDOW_STATE_CHANGED), 10_000)

        manager.onInteraction(reddit, 10_000 + SittingTracker.PASSTHROUGH_RETURN_WINDOW_MS + 1)

        assertFalse(
            "ten minutes in another app is a departure however the evidence of return arrives",
            manager.shouldSkipForegroundEvaluation(reddit)
        )
    }

    /** A screen-off with nothing granted and no sitting must stay a no-op. */
    @Test
    fun `a screen-off with no sitting changes nothing`() {
        assertNull(manager.lastPackage)
        manager.onScreenOff(1_000)
        assertNull(manager.lastPackage)
    }

    @Test
    fun `whole-app passthrough does not skip feature evaluation`() {
        manager.grant("com.example.alpha")

        assertFalse(
            manager.shouldSkipFeatureEvaluation(
                "com.example.alpha",
                "REELS"
            )
        )
    }

    @Test
    fun `feature passthrough skips the matching feature only`() {
        manager.grant("com.example.alpha", "REELS")

        assertTrue(
            manager.shouldSkipFeatureEvaluation(
                "com.example.alpha",
                "REELS"
            )
        )
        assertFalse(
            manager.shouldSkipFeatureEvaluation(
                "com.example.alpha",
                "EXPLORE"
            )
        )
    }

    @Test
    fun `own package overlay widget events do not clear counter state`() {
        assertFalse(
            NudgeAccessibilityService.shouldClearForOwnPackageEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = "android.widget.TextView",
                ownClassNamespace = NudgeIdentity.CLASS_NAMESPACE
            )
        )
    }

    @Test
    fun `own package app window events clear counter state`() {
        assertTrue(
            NudgeAccessibilityService.shouldClearForOwnPackageEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = "com.astraedus.nudge.MainActivity",
                ownClassNamespace = NudgeIdentity.CLASS_NAMESPACE
            )
        )
    }

    @Test
    fun `own package non-window events do not clear counter state`() {
        assertFalse(
            NudgeAccessibilityService.shouldClearForOwnPackageEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                className = "com.astraedus.nudge.MainActivity",
                ownClassNamespace = NudgeIdentity.CLASS_NAMESPACE
            )
        )
    }

    // --- Overlay-bypass detection: tabbing out of a blocked app and back in must re-block. ---

    @Test
    fun `blocked app returning to foreground while overlay flag set is treated as bypass`() {
        // The user tabbed out and re-opened the blocked app; its task comes forward directly,
        // orphaning the overlay. A real foreground switch must clear the stale flag so we re-block.
        assertTrue(
            BlockLaunchGate.isGenuineBypass(
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = signalFor("com.instagram.android", A11yEventType.WINDOW_STATE_CHANGED),
                pending = null,
                nowMs = 0L
            )
        )
    }

    @Test
    fun `own package window event does not count as overlay bypass`() {
        // The overlay's own window appearing is not a bypass — keep swallowing it. The exclusion
        // now lives in the classifier (OwnUi, not AppWindow) rather than in an ownPackageName
        // comparison inside the gate, so this asserts the whole path, not a local `if`.
        assertFalse(
            BlockLaunchGate.isGenuineBypass(
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = signalFor(OWN_PACKAGE, A11yEventType.WINDOW_STATE_CHANGED),
                pending = null,
                nowMs = 0L
            )
        )
    }

    @Test
    fun `system window while overlay up does not count as overlay bypass`() {
        // Launcher / systemui surfacing over the overlay is not the user re-entering the app.
        assertFalse(
            BlockLaunchGate.isGenuineBypass(
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = signalFor("com.android.systemui", A11yEventType.WINDOW_STATE_CHANGED),
                pending = null,
                nowMs = 0L
            )
        )
    }

    @Test
    fun `content change churn under a live overlay does not count as overlay bypass`() {
        // The blocked app animating/loading underneath a genuinely-live overlay must NOT clear the
        // flag — only a real foreground switch (WINDOW_STATE_CHANGED) does.
        assertFalse(
            BlockLaunchGate.isGenuineBypass(
                eventType = A11yEventType.WINDOW_CONTENT_CHANGED,
                signal = signalFor("com.instagram.android", A11yEventType.WINDOW_CONTENT_CHANGED),
                pending = null,
                nowMs = 0L
            )
        )
    }

    private companion object {
        /**
         * The REAL applicationId. It used to be the literal "com.astraedus.nudge", which is
         * the NAMESPACE -- and that is precisely why the own-window tests below passed for
         * months against a predicate that could never fire in production (issue #33).
         */
        val OWN_PACKAGE: String = NudgeIdentity.APPLICATION_ID
    }
}
