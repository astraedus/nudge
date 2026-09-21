package com.astraedus.nudge.domain.events

/**
 * What an accessibility event means about WHAT IS ON SCREEN — the one question the service used to
 * answer implicitly, in six places, with three hardcoded package sets.
 *
 * Every bug in `docs/architecture/foreground-detection.md` came from the same silent assumption:
 * *an event carrying package P means the user is now in P*. That is false for a keyboard (#5), for a
 * window that only delivers content changes (#7), for a picture-in-picture bubble (#19), and — the
 * one issue [#28](https://github.com/astraedus/nudge/issues/28) is about — for every sub-flow an app
 * launches: a photo picker, a share sheet, a permission dialog, an OEM volume panel. Each time, the
 * fix was another entry in another `setOf(...)`, and each list sprang again the moment a device
 * shipped a package that was not on it (the Pixel's permission dialog is
 * `com.google.android.permissioncontroller`, which is NOT the `com.android.permissioncontroller` in
 * `SYSTEM_PACKAGES`).
 *
 * Classifying once, into this closed set, replaces the assumption with a decision that is made in a
 * single pure function and is exhaustive at the compiler level. The critical property is that only
 * [AppWindow] and [Home] may move the user's *sitting* (see
 * [com.astraedus.nudge.domain.sitting.SittingTracker]); everything else is by construction incapable
 * of revoking a grant, no matter what package it carries.
 */
sealed interface ForegroundSignal {

    /** The package this signal is about, or null for signals that carry none. */
    val packageName: String?

    /**
     * A real application window is in front. The only signal that drives rule evaluation, and
     * (together with [Home]) the only one the sitting model reacts to.
     */
    data class AppWindow(override val packageName: String) : ForegroundSignal

    /**
     * The home screen / launcher. The user genuinely left whatever they were in, which is the one
     * unambiguous "this sitting is over" gesture the platform gives us.
     */
    data class Home(override val packageName: String) : ForegroundSignal

    /**
     * A system surface that is NOT the launcher: the notification shade, a permission dialog, the
     * package installer, the volume panel.
     *
     * These hide the awareness overlays and are never evaluated (no rule, no `UsageEvent`) — but
     * they must NEVER touch the sitting. That distinction is the whole reason `SYSTEM_PACKAGES`
     * membership is no longer allowed to answer "did the user leave": one membership test answering
     * two questions is the grouped-constant trap that has now sprung three times (the passthrough
     * grant, the foreground-time clock, and this issue).
     */
    data class SystemSurface(override val packageName: String) : ForegroundSignal

    /** Nudge's own UI: the block overlay, the Strict Mode guard, the PiP explainer, the app. */
    data class OwnUi(override val packageName: String) : ForegroundSignal

    /**
     * One of Nudge's AWARENESS overlays — the interaction counter, the time-remaining pill — drawn
     * over the app the user is still sitting in.
     *
     * Carries the same package as [OwnUi] and means the opposite thing about where the user is.
     * [OwnUi] is Nudge *in front of* the user; this is Nudge *on top of* whatever they were already
     * looking at, so it makes NO claim about the foreground at all and belongs with
     * [SystemSurface]/[Transient] everywhere a claim is consumed.
     *
     * That distinction is [#41](https://github.com/astraedus/nudge/issues/41): a `TYPE_ACCESSIBILITY_OVERLAY`
     * view owned by Nudge fires window and content events like any other window, `foregroundAfter`
     * moved the foreground to Nudge for every one of them, and nothing moved it back while the user
     * sat still in the blocked app — so every 30-second daily-limit tick was refused with
     * `DROP_FOREGROUND_MOVED foreground=dev.astraedus.nudge` and a user past their limit went
     * unblocked.
     *
     * It is identified POSITIVELY, by the accessibility class name the awareness overlay views
     * report (`com.astraedus.nudge.service.AwarenessOverlayWindow`), never as "Nudge and not the
     * block overlay". The same reason `BlockLaunchGate.isOwnMainAppWindow` is positive: the block
     * overlay TASK's first window arrives ~600ms early carrying the framework class
     * `android.widget.FrameLayout`, so any negative test misfiles it.
     */
    data class AwarenessOverlay(override val packageName: String) : ForegroundSignal

    /**
     * A transient, non-application window: any soft keyboard (matched dynamically, so third-party
     * keyboards are covered — issue #5) or the `android` framework package that hosts toasts, the
     * paste toolbar and long-press popups. Ignored entirely; the app underneath has not changed.
     */
    data class Transient(override val packageName: String) : ForegroundSignal

    /**
     * The package is on screen ONLY as a picture-in-picture bubble (issue #19). It fires events
     * while the user is somewhere else entirely, so it drives nothing at all.
     */
    data class PipOnly(override val packageName: String) : ForegroundSignal

    /**
     * Not a window-bearing event (a content change, a click, a scroll). Carries no claim about what
     * is in front, so the sitting model ignores it.
     *
     * The issue #7 fallback deliberately does NOT arrive here: when a content change is verified
     * against the real active window it is re-classified as a genuine [AppWindow], because that is
     * what it has been proven to be.
     */
    data class NotForeground(override val packageName: String) : ForegroundSignal
}
