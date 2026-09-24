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
