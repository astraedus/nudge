package com.astraedus.nudge.domain.block

import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.ForegroundSignal

/**
 * Whether a block decision that has just finished being computed may still be shown.
 *
 * ## The assumption this deletes
 *
 * Every launch of `BlockOverlayActivity` used to assume *the app this decision is for is still the
 * app in front*. Nothing checked it. A decision is computed on the service's IO scope, a rule
 * lookup, a usage read, sometimes a URL-bar read, while foreground changes keep arriving on the
 * main thread, so the assumption is false whenever the user leaves faster than the database
 * answers. That is [#31](https://github.com/astraedus/nudge/issues/31) verbatim: *"a block/delay
 * decision can finish after the user has already left the target app, and the overlay is shown over
 * Nudge or another foreground app."*
 *
 * It is the same shape as [#19](https://github.com/astraedus/nudge/issues/19) one layer up. There,
 * a package had a *window* while the user was elsewhere and every evaluation path read that as "P is
 * in front". Here, a package *was* in front when the evaluation started and is not when it finishes.
 * Both are the same silent premise, so both get the same treatment: one gate, ahead of every launch
 * site, rather than a check bolted onto the branch that happened to be reported.
 *
 * ## The second condition, and why it lives in the SAME gate
 *
 * [#26](https://github.com/astraedus/nudge/issues/26): *"I changed my mind - have to click twice"*
 * `BlockOverlayActivity.navigateHome` dispatches `GLOBAL_ACTION_HOME` and finishes. The activity is
 * singleInstance in its own task with an empty taskAffinity, so finishing pops back to the task
 * underneath, the blocked app. On a device where that pop wins the race, the blocked app's window
 * genuinely resumes, fires a real `TYPE_WINDOW_STATE_CHANGED`, is a real [ForegroundSignal.AppWindow]
 * for a real foreground app, and re-arms the block the user just declined. Three reporters saw it
 * every time; the bench Pixel 3 never did, because the answer is device and launcher timing.
 *
 * So the foreground check alone cannot fix #26: at the moment the phantom decision lands, the
 * blocked app really IS in front. What is missing is that the service does not know a departure is
 * in flight. Both issues are therefore one question asked at one place: **is this decision still
 * about where the user is going to be?**
 *
 * Pure, so both conditions and every combination of them are unit tests rather than device sessions
 * (`BlockLaunchGateTest`), in the same spirit as [CooldownGate].
 */
object BlockLaunchGate {

    /**
     * How long a dispatched "go home" is allowed to still be in flight.
     *
     * This is a FAIL-SAFE, not the mechanism. In the normal case the window is closed by evidence,
     * not by the clock: [walkAwayAfter] drops it the moment the launcher (or any other app) is
     * observed in front, which on any device is the transition the user is watching. The timeout
     * only covers the case where that evidence never arrives: `GLOBAL_ACTION_HOME` was accepted and
     * did nothing, or the launcher set could not be resolved so a real Home event was classified as
     * something else.
     *
     * 1.5 seconds is chosen against those two failure directions, not against a stopwatch. Too
     * short and the bug comes back on exactly the slow devices that reported it (the three reporters
     * are the evidence that a sub-second assumption is wrong). Too long and a *deliberate* re-open
     * inside the window opens the app with no delay, which is a bypass. Since the evidence path
     * closes the window on the very first foreground event after the transition, the timeout is only
     * ever reached when nothing moved at all, and a user who walks away, sees no transition happen,
     * and re-opens the app within 1.5 seconds is indistinguishable from the bug we are fixing.
     * Missing one block there, once, self-corrects on the next window event; showing the overlay
     * they just dismissed does not.
     */
    const val WALK_AWAY_TRANSITION_MS = 1_500L

    /** A "go home" dispatched by the walk-away path that the platform has not yet honoured. */
    data class WalkAway(val packageName: String, val armedAtMs: Long)

    /**
     * A block overlay we have started but have not yet seen reach the screen.
     *
     * `NudgeAccessibilityService.isOverlayActive` is set synchronously at `startActivity`, which is
     * the right moment for "stop evaluating" but the WRONG moment for "the overlay is covering the
     * app". Between those two moments the blocked app is still starting up underneath, and it goes
     * on firing `TYPE_WINDOW_STATE_CHANGED` for its own windows. See [isGenuineBypass].
     */
    data class PendingOverlay(
        val packageName: String,
        val launchedAtMs: Long,
        val windowShown: Boolean,
        /**
         * Which `BlockOverlayActivity` INSTANCE this pending overlay belongs to.
         *
         * Needed because the activity that clears it is not always the one that owns it: an
         * overlay finishing itself from `onStop` while the service launches a replacement has its
         * `onDestroy` run AFTER the new instance's `onResume`. See [pendingOverlayAfterDismissal].
         */
        val id: Long = NO_OVERLAY_ID
    )

    /** The id of an overlay nobody has claimed. Never equal to an id the guard hands out. */
    const val NO_OVERLAY_ID = -1L

    /**
     * Fail-safe only: how long an overlay may stay "pending" before its target's window events are
     * allowed to mean a bypass again.
     *
     * The real end of pending is evidence, `BlockOverlayActivity.onResume` reporting that it is on
     * screen. This covers the case where that never happens at all, a `startActivity` the platform
     * dropped, which would otherwise leave the service permanently unable to recognise a bypass.
     *
     * Measured on the Pixel 3 from `picker-subflow-keeps-sitting.jsonl`: the blocked app's first
     * window event to the overlay's own window is 804ms. Three seconds is comfortably clear of that
     * on a colder or slower device, and being generous here is the safe direction: a missed bypass
     * self-corrects (the overlay's own `onStop` finishes it and clears the flag), while a bypass
     * recognised too eagerly is the duplicate-launch bug this constant exists to end.
     */
    const val OVERLAY_SETTLE_MS = 3_000L

    /** Why a launch was allowed or refused. Named so logcat can say which condition fired. */
    enum class Decision {
        /** The decision is still about the app in front. Show it. */
        LAUNCH,

        /** The user has already moved on: another app, Nudge itself, or the launcher is in front. */
        DROP_FOREGROUND_MOVED,

        /** A walk-away is mid-transition; this app's window is leaving, not arriving. */
        DROP_WALK_AWAY_IN_FLIGHT,

        /**
         * An overlay for this same app is already launched and still on its way to the screen.
         * Showing a second one would log a second block for a single entry.
         */
        DROP_ALREADY_PENDING
    }

    /**
     * @param target the package a launch would block **re-entry to**, the app the user is sitting
     *   in, which for a web block is the BROWSER and not the rule's app. That distinction already
     *   exists as `EXTRA_PASSTHROUGH_PACKAGE` vs `EXTRA_PACKAGE_NAME`: the rule's app is what the
     *   block is attributed to (its label, its `UsageEvent`), and comparing THAT against the
     *   foreground would drop every web block ever, because the user is never in Instagram when
     *   instagram.com is blocked in Chrome.
     * @param foreground what the service last observed in front, or null when it has observed
     *   nothing yet. Null means "we have no claim", and the gate does not weaken enforcement on a
     *   claim it does not have.
     * @param walkAway a go-home dispatched by the walk-away path, if one is outstanding.
     */
    fun decide(
        target: String,
        foreground: String?,
        walkAway: WalkAway?,
        nowMs: Long,
        transitionMs: Long = WALK_AWAY_TRANSITION_MS,
        pendingOverlay: PendingOverlay? = null
    ): Decision = when {
        // Asked before anything else because it is about THIS launch being redundant rather than
        // about where the user is. Two overlays for one entry into an app write two `UsageEvent`
        // rows and inflate the count the home screen shows.
        pendingOverlay != null &&
            !pendingOverlay.windowShown &&
            pendingOverlay.packageName == target &&
            nowMs - pendingOverlay.launchedAtMs < OVERLAY_SETTLE_MS -> Decision.DROP_ALREADY_PENDING

        // Asked FIRST because it is the more specific answer, and because in the #26 case the
        // foreground check cannot help: the blocked app really is in front at that instant.
        walkAway != null &&
            walkAway.packageName == target &&
            nowMs - walkAway.armedAtMs < transitionMs -> Decision.DROP_WALK_AWAY_IN_FLIGHT

        foreground != null && foreground != target -> Decision.DROP_FOREGROUND_MOVED

        else -> Decision.LAUNCH
    }

    /**
     * What the foreground package becomes after [signal], given [previous].
     *
     * Only three signals move it, and the four that do not are the whole reason this is a function
     * rather than an assignment at the event site. A system surface, a keyboard, a framework popup,
     * a picture-in-picture bubble and a non-window event all carry a package that is NOT the app the
     * user is in, reading any of them as a foreground change would drop legitimate blocks whenever
     * the notification shade, a permission dialog or the volume panel happened to land inside the
     * few milliseconds a rule lookup takes. That is `SYSTEM_PACKAGES` answering a question it cannot
     * answer, the grouped-constant trap `docs/architecture/foreground-detection.md` records three
     * separate sprints of.
     *
     * [ForegroundSignal.OwnUi] deliberately DOES move it, and it is the case issue #31's reporter
     * cares about most: an overlay landing on top of Nudge's own screens is the most visible form of
     * the bug. Our own block overlay is also `OwnUi`, which is correct rather than unfortunate: a
     * second decision arriving while an overlay is already up has nothing to add, and if the user
     * bypasses that overlay back into the app, the bypass itself is an `AppWindow` that moves the
     * foreground back before the re-evaluation runs.
     */
    fun foregroundAfter(signal: ForegroundSignal, previous: String?): String? = when (signal) {
        is ForegroundSignal.AppWindow -> signal.packageName
        is ForegroundSignal.Home -> signal.packageName
        is ForegroundSignal.OwnUi -> signal.packageName

        is ForegroundSignal.AwarenessOverlay,
        is ForegroundSignal.SystemSurface,
        is ForegroundSignal.Transient,
        is ForegroundSignal.PipOnly,
        is ForegroundSignal.NotForeground -> previous
    }

    /**
     * The outstanding walk-away after [signal]: null once the departure has visibly happened.
     *
     * The window closes on EVIDENCE. Anything in front that is not the app being left is proof the
     * transition completed, which is what keeps this from being a blanket "ignore this app for 1.5
     * seconds", a genuine re-entry after the launcher has appeared blocks normally, immediately,
     * because by then there is no window left to be inside.
     *
     * A signal that carries no claim about what is in front (a system surface, a keyboard, a scroll)
     * leaves the window exactly as it was, for the same reason it leaves the foreground alone.
     */
    /**
     * Is this event really the user getting BACK INTO the blocked app past a live overlay, or is it
     * the blocked app still settling underneath an overlay that has not arrived yet?
     *
     * ## The duplicate block, measured
     *
     * `NudgeAccessibilityService` treats "a real app window came forward while an overlay is up" as
     * proof the overlay was bypassed: the user tapped the app's icon or picked it out of Recents,
     * orphaning the overlay in its own task. It clears the flag and re-evaluates, which is right for
     * that case and wrong for the far more common one.
     *
     * `picker-subflow-keeps-sitting.jsonl`, a Pixel 3 capture committed since v1.16.0, times a
     * single clean launch of Google Keep under a DELAY rule:
     *
     * ```
     *  1252ms  keep   android.widget.FrameLayout                     <- evaluate, launch the overlay
     *  1445ms  nudge  android.widget.FrameLayout                     <- the overlay TASK's first window
     *  1736ms  keep   ...keep.ui.activities.BrowseActivity           <- keep STILL starting up
     *  2056ms  nudge  ...overlay.BlockOverlayActivity                <- the overlay actually on screen
     * ```
     *
     * The event at 1736ms is Keep's own second window, 320ms before the overlay reaches the screen.
     * It is a `WINDOW_STATE_CHANGED` for a real `AppWindow`, so the old rule called it a bypass,
     * cleared the flag and re-evaluated. The 1000ms debounce did not absorb it either, because the
     * overlay's task window at 1445ms had already moved `lastPackage` to Nudge's own package. Result:
     * a second decision, a second `UsageEvent`, a second `startActivity`, for one entry into one app.
     * Device QA measured `wasBlocked` rising by 25 across 10 launches.
     *
     * The fix is not a longer debounce. It is that an overlay we have STARTED is not yet an overlay
     * the user can be past. Until it reports itself on screen, its target's own window events are the
     * app being covered, and nothing else. A different app coming forward in that gap is still a real
     * foreground change and still counts.
     *
     * @param pending the overlay we have launched and not yet seen, or null when none is in flight.
     */
    fun isGenuineBypass(
        eventType: A11yEventType,
        signal: ForegroundSignal,
        pending: PendingOverlay?,
        nowMs: Long,
        settleMs: Long = OVERLAY_SETTLE_MS
    ): Boolean {
        // Unchanged from the rule this replaces: only a new activity in front can be a bypass, and
        // only a real application window. Our own overlay, the launcher, a keyboard and a
        // picture-in-picture bubble are excluded by construction rather than by package set.
        if (eventType != A11yEventType.WINDOW_STATE_CHANGED) return false
        if (signal !is ForegroundSignal.AppWindow) return false

        if (pending == null) return true
        if (pending.windowShown) return true
        // Some OTHER app really did come forward. That is a foreground change whatever our overlay
        // is doing, and suppressing it would swallow a genuine switch.
        if (signal.packageName != pending.packageName) return true
        return nowMs - pending.launchedAtMs >= settleMs
    }

    /** The pending overlay after [signal], which only the overlay reporting itself can resolve. */
    fun pendingOverlayAfter(pending: PendingOverlay?, overlayShown: Boolean): PendingOverlay? =
        if (pending != null && overlayShown) pending.copy(windowShown = true) else pending

    fun walkAwayAfter(signal: ForegroundSignal, pending: WalkAway?): WalkAway? {
        if (pending == null) return null
        return when (signal) {
            // The go-home landed. This is the ordinary end of the window.
            is ForegroundSignal.Home -> null

            // The app being left is back in front: this is the pop that #26 is about, so the window
            // must survive it. Any OTHER app in front means the departure happened.
            is ForegroundSignal.AppWindow ->
                if (signal.packageName == pending.packageName) pending else null

            // Nudge's own UI in front is not the blocked app, so the departure did happen, but the
            // walk-away path itself is Nudge UI on its way out, so this must NOT close the window,
            // or the overlay's own dying window event would close it before the transition starts.
            is ForegroundSignal.OwnUi,
            is ForegroundSignal.AwarenessOverlay,
            is ForegroundSignal.SystemSurface,
            is ForegroundSignal.Transient,
            is ForegroundSignal.PipOnly,
            is ForegroundSignal.NotForeground -> pending
        }
    }

    // ------------------------------------------------- one confrontation per arrival (issue #36)

    /**
     * A block overlay we have shown for [targetPackage] during the arrival the user is currently
     * in, and the confrontations already COUNTED for it.
     *
     * ## Why counting needed its own state at all
     *
     * [#36](https://github.com/astraedus/nudge/issues/36): *"Statistics page registered 2500
     * interventions on one day when baseline is a couple of hundred."* 2500 is not a double count,
     * it is a loop, and every gate above this one is about whether an overlay should be SHOWN. That
     * is a different question from whether a `UsageEvent` should be WRITTEN, and until this existed
     * the second question had no answer of its own: a row was written whenever a launch happened,
     * so any mechanism that re-launched an overlay autonomously also re-counted it.
     *
     * The loops are real and none of them needs a finger on the screen. The overlay reaches the
     * screen, something stops it without finishing it (the screen goes off, the blocked app's task
     * re-fronts on a device whose launcher loses that race), `onStop` finishes it, the app resumes
     * underneath and fires a genuine `TYPE_WINDOW_STATE_CHANGED`, and every gate in the service
     * correctly says "this app is in front and there is no overlay up" -- so it blocks again, writes
     * again, and the overlay it puts up is stopped again. One row per iteration, a second or two per
     * iteration, for as long as the two keep fighting. A re-delivery through `onNewIntent` produces
     * the same shape by a different route (see [pendingOverlayAfterLaunch]).
     *
     * Patching each of those mechanisms leaves the structure that guarantees the next one. What is
     * missing is an invariant over the OUTCOME: **a confrontation is something the user arrived
     * into, and one arrival owes at most one row of each kind.** Enforcement is untouched by it --
     * when this says "no row", the overlay is still shown, because being re-shown a block is
     * exactly what should happen to someone who is still sitting in a blocked app.
     *
     * @param countedKeys the [confrontationKey]s already counted, oldest first, capped at
     *   [MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL]. A list rather than "the last key" deliberately:
     *   with only the last one, two rules that alternate inside one arrival (a whole-app rule and a
     *   feature rule on the same app, which fire from two different event paths) would each look new
     *   to the other and the loop would come straight back wearing two hats. The cap is what makes
     *   the count structurally unable to explode: whatever mechanism fires, one arrival can write at
     *   most that many rows.
     */
    data class Arrival(
        val targetPackage: String,
        val countedKeys: List<String>
    )

    /**
     * The ceiling on rows one arrival can produce, across every kind of block.
     *
     * Generous against real use -- an arrival holds a whole-app confrontation, a feature
     * confrontation, and for a browser one per site visited -- and small enough that no loop can
     * turn an afternoon into 2500 rows. It is enforced as a ceiling by [isNewConfrontation]; see
     * there for why a mere eviction policy would not have been an invariant at all.
     */
    const val MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL = 32

    /**
     * What makes two confrontations DIFFERENT things rather than the same thing shown twice.
     *
     * Not the target package, which for a web block is the browser and would collapse every site
     * behind one key. The identity of a confrontation is what the user ran into: the app the block
     * is attributed to, plus the in-app feature if it was a feature rule, plus the domain if it was
     * a website. Two sites blocked in one Chrome sitting are two confrontations; the same site
     * re-blocked four times while the user sits on it is one.
     */
    fun confrontationKey(
        attributedPackage: String,
        featureKey: String? = null,
        webDomain: String? = null
    ): String = buildString {
        append(attributedPackage)
        featureKey?.takeIf { it.isNotBlank() }?.let { append("|feature=").append(it) }
        webDomain?.takeIf { it.isNotBlank() }?.let { append("|domain=").append(it) }
    }

    /**
     * Is a row owed for this confrontation, or has the user already been counted for this arrival?
     *
     * Null [arrival] means the foreground has left the target since the last count (or nothing has
     * been counted yet), which is the definition of a fresh arrival. An [arrival] for a DIFFERENT
     * target is also fresh: the user cannot be mid-arrival in two apps at once, and failing toward
     * counting is the right direction for a state we are unsure of.
     *
     * **[MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL] is a HARD CEILING, not a memory budget.** That
     * distinction is the whole structural promise: remembering only the last N keys would bound
     * what we store while leaving the number of ROWS unbounded, because an evicted key looks new
     * again the next time it comes round. A loop that manufactured distinct keys would then be back
     * to 2500 a day with a tidier data structure. Refusing past the ceiling means one arrival can
     * write at most that many rows whatever fires, which is the property issue #36 needs and the
     * only one a future mechanism nobody has thought of cannot get around.
     *
     * An arrival really holding more than 32 distinct confrontations with no departure in between
     * is not a person; it is a loop, and it is reported as one (`BlockLaunchGuard.onLaunchAttempt`).
     * Under-counting there is the safe direction.
     */
    fun isNewConfrontation(arrival: Arrival?, target: String, key: String): Boolean {
        if (arrival == null || arrival.targetPackage != target) return true
        if (key in arrival.countedKeys) return false
        return arrival.countedKeys.size < MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL
    }

    /** The arrival after a confrontation for [key] has been counted. */
    fun arrivalAfterConfrontation(arrival: Arrival?, target: String, key: String): Arrival {
        val existing = if (arrival != null && arrival.targetPackage == target) {
            arrival.countedKeys
        } else {
            emptyList()
        }
        val keys = (existing - key) + key
        return Arrival(
            targetPackage = target,
            countedKeys = keys.takeLast(MAX_COUNTED_CONFRONTATIONS_PER_ARRIVAL)
        )
    }

    /**
     * The arrival after [signal]: null once the foreground has genuinely left the target.
     *
     * The list of departures is deliberately the same shape as [walkAwayAfter]'s evidence rule, and
     * for the same reason: only a signal that proves the user is somewhere else may end the thing
     * it governs.
     *
     * [ForegroundSignal.OwnUi] does NOT end an arrival here, and that is the difference from
     * [foregroundAfter]. Our own block overlay is `OwnUi`, and it is on screen for every one of
     * these confrontations by construction -- treating it as a departure would mean the overlay's
     * own appearance re-opened the arrival it belongs to, which is the loop this exists to close.
     * Nudge's MAIN app window IS a departure, but the accessibility stream cannot tell the two
     * apart (the overlay's task window arrives ~600ms early carrying a framework class name); the
     * service already makes that distinction with `isOwnAppWindowEvent` and reports it through
     * `BlockLaunchGuard.onDeparture`, which is also how a screen-off arrives.
     */
    fun arrivalAfterSignal(signal: ForegroundSignal, arrival: Arrival?): Arrival? {
        if (arrival == null) return null
        return when (signal) {
            is ForegroundSignal.Home -> null
            is ForegroundSignal.AppWindow ->
                if (signal.packageName == arrival.targetPackage) arrival else null

            is ForegroundSignal.OwnUi,
            is ForegroundSignal.AwarenessOverlay,
            is ForegroundSignal.SystemSurface,
            is ForegroundSignal.Transient,
            is ForegroundSignal.PipOnly,
            is ForegroundSignal.NotForeground -> arrival
        }
    }

    /**
     * The Kotlin NAMESPACE every class this app ships lives in, which is NOT its applicationId.
     *
     * `namespace = "com.astraedus.nudge"`, `applicationId = "dev.astraedus.nudge"`
     * (`app/build.gradle.kts`). Accessibility events carry the applicationId as the package and a
     * class name from the namespace, so the two are never interchangeable — and treating them as
     * interchangeable is [#33](https://github.com/astraedus/nudge/issues/33): the service asked
     * `className.startsWith(applicationId)`, which is false for every event this app can emit, and
     * the branch it guarded was dead in production for months while its tests passed.
     *
     * A literal rather than a value read off `BuildConfig` for the same reason
     * [MAIN_APP_ACTIVITY_CLASS] is one — this object is pure Kotlin with no Android imports, which
     * is what makes the arrival model JVM-testable. Production never relies on the literal:
     * `NudgeAccessibilityService` derives the namespace from a real class at runtime and passes it
     * in. `OwnClassNamespaceContractTest` pins the two against each other, and against
     * `BuildConfig.APPLICATION_ID`, so a rename of either cannot silently disable a branch again.
     */
    const val OWN_CLASS_NAMESPACE = "com.astraedus.nudge"

    /**
     * The one activity the user browses Nudge in (single-activity architecture).
     *
     * A literal rather than `MainActivity::class.java.name` because this object is pure Kotlin with
     * no Android imports, which is what makes the whole arrival model JVM-testable. Pinned against
     * the real class by `ArrivalAndStormGateTest`.
     */
    const val MAIN_APP_ACTIVITY_CLASS = "$OWN_CLASS_NAMESPACE.MainActivity"

    /**
     * Is [className] a class THIS APP ships?
     *
     * The single source of truth for "is this class ours", shared with
     * `NudgeAccessibilityService.shouldClearForOwnPackageEvent`, which is where issue #33 lived.
     * One predicate, so the namespace-vs-applicationId distinction is made once instead of at every
     * site that happens to need it.
     *
     * @param namespace the class namespace to test against. Production passes the value it derives
     *   from a real class (never a literal); the default is here for the pure tests.
     */
    fun isOwnNudgeClass(className: String?, namespace: String = OWN_CLASS_NAMESPACE): Boolean =
        className != null && (className == namespace || className.startsWith("$namespace."))

    /**
     * Is this Nudge's OWN MAIN app window, i.e. the user genuinely somewhere else?
     *
     * **Asked POSITIVELY, by exact class, and that is the whole point.** Every Nudge window is
     * [ForegroundSignal.OwnUi]: the block overlay, the Strict Mode guard, the picture-in-picture
     * explainer — and the overlay TASK's first window, which a device capture times ~600ms ahead of
     * the overlay itself carrying the FRAMEWORK class `android.widget.FrameLayout`. Asking "Nudge,
     * and not the block overlay" would therefore classify that framework window as the app, end the
     * arrival the overlay belongs to, and hand issue #36's loop straight back. Only the main
     * activity may answer yes.
     *
     * Deliberately NOT `NudgeAccessibilityService.shouldClearForOwnPackageEvent`, which asks a
     * different question — "is this ANY window of ours", for hiding the awareness overlays — and
     * now answers it through [isOwnNudgeClass], the shared predicate. That sibling was unreachable
     * in production until issue #33 was fixed, because it tested the class name against the
     * applicationId; see [OWN_CLASS_NAMESPACE].
     *
     * It lives in this pure object rather than beside its sibling in the service because naming an
     * Activity class inside a service file is what `MonitorServiceContractTest` exists to forbid.
     */
    fun isOwnMainAppWindow(
        eventType: A11yEventType,
        className: String?,
        mainActivityClassName: String = MAIN_APP_ACTIVITY_CLASS
    ): Boolean = eventType.isWindowChange && className == mainActivityClassName

    // ---------------------------------------------------------- the storm diagnostic (issue #36)

    /** How long a run of launches for one target is counted over before the window restarts. */
    const val STORM_WINDOW_MS = 60_000L

    /** How many launches for one target inside [STORM_WINDOW_MS] make a storm worth a log line. */
    const val STORM_LAUNCH_THRESHOLD = 5

    /**
     * A run of launch attempts for one target with no departure in between.
     *
     * The arrival invariant above makes the COUNT safe whatever the mechanism, which is the whole
     * point of it -- but it also makes the mechanism invisible, because the symptom that used to
     * arrive as "2500 interventions" now arrives as nothing at all. This is what keeps the next
     * field report diagnosable: the loop still happens, and it still says so, once.
     *
     * @param reported whether this storm has already produced its one line. One line per storm, not
     *   one per event: a loop that fires every second would otherwise fill logcat with the evidence
     *   of itself and push out everything around it that explains why.
     */
    data class LaunchStorm(
        val targetPackage: String,
        val windowStartedAtMs: Long,
        val launches: Int,
        val decisions: List<String>,
        val reported: Boolean
    )

    /** What a storm log line says. The guard supplies the foreground and pending-overlay state. */
    data class StormReport(
        val targetPackage: String,
        val launches: Int,
        val windowMs: Long,
        val decisions: List<String>
    )

    /** The storm state after one launch attempt for [target] that came back [decision]. */
    fun stormAfterLaunch(
        storm: LaunchStorm?,
        target: String,
        decision: Decision,
        nowMs: Long,
        windowMs: Long = STORM_WINDOW_MS
    ): LaunchStorm {
        val continuing = storm != null &&
            storm.targetPackage == target &&
            nowMs - storm.windowStartedAtMs < windowMs
        if (storm == null || !continuing) {
            return LaunchStorm(target, nowMs, 1, listOf(decision.name), reported = false)
        }
        return storm.copy(
            launches = storm.launches + 1,
            decisions = if (decision.name in storm.decisions) {
                storm.decisions
            } else {
                storm.decisions + decision.name
            }
        )
    }

    /**
     * The storm state after [signal]: null once the foreground has left the target it is counting.
     *
     * Deliberately the same rule as [arrivalAfterSignal] rather than a shared field on the arrival,
     * because a storm can exist with no arrival at all: five launches that the gate DROPS write no
     * rows and so never open an arrival, and a run of dropped launches is exactly the shape a
     * future loop is most likely to take.
     */
    fun stormAfterSignal(signal: ForegroundSignal, storm: LaunchStorm?): LaunchStorm? {
        if (storm == null) return null
        return when (signal) {
            is ForegroundSignal.Home -> null
            is ForegroundSignal.AppWindow ->
                if (signal.packageName == storm.targetPackage) storm else null

            is ForegroundSignal.OwnUi,
            is ForegroundSignal.AwarenessOverlay,
            is ForegroundSignal.SystemSurface,
            is ForegroundSignal.Transient,
            is ForegroundSignal.PipOnly,
            is ForegroundSignal.NotForeground -> storm
        }
    }

    /**
     * The line to log for [storm], or null when there is nothing new to say.
     *
     * Fires on the launch that crosses the threshold and on no other, so the caller marks the storm
     * reported and stays quiet until a departure or a fresh window restarts it.
     */
    fun stormReport(
        storm: LaunchStorm,
        nowMs: Long,
        threshold: Int = STORM_LAUNCH_THRESHOLD
    ): StormReport? {
        if (storm.reported || storm.launches < threshold) return null
        return StormReport(
            targetPackage = storm.targetPackage,
            launches = storm.launches,
            windowMs = nowMs - storm.windowStartedAtMs,
            decisions = storm.decisions
        )
    }

    // ------------------------------------------------ the pending overlay's identity (issue #36)

    /**
     * The pending overlay after a launch for [target], given the one already in flight.
     *
     * An overlay that is ALREADY ON SCREEN stays on screen when a second block for the same target
     * is delivered to it: `BlockOverlayActivity` is `singleInstance`, so that delivery arrives
     * through `onNewIntent` and never re-runs `onResume`. Resetting `windowShown` to false for it
     * was a loop of its own: nothing could ever set the flag back, so three seconds later
     * [isGenuineBypass] started reading the blocked app's own window events as the user getting past
     * an overlay that was sitting right there in front of them, each one worth a fresh evaluation
     * and a fresh row, every [OVERLAY_SETTLE_MS], indefinitely.
     *
     * [id] identifies the ACTIVITY INSTANCE for [pendingOverlayAfterDismissal], which is why a
     * re-delivery keeps the id it already has.
     */
    fun pendingOverlayAfterLaunch(
        pending: PendingOverlay?,
        target: String,
        nowMs: Long,
        id: Long
    ): PendingOverlay =
        if (pending != null && pending.packageName == target && pending.windowShown) {
            pending
        } else {
            PendingOverlay(packageName = target, launchedAtMs = nowMs, windowShown = false, id = id)
        }

    /**
     * The pending overlay after the activity holding [id] is destroyed.
     *
     * Only the instance that owns the pending overlay may clear it. When an overlay finishes itself
     * from `onStop` and the service immediately launches another, the platform runs the NEW
     * instance's `onCreate`/`onResume` BEFORE the old one's `onDestroy` -- so an unconditional clear
     * there wiped the live overlay's pending state, and with it the only thing standing between the
     * newly-blocked app's start-up window events and a second evaluation.
     */
    fun pendingOverlayAfterDismissal(pending: PendingOverlay?, id: Long): PendingOverlay? =
        if (pending == null || pending.id == id) null else pending

}
