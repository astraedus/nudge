package com.astraedus.nudge.domain.events

import com.astraedus.nudge.service.AwarenessOverlayWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single place that answers "what is on screen?", and therefore the single place where issue
 * #28 bug 1 could be reintroduced.
 *
 * The pre-fix service answered that question implicitly, in six branches, off three hardcoded
 * package sets — so *any* foreign package on a window event read as "the user left the app", and a
 * photo picker, a share sheet or a permission dialog revoked a completed delay's passthrough. These
 * tests pin the two properties that make that unexpressible:
 *
 *  1. every event becomes exactly one of a CLOSED set of signals, and
 *  2. only [ForegroundSignal.AppWindow] and [ForegroundSignal.Home] can ever move a sitting
 *     (see [com.astraedus.nudge.domain.sitting.SittingTracker] and `SittingTrackerTest`), so a
 *     package nobody has heard of is harmless by construction rather than by list membership.
 */
class EventClassifierTest {

    private val ownPackage = "dev.astraedus.nudge"
    private val framework = "android"

    /**
     * Mirrors the sets `NudgeAccessibilityService` passes in (`SYSTEM_PACKAGES`, `IME_PACKAGES`,
     * `FRAMEWORK_PACKAGE`). Held locally on purpose: the classifier takes them as parameters so
     * that its logic is testable as pure Kotlin, with no `android.*` anywhere on this test's path.
     */
    private val systemPackages = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.android.permissioncontroller"
    )
    private val imePackages = setOf(
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.sec.android.inputmethod"
    )

    private val classifier = EventClassifier(
        ownPackageName = ownPackage,
        systemPackages = systemPackages,
        imePackages = imePackages,
        frameworkPackage = framework,
        awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES
    )

    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val gboard = "com.google.android.inputmethod.latin"

    /** Issue #5's reporter's keyboard: on no hardcoded list, caught only via the active IME. */
    private val futo = "com.futo.inputmethod.latin"

    // Real Pixel 3 sub-flow packages, captured 2026-09-11. None of them is in any set above, and
    // that is the point of the tests that use them.
    private val picker = "com.google.android.providers.media.module"
    private val shareSheet = "com.android.intentresolver"

    /** NOTE the `google`: the Pixel's dialog is NOT the `com.android.…` one in SYSTEM_PACKAGES. */
    private val pixelPermissions = "com.google.android.permissioncontroller"

    private val launcherSet = setOf(launcher)

    private fun event(
        pkg: String,
        type: A11yEventType = A11yEventType.WINDOW_STATE_CHANGED,
        className: String? = null
    ) = AccessibilityEventRecord(type = type, packageName = pkg, className = className)

    private fun classify(
        record: AccessibilityEventRecord,
        ime: String? = futo,
        launchers: Set<String> = launcherSet,
        pip: Set<String> = emptySet()
    ) = classifier.classify(record, ime, launchers, pip)

    /**
     * Exhaustive by construction: this `when` is over a sealed interface, so adding a
     * [ForegroundSignal] subtype breaks THIS compile. That is the only mechanism by which a test
     * can notice a signal type nobody produces — a value-level assertion cannot.
     */
    private fun nameOf(signal: ForegroundSignal): String = when (signal) {
        is ForegroundSignal.AppWindow -> "AppWindow"
        is ForegroundSignal.Home -> "Home"
        is ForegroundSignal.SystemSurface -> "SystemSurface"
        is ForegroundSignal.OwnUi -> "OwnUi"
        is ForegroundSignal.AwarenessOverlay -> "AwarenessOverlay"
        is ForegroundSignal.Transient -> "Transient"
        is ForegroundSignal.PipOnly -> "PipOnly"
        is ForegroundSignal.NotForeground -> "NotForeground"
    }

    @Test
    fun `every ForegroundSignal type is produced by some input`() {
        val produced = listOf(
            classify(event(instagram)),
            classify(event(launcher)),
            classify(event("com.android.systemui")),
            classify(event(ownPackage)),
            classify(event(framework)),
            classify(event(youtube), pip = setOf(youtube)),
            classify(event(instagram, A11yEventType.VIEW_SCROLLED)),
            classify(event(ownPackage, className = AwarenessOverlayWindow.CLASS_NAME))
        ).mapTo(mutableSetOf()) { nameOf(it) }

        assertEquals(
            setOf(
                "AppWindow", "Home", "SystemSurface", "OwnUi", "AwarenessOverlay",
                "Transient", "PipOnly", "NotForeground"
            ),
            produced
        )
    }

    // --- the PiP gate, which sits ahead of everything ------------------------------------------

    /**
     * Issue #19's lesson, generalised: a PiP bubble fires events for an app the user is not looking
     * at, and the previous fix attached that check to ONE branch, so every other path still read
     * the bubble as a foreground app. Here the gate is first, so nothing — not our own package, not
     * a system package, not the launcher — can outrank it.
     */
    @Test
    fun `a PiP-only package is PipOnly ahead of every other rule`() {
        val pip = setOf(youtube, ownPackage, "com.android.systemui", launcher)
        listOf(youtube, ownPackage, "com.android.systemui", launcher).forEach { pkg ->
            assertEquals(
                "$pkg is only a PiP bubble; nothing may reclassify it",
                ForegroundSignal.PipOnly(pkg),
                classify(event(pkg), pip = pip)
            )
        }
    }

    // --- our own UI, keyboards, and the framework package ---------------------------------------

    @Test
    fun `our own package is OwnUi`() {
        assertEquals(ForegroundSignal.OwnUi(ownPackage), classify(event(ownPackage)))
    }

    /**
     * Issue #41. An awareness overlay carries OUR package and means the opposite of `OwnUi` about
     * where the user is: it is a view drawn OVER the app they never left, so it makes no claim
     * about the foreground at all. Told apart POSITIVELY, by the class name the overlay's views
     * report, because our package emits several window shapes and only this one is an overlay.
     */
    @Test
    fun `our awareness overlay is AwarenessOverlay, not OwnUi`() {
        assertEquals(
            ForegroundSignal.AwarenessOverlay(ownPackage),
            classify(event(ownPackage, className = AwarenessOverlayWindow.CLASS_NAME))
        )
    }

    /**
     * The counter overlay updates its text far more often than it appears, and a text change is a
     * `WINDOW_CONTENT_CHANGED`. Those reach the classifier ahead of the window-change test, so if
     * they were still `OwnUi` the foreground would move to Nudge on every single interaction and
     * #41 would be half-fixed.
     */
    @Test
    fun `an awareness overlay content change is AwarenessOverlay too`() {
        assertEquals(
            ForegroundSignal.AwarenessOverlay(ownPackage),
            classify(
                event(
                    ownPackage,
                    A11yEventType.WINDOW_CONTENT_CHANGED,
                    className = AwarenessOverlayWindow.CLASS_NAME
                )
            )
        )
    }

    /**
     * Every OTHER window of ours stays `OwnUi`, which is what moves the foreground to Nudge. The
     * framework class name in this list is the block overlay TASK's first window, timed on a Pixel
     * 3 ~600ms ahead of the overlay itself — the exact window a negative "Nudge and not the
     * overlay" test would misfile.
     */
    @Test
    fun `every other window of ours is still OwnUi`() {
        listOf(
            null,
            "android.widget.FrameLayout",
            "com.astraedus.nudge.MainActivity",
            "com.astraedus.nudge.ui.overlay.BlockOverlayActivity",
            AwarenessOverlayWindow.CLASS_NAME + "Extra"
        ).forEach { className ->
            assertEquals(
                "a Nudge window named $className is not an awareness overlay",
                ForegroundSignal.OwnUi(ownPackage),
                classify(event(ownPackage, className = className))
            )
        }
    }

    /** A PiP bubble outranks even our own package, and that ordering holds for the overlay too. */
    @Test
    fun `an awareness overlay class on a PiP-only package is still PipOnly`() {
        assertEquals(
            ForegroundSignal.PipOnly(ownPackage),
            classify(
                event(ownPackage, className = AwarenessOverlayWindow.CLASS_NAME),
                pip = setOf(ownPackage)
            )
        )
    }

    /** Another app cannot claim our identity: the package is tested first. */
    @Test
    fun `another app reporting our overlay class name is still just an app`() {
        assertEquals(
            ForegroundSignal.AppWindow(instagram),
            classify(event(instagram, className = AwarenessOverlayWindow.CLASS_NAME))
        )
    }

    /** The `android` package hosts toasts, long-press menus and the paste toolbar — issue #5. */
    @Test
    fun `the framework package is Transient`() {
        assertEquals(ForegroundSignal.Transient(framework), classify(event(framework)))
    }

    @Test
    fun `a hardcoded keyboard is Transient even when it is not the active IME`() {
        assertEquals(ForegroundSignal.Transient(gboard), classify(event(gboard), ime = null))
    }

    /**
     * The exact issue #5 regression: FUTO is on no hardcoded list. It is recognised only because
     * the active IME is read from `Settings.Secure.DEFAULT_INPUT_METHOD` and passed in, which is
     * what makes EVERY keyboard covered instead of the three we happened to know about.
     */
    @Test
    fun `a third-party keyboard matched only via the active IME is Transient`() {
        assertTrue(futo !in imePackages)
        assertEquals(ForegroundSignal.Transient(futo), classify(event(futo), ime = futo))
    }

    /**
     * A null active IME means "we could not read it", and must degrade to the static list — not to
     * "everything is a keyboard" (which would ignore every app) and not to a crash.
     */
    @Test
    fun `a null active IME neither crashes nor makes everything Transient`() {
        assertEquals(ForegroundSignal.AppWindow(instagram), classify(event(instagram), ime = null))
        assertEquals(ForegroundSignal.Transient(framework), classify(event(framework), ime = null))
        assertEquals(ForegroundSignal.Transient(gboard), classify(event(gboard), ime = null))
    }

    // --- what is actually in front ---------------------------------------------------------------

    @Test
    fun `a real app on a window state change is AppWindow`() {
        assertEquals(ForegroundSignal.AppWindow(instagram), classify(event(instagram)))
    }

    @Test
    fun `the launcher on a window state change is Home`() {
        assertEquals(ForegroundSignal.Home(launcher), classify(event(launcher)))
    }

    /**
     * Launcher content churn — widgets ticking, the icon grid redrawing behind a fullscreen app —
     * is not evidence that anything came forward. Treating it as Home would end a sitting for a
     * user who never left, which is the expensive direction of this error.
     */
    @Test
    fun `the launcher on a content change is NotForeground and not Home`() {
        assertEquals(
            ForegroundSignal.NotForeground(launcher),
            classify(event(launcher, A11yEventType.WINDOW_CONTENT_CHANGED))
        )
    }

    /**
     * Only `TYPE_WINDOW_STATE_CHANGED` means "a new activity is in front", so a WINDOWS_CHANGED
     * from the launcher falls through to the ordinary rules and lands on
     * [ForegroundSignal.SystemSurface] (the stock launcher is in `SYSTEM_PACKAGES`). Inert either
     * way — the failure direction stays "miss a revoke", never "revoke falsely".
     */
    @Test
    fun `the launcher on a windows-changed event is not Home`() {
        val signal = classify(event(launcher, A11yEventType.WINDOWS_CHANGED))
        assertEquals(ForegroundSignal.SystemSurface(launcher), signal)
    }

    /**
     * An empty launcher set means PackageManager could not tell us. Nothing is Home, so nothing is
     * revoked: the documented failure direction.
     */
    @Test
    fun `an empty launcher set classifies nothing as Home`() {
        val signal = classify(event(launcher), launchers = emptySet())
        assertTrue("with no launcher set, no event may be Home", signal !is ForegroundSignal.Home)
        assertEquals(ForegroundSignal.SystemSurface(launcher), signal)
    }

    @Test
    fun `a system package that is not the launcher is SystemSurface`() {
        assertEquals(
            ForegroundSignal.SystemSurface("com.android.systemui"),
            classify(event("com.android.systemui"))
        )
        assertEquals(
            ForegroundSignal.SystemSurface("com.android.settings"),
            classify(event("com.android.settings"))
        )
    }

    /**
     * The classifier does NOT sanitise the launcher set — `com.android.settings` declares
     * `CATEGORY_HOME` via AOSP's `Settings$FallbackHome` and is stripped upstream, by
     * `NudgeAccessibilityService.sanitizeLauncherPackages`, which has its own tests. What this
     * class guarantees is narrower and worth pinning: whatever it is given is honoured exactly, so
     * the sanitisation has a single owner instead of two half-owners.
     */
    @Test
    fun `whatever is in launcherPackages is honoured verbatim`() {
        val thirdParty = "com.some.third.party.launcher"
        assertEquals(
            ForegroundSignal.Home(thirdParty),
            classify(event(thirdParty), launchers = setOf(thirdParty))
        )
    }

    /** A scroll, a click or a content change arrives from whatever is already there. */
    @Test
    fun `non-window events from a real app are NotForeground`() {
        listOf(
            A11yEventType.VIEW_SCROLLED,
            A11yEventType.VIEW_CLICKED,
            A11yEventType.WINDOW_CONTENT_CHANGED,
            A11yEventType.OTHER
        ).forEach { type ->
            assertEquals(
                "$type makes no claim about what is in front",
                ForegroundSignal.NotForeground(instagram),
                classify(event(instagram, type))
            )
        }
    }

    // --- issue #28's sub-flows -------------------------------------------------------------------

    /**
     * The picker, the share sheet and the Pixel's permission dialog all come back as ordinary
     * [ForegroundSignal.AppWindow]s — the classifier deliberately does NOT pretend to recognise
     * them, because the last three attempts to fix this bug were another entry in another
     * `setOf(...)` and each list sprang again on the next device.
     *
     * That is now SAFE because `SittingTracker` is what decides whether the user left, and it does
     * not end a sitting for a brief excursion no matter which package it carries. Adding these to a
     * list here would re-create the grouped-constant trap rather than remove it.
     */
    @Test
    fun `sub-flow packages are ordinary app windows rather than special cases`() {
        listOf(picker, shareSheet, pixelPermissions).forEach { pkg ->
            assertTrue("$pkg must not be on any hardcoded set", pkg !in systemPackages)
            assertEquals(ForegroundSignal.AppWindow(pkg), classify(event(pkg)))
        }
    }

    // --- issue #7's verified content change ------------------------------------------------------

    /**
     * Re-entering an app via recents or a notification sometimes delivers ONLY a content change.
     * Once the caller has verified against `rootInActiveWindow` that the package really owns the
     * active window, the event has been PROVEN to be a foreground switch, so it is promoted.
     */
    @Test
    fun `a verified content change is promoted to AppWindow`() {
        val record = event(instagram, A11yEventType.WINDOW_CONTENT_CHANGED)
        assertEquals(ForegroundSignal.NotForeground(instagram), classify(record))
        assertEquals(
            ForegroundSignal.AppWindow(instagram),
            classifier.classifyVerifiedContentChangeAsSwitch(record, futo, emptySet())
        )
    }

    /**
     * The promotion is an exception to ONE rule (the event type), not a bypass of the gates. A
     * verified content change from a PiP bubble, our own overlay, the keyboard or the shade is
     * still not a foreground app.
     */
    @Test
    fun `a verified content change still rejects PiP own UI keyboards and system packages`() {
        fun promote(pkg: String, pip: Set<String> = emptySet()) =
            classifier.classifyVerifiedContentChangeAsSwitch(
                event(pkg, A11yEventType.WINDOW_CONTENT_CHANGED), futo, pip
            )

        assertEquals(ForegroundSignal.PipOnly(youtube), promote(youtube, pip = setOf(youtube)))
        assertEquals(ForegroundSignal.OwnUi(ownPackage), promote(ownPackage))
        assertEquals(ForegroundSignal.Transient(futo), promote(futo))
        assertEquals(ForegroundSignal.Transient(gboard), promote(gboard))
        assertEquals(ForegroundSignal.Transient(framework), promote(framework))
        assertEquals(
            ForegroundSignal.SystemSurface("com.android.systemui"),
            promote("com.android.systemui")
        )
    }
}
