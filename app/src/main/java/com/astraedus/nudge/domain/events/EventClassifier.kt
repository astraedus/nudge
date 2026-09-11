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
 */
class EventClassifier(
    private val ownPackageName: String,
    private val systemPackages: Set<String>,
    private val imePackages: Set<String>,
    private val frameworkPackage: String
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

        // Ahead of everything, including our own package: a PiP bubble fires events for an app the
        // user is not looking at, and issue #19's whole lesson is that this gate must be general
        // rather than attached to one branch.
        if (pkg in pipOnlyPackages) return ForegroundSignal.PipOnly(pkg)

        if (pkg == ownPackageName) return ForegroundSignal.OwnUi(pkg)

        if (isTransient(pkg, currentImePackage)) return ForegroundSignal.Transient(pkg)

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
        if (pkg in pipOnlyPackages) return ForegroundSignal.PipOnly(pkg)
        if (pkg == ownPackageName) return ForegroundSignal.OwnUi(pkg)
        if (isTransient(pkg, currentImePackage)) return ForegroundSignal.Transient(pkg)
        if (pkg in systemPackages) return ForegroundSignal.SystemSurface(pkg)
        return ForegroundSignal.AppWindow(pkg)
    }

    private fun isTransient(packageName: String, currentImePackage: String?): Boolean =
        packageName == frameworkPackage ||
            packageName in imePackages ||
            (currentImePackage != null && packageName == currentImePackage)
}
