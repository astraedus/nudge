package com.astraedus.nudge.service

import com.astraedus.nudge.NudgeIdentity
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for issue #5: keyboards and paste/long-press popups re-triggering the block.
 *
 * The bug: after completing a delay (passthrough granted for app X), a soft keyboard NOT on the
 * hardcoded list (e.g. FUTO), or the `android` framework package that hosts the paste / long-press
 * popup toolbar, surfaced a different package on a window event. Routing that into evaluation
 * cleared X's passthrough, so tapping back into X re-triggered the delay. The fix recognises those
 * windows as transient and ignores their events, keeping the passthrough intact.
 */
class TransientWindowTest {

    private val futo = "org.futo.inputmethod.latin"
    private val gboard = "com.google.android.inputmethod.latin"
    private val instagram = "com.instagram.android"
    /** The REAL applicationId, never the namespace: see [NudgeIdentity]. */
    private val ownPackage = NudgeIdentity.APPLICATION_ID

    /**
     * The real classifier, wired from the service's own package sets.
     *
     * The transient exclusion the overlay-bypass gate relies on is no longer inside that gate — it
     * is one branch of [EventClassifier], which answers [ForegroundSignal.Transient] for an active
     * IME or the framework package. These tests therefore have to CLASSIFY, not hand-build a
     * signal: a hand-built `ForegroundSignal.Transient(futo)` would pass while the real pipeline
     * regressed straight back into issue #5.
     */
    private val classifier = EventClassifier(
        ownPackageName = ownPackage,
        systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
        imePackages = { NudgeAccessibilityService.IME_PACKAGES },
        frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES,
        windows = { null }
    )

    private fun signalFor(
        packageName: String,
        type: A11yEventType,
        currentImePackage: String? = null
    ): ForegroundSignal = classifier.classify(
        AccessibilityEventRecord(type = type, packageName = packageName),
        currentImePackage = currentImePackage,
        launcherPackages = emptySet(),
        pipOnlyPackages = emptySet()
    )

    // --- the recognition the fix hinges on, asked of the PRODUCTION classifier ---
    //
    // These four used to call `NudgeAccessibilityService.isTransientNonAppPackage`, a companion COPY
    // of the classifier's rule that production never called. A test of a copy stays green while
    // the real rule drifts, so the copy was deleted (issue #76) and the tests now classify.

    private fun isTransient(packageName: String, currentImePackage: String?): Boolean =
        signalFor(packageName, A11yEventType.WINDOW_STATE_CHANGED, currentImePackage) is
            ForegroundSignal.Transient

    @Test
    fun `the reporter's third-party keyboard (FUTO) is recognised as transient when it is the active IME`() {
        // This is the exact issue #5 scenario: FUTO is not on any hardcoded list, but matching the
        // active default IME dynamically catches it, so its window events no longer clear passthrough.
        assertTrue(isTransient(futo, currentImePackage = futo))
    }

    @Test
    fun `a hardcoded keyboard is transient even without a dynamic match`() {
        assertTrue(isTransient(gboard, currentImePackage = null))
    }

    @Test
    fun `the Samsung keyboard on One UI (honeyboard) is in the static floor`() {
        // Issue #76 was a Samsung S24 Ultra; the old floor only had the pre-One-UI package.
        assertTrue(isTransient("com.samsung.android.honeyboard", currentImePackage = null))
    }

    @Test
    fun `every ENABLED keyboard is transient, not only the default`() {
        // Issue #76: the classifier reads the live enabled set from the service. A keyboard that is
        // enabled but not the default (picked from the IME switcher, or an OEM keyboard brought up
        // for one field) must not read as the user leaving the app.
        val swiftKey = "com.touchtype.swiftkey"
        val live = EventClassifier(
            ownPackageName = ownPackage,
            systemPackages = NudgeAccessibilityService.SYSTEM_PACKAGES,
            imePackages = { NudgeAccessibilityService.IME_PACKAGES + swiftKey },
            frameworkPackage = NudgeAccessibilityService.FRAMEWORK_PACKAGE,
            awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES,
            windows = { null }
        )
        val signal = live.classify(
            AccessibilityEventRecord(type = A11yEventType.WINDOW_STATE_CHANGED, packageName = swiftKey),
            currentImePackage = gboard,
            launcherPackages = emptySet(),
            pipOnlyPackages = emptySet()
        )
        assertTrue(signal is ForegroundSignal.Transient)
    }

    @Test
    fun `the android framework package (paste and long-press popups) is transient`() {
        // Long-press link menus and the text-selection / paste floating toolbar are hosted by the
        // `android` package — the second symptom the reporter found.
        assertTrue(isTransient(NudgeAccessibilityService.FRAMEWORK_PACKAGE, currentImePackage = futo))
    }

    @Test
    fun `a real app is never transient`() {
        assertFalse(isTransient(instagram, currentImePackage = futo))
        assertFalse(isTransient(instagram, currentImePackage = null))
    }

    // --- isOverlayBypassedByForeground: a transient window must not clear an active block overlay ---

    @Test
    fun `active keyboard while overlay up is not an overlay bypass`() {
        assertFalse(
            BlockLaunchGate.isGenuineBypass(
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = signalFor(futo, A11yEventType.WINDOW_STATE_CHANGED, currentImePackage = futo),
                pending = null,
                nowMs = 0L
            )
        )
    }

    @Test
    fun `android popup while overlay up is not an overlay bypass`() {
        assertFalse(
            BlockLaunchGate.isGenuineBypass(
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = signalFor(
                    NudgeAccessibilityService.FRAMEWORK_PACKAGE,
                    A11yEventType.WINDOW_STATE_CHANGED,
                    currentImePackage = futo
                ),
                pending = null,
                nowMs = 0L
            )
        )
    }

    @Test
    fun `a real app returning to foreground is still a bypass (regression guard)`() {
        // The transient exclusions must not weaken the genuine tab-out-and-back-in re-block path.
        assertTrue(
            BlockLaunchGate.isGenuineBypass(
                eventType = A11yEventType.WINDOW_STATE_CHANGED,
                signal = signalFor(
                    instagram,
                    A11yEventType.WINDOW_STATE_CHANGED,
                    currentImePackage = futo
                ),
                pending = null,
                nowMs = 0L
            )
        )
    }
}
