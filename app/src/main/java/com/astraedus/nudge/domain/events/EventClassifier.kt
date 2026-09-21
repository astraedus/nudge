package com.astraedus.nudge.domain.events

/**
 * Turns one [AccessibilityEventRecord] into exactly one [ForegroundSignal].
 *
 * This is the single place in the app that answers "what is on screen?". It is pure, so the answer
 * can be replayed from a captured event stream in a JVM test, and it is called ONCE per event at the
 * very top of `NudgeAccessibilityService.onAccessibilityEvent` — ahead of every early return.
 *
 * **That ordering is the load-bearing part, not the logic.** Every prior bug in this subsystem was
 * an ordering bug: a branch that returned before the passthrough clear (#5, the Home path), a branch
 * that returned before evaluation (#7), a gate added below a path that needed it (#19). Classifying
 * first and unconditionally means a future early return CANNOT skip the classification — the
 * mistake is no longer available to make. `EventDispatchOrderContractTest` pins it at source level,
 * in the spirit of `HomeScreenPassthroughContractTest`, because no value-level test can see where a
 * `return` sits.
 *
 * The classifier deliberately still consults package sets, but only where a package set is the
 * honest answer to a *narrow* question:
 *  - [launcherPackages] is resolved from PackageManager, not hardcoded, and answers "is this the
 *    home screen".
 *  - [systemPackages] answers "should this be evaluated / should the awareness overlays hide" — and
 *    NOTHING else. It is no longer allowed anywhere near the question "did the user leave the app",
 *    which is what [com.astraedus.nudge.domain.sitting.SittingTracker] now owns.
 *
 * @param awarenessOverlayClassNames the accessibility class names Nudge's own awareness overlays
 *   report (`AwarenessOverlayWindow.CLASS_NAMES`). Deliberately has NO default: every construction
 *   site must decide, because a forgotten argument here would not fail — it would silently classify
 *   the counter and the time-remaining pill as [ForegroundSignal.OwnUi] again and hand issue #41
 *   straight back, with every test still green.
 */
class EventClassifier(
    private val ownPackageName: String,
    private val systemPackages: Set<String>,
    private val imePackages: Set<String>,
    private val frameworkPackage: String,
    private val awarenessOverlayClassNames: Set<String>
) {

    /**
     * @param currentImePackage the active keyboard, read from `Settings.Secure.DEFAULT_INPUT_METHOD`
     *   so EVERY keyboard is recognised and not only the handful in [imePackages] (issue #5).
     * @param launcherPackages every package that may legitimately be the home screen, resolved from
     *   PackageManager. An EMPTY set means "we could not tell", and nothing is then classified as
     *   [ForegroundSignal.Home] — the failure direction is always "miss a revoke", never "revoke
     *   falsely", because a false revoke re-blocks a user who never went anywhere.
     * @param pipOnlyPackages packages present only as a picture-in-picture window (issue #19).
     */
    fun classify(
        record: AccessibilityEventRecord,
        currentImePackage: String?,
        launcherPackages: Set<String>,
        pipOnlyPackages: Set<String>
    ): ForegroundSignal {
        val pkg = record.packageName

        notOnScreen(pkg, record.className, currentImePackage, pipOnlyPackages)?.let { return it }

        // Only a window-bearing event makes any claim about what is in front. A content change, a
        // click or a scroll arrives from whatever is already there — treating one as a foreground
        // change is exactly the ghost app-switch that would reintroduce #5.
        if (!record.type.isWindowChange) return ForegroundSignal.NotForeground(pkg)

        // Home is restricted to TYPE_WINDOW_STATE_CHANGED for the same reason the overlay-bypass
        // check is: it is the only event type meaning "a new activity is in front". Launcher
        // content-change churn (widgets, the icon grid redrawing behind a fullscreen app) is not
        // evidence anything came forward.
        if (record.type == A11yEventType.WINDOW_STATE_CHANGED && pkg in launcherPackages) {
            return ForegroundSignal.Home(pkg)
        }

        if (pkg in systemPackages) return ForegroundSignal.SystemSurface(pkg)

        return ForegroundSignal.AppWindow(pkg)
    }

    /**
     * Re-classify a content-change event that has been VERIFIED to own the real active window as the
     * genuine foreground switch it is (issue #7).
     *
     * Expressed here rather than in the service so the one place that may promote a non-window event
     * to [ForegroundSignal.AppWindow] is visible next to the rule it is an exception to. The caller
     * still owns the verification (reading `rootInActiveWindow` is a binder call); this only owns
     * what a verified event means.
     */
    fun classifyVerifiedContentChangeAsSwitch(
        record: AccessibilityEventRecord,
        currentImePackage: String?,
        pipOnlyPackages: Set<String>
    ): ForegroundSignal {
        val pkg = record.packageName
        notOnScreen(pkg, record.className, currentImePackage, pipOnlyPackages)?.let { return it }
        if (pkg in systemPackages) return ForegroundSignal.SystemSurface(pkg)
        return ForegroundSignal.AppWindow(pkg)
    }

    /**
     * The three answers that mean "this package is not the app the user is looking at", in the order
     * they must be asked, or null when none of them applies.
     *
     * Shared by both entry points deliberately. They had the same three checks written out twice,
     * and a divergence between them would be precisely the ordering bug this class exists to
     * prevent: the picture-in-picture gate in particular has to come first EVERYWHERE (issue #19's
     * lesson was that gating one branch rather than the pipeline missed the common case), and a copy
     * that drifted would reintroduce it on whichever path was edited second.
     */
    private fun notOnScreen(
        pkg: String,
        className: String?,
        currentImePackage: String?,
        pipOnlyPackages: Set<String>
    ): ForegroundSignal? = when {
        // A PiP bubble fires events for an app the user is not looking at, so this outranks even
        // our own package.
        pkg in pipOnlyPackages -> ForegroundSignal.PipOnly(pkg)
        pkg == ownPackageName -> ownUiSignal(pkg, className)
        isTransient(pkg, currentImePackage) -> ForegroundSignal.Transient(pkg)
        else -> null
    }

    /**
     * Which KIND of Nudge window this is: one the user is looking AT, or one we drew OVER the app
     * they are still in (issue #41).
     *
     * The test is positive — this exact class is an awareness overlay — and never "Nudge and not the
     * block overlay". Our own package emits four different window shapes and one of them is the
     * block overlay TASK's first window, carrying the FRAMEWORK class `android.widget.FrameLayout`
     * ~600ms before the overlay itself; a negative test files that under whichever branch it was
     * not thinking about. `BlockLaunchGate.isOwnMainAppWindow` asks its neighbouring question the
     * same way, for the same reason.
     */
    private fun ownUiSignal(pkg: String, className: String?): ForegroundSignal =
        if (className != null && className in awarenessOverlayClassNames) {
            ForegroundSignal.AwarenessOverlay(pkg)
        } else {
            ForegroundSignal.OwnUi(pkg)
        }

    private fun isTransient(packageName: String, currentImePackage: String?): Boolean =
        packageName == frameworkPackage ||
            packageName in imePackages ||
            (currentImePackage != null && packageName == currentImePackage)
}
