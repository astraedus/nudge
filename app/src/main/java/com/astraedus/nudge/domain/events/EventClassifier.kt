package com.astraedus.nudge.domain.events

import com.astraedus.nudge.domain.windows.WindowKind
import com.astraedus.nudge.domain.windows.WindowSnapshot

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
 * @param imePackages every keyboard to treat as transient RIGHT NOW: the static floor plus every
 *   ENABLED input method, read from the system by the service (issue
 *   [#76](https://github.com/astraedus/nudge/issues/76)). A provider rather than a set because the
 *   enabled list changes while the service runs. The per-event `currentImePackage` is kept as well:
 *   it is the one keyboard that is guaranteed to be current even if the enabled-list read failed.
 * @param windows the live window list ([WindowSnapshot]), or null when it is unavailable. Also
 *   deliberately has NO default: a forgotten argument would silently turn off the window evidence
 *   that #76 and #71 are fixed by. Null is a legitimate VALUE (no evidence), never an omission.
 */
class EventClassifier(
    private val ownPackageName: String,
    private val systemPackages: Set<String>,
    private val imePackages: () -> Set<String>,
    private val frameworkPackage: String,
    private val awarenessOverlayClassNames: Set<String>,
    private val windows: () -> WindowSnapshot?
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

        return appWindowUnlessWindowsDisagree(record)
    }

    /**
     * Re-classify a content-change event that has been VERIFIED to own the real active window as the
     * genuine foreground switch it is (issue #7).
     *
     * Expressed here rather than in the service so the one place that may promote a non-window event
     * to [ForegroundSignal.AppWindow] is visible next to the rule it is an exception to. The caller
     * still owns the verification (reading `rootInActiveWindow` is a binder call); this only owns
     * what a verified event means.
     *
     * ## The launcher ([#58](https://github.com/astraedus/nudge/issues/58))
     *
     * [classify] above refuses to call a launcher content change [ForegroundSignal.Home], and it is
     * right to: launcher content-change churn (widgets ticking, the icon grid redrawing behind a
     * fullscreen app) is not evidence anything came forward, and Home is one of only two signals
     * allowed to end a sitting outright. That refusal is what #5 and #28 cost.
     *
     * But the device does not always give us the other event. On a failing Pixel 3 trial the
     * launcher arrived ONLY as `WINDOW_CONTENT_CHANGED` — no `WINDOW_STATE_CHANGED` anywhere in the
     * buffer — so nothing classified as Home, the sitting never ended, the completed delay was
     * never revoked, and reopening the blocked app walked straight in. Same device, same gesture,
     * same build; the passing trials had the state change and the failing one did not.
     *
     * The hole is the same one issue #7 already has a mechanism for, one signal over. This entry
     * point exists precisely because a content change that PROVABLY owns the active window is no
     * longer churn — it is the window it claims to be. So the launcher gets the same treatment on
     * the same evidence: verified, it is Home; unverified, it is nothing, exactly as before. A
     * widget ticking behind a fullscreen app cannot pass the verification, because the app owns the
     * active window, which is what keeps this from being the blanket "a launcher event means home"
     * that [classify] forbids.
     *
     * **The launcher is asked about BEFORE [systemPackages], and that order is load-bearing.** The
     * stock launchers are IN that set (`com.android.launcher3`,
     * `com.google.android.apps.nexuslauncher`, `com.samsung.android.launcher`), which is precisely
     * what the pre-fix behaviour was: a launcher content change came back a
     * [ForegroundSignal.SystemSurface], the caller dropped it before it ever read the active
     * window, and the sitting was never told. Asked in the other order this fix would be dead code
     * on every Pixel — one membership test answering two questions, the grouped-constant trap
     * `docs/architecture/foreground-detection.md` records three earlier sprints of.
     *
     * @param launcherPackages the home-screen packages, resolved from PackageManager. Empty means
     *   "we could not tell", and then nothing here is Home either — the same fail direction
     *   [classify] documents, because a false revoke re-blocks a user who never went anywhere.
     */
    fun classifyVerifiedContentChangeAsSwitch(
        record: AccessibilityEventRecord,
        currentImePackage: String?,
        launcherPackages: Set<String>,
        pipOnlyPackages: Set<String>
    ): ForegroundSignal {
        val pkg = record.packageName
        notOnScreen(pkg, record.className, currentImePackage, pipOnlyPackages)?.let { return it }
        if (pkg in launcherPackages) return ForegroundSignal.Home(pkg)
        if (pkg in systemPackages) return ForegroundSignal.SystemSurface(pkg)
        return appWindowUnlessWindowsDisagree(record)
    }

    /**
     * [ForegroundSignal.AppWindow], unless the WINDOW LIST positively shows that this event did not
     * come from the app the user is in, in which case [ForegroundSignal.Transient].
     *
     * ## Why the package was never enough ([#76](https://github.com/astraedus/nudge/issues/76))
     *
     * Every answer above is about the event's PACKAGE. On a Samsung S24 Ultra with SwiftKey,
     * opening the keyboard in an Instagram DM and closing it again re-blocked Instagram, and doing
     * it repeatedly ran into the auto-kick cooldown. The keyboard itself was already transient.
     * What was not: a window from ANOTHER package appearing with the keyboard (inline autofill
     * suggestions are rendered inside the IME's own window; the Samsung clipboard and Galaxy AI
     * writing tools are overlays). It classified as an app window, started the sitting's away clock
     * ([com.astraedus.nudge.domain.sitting.SittingTracker]), typing (IME events, transient) never
     * cancelled it, and closing the keyboard two minutes later read as a return from a two-minute
     * absence: the grant went and the block came back.
     *
     * ## The evidence, all of it positive
     *
     * 1. The event's own window is a window we can see and it is NOT an application window (the
     *    keyboard, a system or app overlay, an accessibility overlay). Whatever package drew into
     *    it, nobody is "in" it.
     * 2. The package owns application windows, NONE active or focused, while another package's
     *    application window is. A panel beside the app the user is in.
     * 3. The keyboard is up, the package owns no window at all in the list, and another package's
     *    application window holds focus: something drawn inside or beside the keyboard (an
     *    embedded inline-suggestion view is not a window of its own).
     *
     * The list is current for the event that matters most: on `TYPE_WINDOW_STATE_CHANGED`,
     * `AccessibilityManagerService.sendAccessibilityEvent` calls
     * `WindowManagerInternal.computeWindowsForAccessibility()` BEFORE dispatching (verified in the
     * android10-release source, the oldest platform in these reports), and the service re-reads
     * the list on every such event.
     *
     * Anything short of that, including no window list at all, an event window id the list does
     * not contain, or no application window holding focus, answers [ForegroundSignal.AppWindow]
     * exactly as before. A wrong Transient here is a block not evaluated, so it needs proof; a wrong
     * AppWindow is today's behaviour. Case 3 is the only one that can mistake a brand-new app for a
     * panel (its window not listed yet, keyboard still listed), and only for that one event: the
     * app's next content change goes through the issue #7 verified path and is evaluated then.
     */
    private fun appWindowUnlessWindowsDisagree(record: AccessibilityEventRecord): ForegroundSignal {
        val pkg = record.packageName
        val snapshot = windows() ?: return ForegroundSignal.AppWindow(pkg)
        val notTheApp =
            (record.windowId != AccessibilityEventRecord.UNDEFINED &&
                snapshot.isNonApplicationWindow(record.windowId)) ||
                snapshot.focusIsElsewhere(pkg) ||
                drawnWithTheKeyboard(snapshot, pkg)
        return if (notTheApp) ForegroundSignal.Transient(pkg) else ForegroundSignal.AppWindow(pkg)
    }

    /** Evidence 3 of [appWindowUnlessWindowsDisagree]. */
    private fun drawnWithTheKeyboard(snapshot: WindowSnapshot, pkg: String): Boolean {
        if (snapshot.windows.none { it.kind == WindowKind.INPUT_METHOD }) return false
        if (snapshot.windows.any { it.packageName == pkg }) return false
        return snapshot.windows.any {
            it.kind == WindowKind.APPLICATION && it.holdsFocus &&
                it.packageName != null && it.packageName != pkg
        }
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
            packageName in imePackages() ||
            (currentImePackage != null && packageName == currentImePackage)
}
