package com.astraedus.nudge.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.block.BlockLaunchGate
import com.astraedus.nudge.domain.block.CooldownGate
import com.astraedus.nudge.domain.events.A11yEventType
import com.astraedus.nudge.domain.events.AccessibilityEventRecord
import com.astraedus.nudge.domain.events.EventClassifier
import com.astraedus.nudge.domain.events.ForegroundSignal
import com.astraedus.nudge.domain.interaction.SyntheticClickWindow
import com.astraedus.nudge.domain.lock.StrictModeEscapeGuard
import com.astraedus.nudge.domain.sitting.SittingEvent
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.WebBlockMode
import com.astraedus.nudge.domain.pip.PipEscapeLedger
import com.astraedus.nudge.domain.surfaces.FollowingSteer
import com.astraedus.nudge.domain.surfaces.HostSurface
import com.astraedus.nudge.domain.surfaces.PlatformSurfaces
import com.astraedus.nudge.domain.surfaces.PlatformSurfacesRegistry
import com.astraedus.nudge.domain.surfaces.SteerAction
import com.astraedus.nudge.domain.surfaces.SteerRecipe
import com.astraedus.nudge.domain.surfaces.TabCoverDecider
import com.astraedus.nudge.domain.surfaces.TabCoverEffect
import com.astraedus.nudge.domain.surfaces.TabCoverPlacement
import com.astraedus.nudge.domain.web.WebDomainGate
import com.astraedus.nudge.domain.web.WebSessionKey
import com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase
import com.astraedus.nudge.ui.lock.StrictModeGuardActivity
import com.astraedus.nudge.ui.overlay.BlockOverlayActivity
import com.astraedus.nudge.ui.overlay.PipEscapeActivity
import com.astraedus.nudge.util.CrashSafeScope
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NudgeAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NudgeAccessibilityEntryPoint {
        fun evaluateBlockUseCase(): EvaluateBlockUseCase
        fun usageRepository(): UsageRepository
        fun nudgePreferences(): NudgePreferences
        fun inAppDetector(): InAppDetector
        fun grayscaleManager(): GrayscaleManager
        fun interactionTracker(): InteractionTracker
        fun counterOverlayManager(): CounterOverlayManager
        fun timeRemainingOverlayManager(): TimeRemainingOverlayManager
        fun tabCoverOverlayManager(): TabCoverOverlayManager
        fun followingSteerExecutor(): FollowingSteerExecutor
        fun blockRuleRepository(): BlockRuleRepository
        fun nudgeLogger(): NudgeLogger
        fun passthroughManager(): PassthroughManager
        fun webDomainDetector(): WebDomainDetector
        fun strictModeEscapeManager(): StrictModeEscapeManager
        fun emergencyPassManager(): EmergencyPassManager
        fun blockLaunchGuard(): BlockLaunchGuard
    }

    /**
     * The scope every block evaluation, `UsageEvent` write and DataStore collect in this service
     * runs on.
     *
     * [CrashSafeScope] and not a bare `CoroutineScope(SupervisorJob() + Dispatchers.IO)`: the
     * supervisor stops a failed child cancelling its siblings, and does nothing at all about the
     * exception, which without a `CoroutineExceptionHandler` reaches the thread's default handler
     * and kills the process — and with it the accessibility service, which is the only thing
     * enforcing any block (backlog audit F7). Losing one database write must never stop blocking.
     */
    private val serviceScope = CrashSafeScope.create(
        name = "nudge-accessibility-service",
        dispatcher = Dispatchers.IO,
        logger = { entryPoint.nudgeLogger() }
    )

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            NudgeAccessibilityEntryPoint::class.java
        )
    }

    private var lastPackage: String? = null
    private var lastEvalTime: Long = 0L

    /**
     * Structured per-event trace (see [AccessibilityEventTrace] and
     * `docs/architecture/accessibility-event-pipeline.md`). Enabled only when debug logging is —
     * a debug build, or the seven-tap preference — so a release build pays one boolean test per
     * event. It sits at the very TOP of [onAccessibilityEvent], ahead of every early return, because
     * the events that get dropped are exactly the ones a bug report is usually about.
     */
    private val eventTrace by lazy {
        AccessibilityEventTrace(logger = entryPoint.nudgeLogger())
    }

    /**
     * Converts framework events into the pure [com.astraedus.nudge.domain.events.AccessibilityEventRecord].
     * The `viewIdResourceName` binder read has TWO callers with different budgets, and conflating
     * them is how a doc comes to claim the release build pays nothing:
     *  - this factory reads it into the RECORD only while the trace is on, because there it is a
     *    capture-time diagnostic for a human;
     *  - `InteractionHandler` reads it directly, in every build including release, because telling a
     *    comments sheet from the feed behind it is a production decision the counter cannot make
     *    without it.
     * So the release hot path does pay for it, once per scroll event. That is the deliberate trade
     * documented on `InteractionHandler.sourceViewIdFor`.
     */
    private val eventRecordFactory by lazy {
        AccessibilityEventRecordFactory(
            readSourceViewId = {
                if (eventTrace.isEnabled()) {
                    AccessibilityEventRecordFactory.SourceReadPolicy.SCROLL_AND_CLICK
                } else {
                    AccessibilityEventRecordFactory.SourceReadPolicy.NEVER
                }
            },
            onSourceReadCost = eventTrace::noteSourceReadCost
        )
    }

    /**
     * The single place that answers "what is on screen?". Constructed once with the package sets
     * whose scope has now been narrowed to the one question each of them can honestly answer —
     * see [ForegroundSignal] for why that narrowing is the actual fix for issue #28.
     */
    private val eventClassifier by lazy {
        EventClassifier(
            ownPackageName = applicationContext.packageName,
            systemPackages = SYSTEM_PACKAGES,
            imePackages = IME_PACKAGES,
            frameworkPackage = FRAMEWORK_PACKAGE,
            awarenessOverlayClassNames = AwarenessOverlayWindow.CLASS_NAMES
        )
    }

    private val lastContentChangedTime = mutableMapOf<String, Long>()
    private val contentChangedDebounceMs = 2000L

    /** Per-package throttle for the issue #7 content-change app-switch check. */
    private val lastSwitchCheckTime = mutableMapOf<String, Long>()

    /**
     * The [WebSessionKey] of the blocked domain currently in the foreground, or null when the user
     * is not on one. This is the web equivalent of "which app is in front" and is what the web
     * foreground-time clock, the auto-kick and its cooldown are keyed by.
     */
    @Volatile
    private var activeWebSessionKey: String? = null

    /**
     * The web foreground-time clock. Deliberately a SECOND job rather than a reuse of
     * [foregroundTimeJob]: browsers are not in the counter cache, so every browser window event runs
     * `clearOverlays` -> `stopForegroundTimeTicker()` and the shared job would be torn down and
     * restarted (re-reading usage) on each one. Keeping them separate also means nothing on the
     * app-level hot path changes shape.
     */
    private lateinit var webClock: ForegroundClock

    /** Resolves a `web:` key to the browser's clock. See [WebSessionUsageProvider]. */
    private lateinit var webSessionUsageProvider: WebSessionUsageProvider

    /** The time-based auto-kick, reading the web clock. Same class, same evaluator, same executor. */
    private lateinit var autoKickWebTimeHandler: AutoKickTimeHandler

    @Volatile
    private var grayscaleActiveForPackage: String? = null

    private val counterCache = CounterCacheRefresher()

    /**
     * Show / move / hide / leave-alone for the Reels tab cover. Pure; holds no state of its own
     * beyond what it is handed, so the "is a cover up, and where" question has exactly one owner
     * ([TabCoverOverlayManager]).
     */
    private val tabCoverDecider = TabCoverDecider()

    /**
     * "Have we already steered this arrival at the home feed, and is a menu tap outstanding."
     *
     * Lives on the service rather than in the manager because it is a fact about the USER'S VISIT,
     * not about a window: it has to survive the dropdown opening (which makes the tree stop looking
     * like the home feed) and it has to be forgotten when they leave the app.
     */
    private val followingSteer = FollowingSteer()

    /**
     * Remembers that the click the platform is about to report was OURS.
     *
     * The steer's `ACTION_CLICK` is reported exactly like a finger, so without this the blocker's
     * own taps land in the user's "taps today" -- measured on device as `reason=click session=14`
     * straight after a steer. A counter that includes the blocker's own actions is measuring itself.
     */
    private val syntheticClicks = SyntheticClickWindow()

    private lateinit var interactionHandler: InteractionHandler
    private lateinit var timeRemainingHandler: TimeRemainingHandler
    private lateinit var autoKickExecutor: AutoKickExecutor
    private lateinit var autoKickTimeHandler: AutoKickTimeHandler

    /**
     * The periodic foreground-time clock (see [updateForegroundTimeTicker]). At most one package is
     * clocked at a time. [ForegroundClock] owns the loop so that one throwing tick cannot end it in
     * silence, and so every start/stop is logged with its reason.
     */
    private lateinit var foregroundClock: ForegroundClock

    companion object {
        private const val DEBOUNCE_MS = 1000L

        /**
         * Cadence of the foreground-time clock. Matches the time-remaining overlay's existing
         * update interval, so the two share one tick and one usage read. A time-based auto-kick can
         * therefore overshoot its threshold by up to this much — acceptable against thresholds
         * measured in minutes, and cheaper than a tighter poll on the 3GB Pixel 3.
         */
        private const val FOREGROUND_TICK_MS = 30_000L

        /** Upper bound on nodes scanned when harvesting Settings window text (bounded traversal). */
        private const val MAX_NODES_SCANNED = 800

        /**
         * Minimum gap between active-window reads for the issue #7 content-change switch check.
         * Short enough that a real re-entry is still caught on its first event (the previous check
         * for that package is always older than this), long enough that a sustained stream of
         * content changes cannot hammer the node tree.
         */
        private const val SWITCH_CHECK_DEBOUNCE_MS = 500L

        val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.samsung.android.launcher",
        )

        /** The `android` framework package: hosts toasts, transient dialogs, and — the important
         *  one for issue #5 — the floating text-selection / paste toolbar and long-press popups. */
        const val FRAMEWORK_PACKAGE = "android"

        /**
         * Static soft-keyboard packages. A fallback only — the *active* keyboard is matched
         * dynamically (see [isTransientNonAppPackage] + [currentImePackage]), which is what covers
         * third-party keyboards (FUTO, SwiftKey, …) that are not on any hardcoded list.
         */
        val IME_PACKAGES = setOf(
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.sec.android.inputmethod",
        )

        /**
         * True when a window event comes from a transient, non-application window that must NOT be
         * treated as a foreground app switch: any soft keyboard / IME (matched dynamically against
         * [currentImePackage] so EVERY keyboard is covered, plus the static [IME_PACKAGES]
         * fallback), or the [FRAMEWORK_PACKAGE] that hosts toasts / transient dialogs / the paste
         * + long-press popup toolbars.
         *
         * Root cause of issue #5: after a delay completes, passthrough is granted for app X. When
         * the user then opened the keyboard or a paste/long-press popup, that surfaced a *different*
         * package on a window event; routing it into evaluation cleared X's passthrough, so tapping
         * back into X re-triggered the block. Recognising these as transient — and ignoring their
         * window events — keeps the passthrough intact.
         */
        internal fun isTransientNonAppPackage(
            packageName: String,
            currentImePackage: String?
        ): Boolean {
            return packageName == FRAMEWORK_PACKAGE ||
                packageName in IME_PACKAGES ||
                (currentImePackage != null && packageName == currentImePackage)
        }

        /**
         * How long a resolved launcher set is trusted before it is re-read from PackageManager.
         *
         * The default home app can change (the user picks a new launcher, or installs one), and the
         * set is only consulted when a system package is already in front, so a lazy throttled
         * refresh costs one small binder query every few minutes at worst. A stale set degrades to
         * the OLD behaviour (passthrough simply isn't cleared) — never to a false "user left".
         */
        private const val LAUNCHER_REFRESH_MS = 5 * 60_000L

        /**
         * Packages that answer a `CATEGORY_HOME` query but are NOT the user's home screen, and must
         * never be read as "the user left the app".
         *
         * `com.android.settings` is the load-bearing one: AOSP declares `Settings$FallbackHome`
         * with `CATEGORY_HOME` + `CATEGORY_DEFAULT` (it is the placeholder home shown before the
         * user unlocks after a reboot), so a plain `queryIntentActivities` DOES return Settings on a
         * stock device. Treating Settings as home would clear passthrough for every permission /
         * settings excursion. The rest are defence in depth against an OEM declaring a home filter
         * on a system-surface package.
         */
        private val NEVER_LAUNCHER_PACKAGES = setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
        )

        /**
         * Filter a raw `CATEGORY_HOME` resolution down to packages that may legitimately be treated
         * as the home screen. Pure so the exclusions are unit-tested rather than eyeballed.
         *
         * Drops blanks, our own package, the [FRAMEWORK_PACKAGE] (an unset default home resolves to
         * the framework's chooser/ResolverActivity), every [NEVER_LAUNCHER_PACKAGES] entry, and any
         * IME (a keyboard is the canonical "surfaced without the user leaving" window — it must
         * never end up in this set by any route).
         */
        internal fun sanitizeLauncherPackages(
            resolved: Collection<String?>,
            ownPackageName: String
        ): Set<String> = resolved.asSequence().filterNotNull().filterTo(mutableSetOf()) { candidate ->
            candidate.isNotBlank() &&
                candidate != ownPackageName &&
                candidate != FRAMEWORK_PACKAGE &&
                candidate !in NEVER_LAUNCHER_PACKAGES &&
                candidate !in IME_PACKAGES
        }

        val WINDOW_CHANGE_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )

        @Volatile
        var isOverlayActive = false
            private set

        /**
         * Every package we have actually put a block overlay in front of since the process started.
         *
         * Used only to decide whether a picture-in-picture escape is worth EXPLAINING (issue #19):
         * an app floating in PiP is unremarkable, an app we were blocking floating in PiP is the
         * escape. Deliberately not "is there a block up right now" — the field failure showed the
         * escape usually outlives the overlay (the overlay dismisses, the bubble keeps playing), and
         * the reported repro reaches PiP minutes later via an emergency pass.
         *
         * Session-scoped and unbounded-by-design: it can only ever hold packages the user has rules
         * for, and it is a plain in-memory set that dies with the process.
         */
        private val blockedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * The block overlay is now on screen for [packageName].
         *
         * Paired with [markOverlayInactive] rather than assigning [isOverlayActive] at each of the
         * six launch and dismissal sites: this is also where [blockedThisSession] is recorded, and
         * a launch site that set the flag but forgot the record would silently disable the PiP
         * explainer for that path.
         */
        fun markOverlayActive(packageName: String?) {
            packageName?.takeIf { it.isNotBlank() }?.let { blockedThisSession.add(it) }
            isOverlayActive = true
        }

        /** The block overlay is gone (dismissed, completed, or bypassed). */
        fun markOverlayInactive() {
            isOverlayActive = false
        }

        /** True if we have put a block overlay in front of [packageName] this session. */
        internal fun hasBlockedThisSession(packageName: String): Boolean =
            packageName in blockedThisSession

        /** Test seam: clear the session block record. */
        internal fun clearBlockedThisSession() = blockedThisSession.clear()

        @Volatile
        var passthroughManagerInstance: PassthroughManager? = null
            private set

        /**
         * The running service instance, used so the Strict Mode guard overlay can request a
         * reliable "go home" via [AccessibilityService.performGlobalAction]. Null when the service
         * is not connected; callers MUST provide their own fallback (a HOME intent) so the user is
         * never trapped on a Settings screen.
         */
        @Volatile
        private var instance: NudgeAccessibilityService? = null

        /**
         * Is the accessibility service bound and enforcing *right now*?
         *
         * Deliberately a different question from "is Nudge listed in
         * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`". After an OS process kill (the nightly
         * `full_backup_package`, memory pressure, a force stop) the setting still says yes and this
         * says no, for as long as it takes the system to rebind. Every block Nudge enforces hangs
         * off this being true, so [NudgeMonitorService] polls it and tells the user when it is not.
         */
        fun isConnected(): Boolean = instance != null

        /**
         * Send the user to the home screen. Uses [AccessibilityService.GLOBAL_ACTION_HOME] when the
         * service is connected (cleanly exits whatever Settings screen they are on). Returns true if
         * the global action was dispatched; false if the service was unavailable so the caller can
         * fall back to a HOME intent. Either way the user must end up home — this is a safety-critical
         * "never trap the user" path.
         */
        fun requestGoHome(): Boolean {
            val service = instance ?: return false
            return try {
                service.performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * The class NAMESPACE this app's own classes live in, read off a real class.
         *
         * Never a literal, and never `applicationContext.packageName`. That second mistake is
         * [#33](https://github.com/astraedus/nudge/issues/33): the applicationId is
         * `dev.astraedus.nudge` and every class name an accessibility event can carry starts with
         * `com.astraedus.nudge`, so `className.startsWith(packageName)` was false for every event
         * this app can emit and [shouldClearForOwnPackageEvent] was dead in production for months.
         * Its unit tests passed throughout, because they supplied a matching value production never
         * did — which is why `OwnClassNamespaceContractTest` now ties the test's constants to the
         * real `BuildConfig.APPLICATION_ID` and the real namespace.
         *
         * `BuildConfig` is generated INTO the namespace, so this is the namespace by construction
         * and a rename moves it. Taken off the class NAME rather than `Class.getPackageName()`,
         * which is API 31 and this app's minSdk is 26: an unguarded call there is a
         * `NoSuchMethodError` on every Android 8-11 device, in the companion initialiser of the
         * accessibility service, i.e. blocking would simply never start. `lintDebug` is the only
         * check in this repo that can see that, and it did. (Release builds do not minify, so shipped class names match what
         * the tests see; if that ever changes, both sides of this comparison are obfuscated
         * together, because both come from real classes.)
         */
        internal val OWN_CLASS_NAMESPACE: String =
            BuildConfig::class.java.name.substringBeforeLast('.')

        /**
         * Is this a window event for one of OUR OWN windows, i.e. should the awareness overlays be
         * cleared because Nudge itself came forward?
         *
         * @param ownClassNamespace [OWN_CLASS_NAMESPACE] in production. A parameter so the test can
         *   prove the predicate against the REAL value AND against the applicationId that broke it.
         */
        internal fun shouldClearForOwnPackageEvent(
            eventType: Int,
            className: String?,
            ownClassNamespace: String
        ): Boolean {
            return eventType in WINDOW_CHANGE_EVENT_TYPES &&
                BlockLaunchGate.isOwnNudgeClass(className, ownClassNamespace)
        }

        // "Is this Nudge's own MAIN app window?" (issue #36) is deliberately NOT here, next to its
        // sibling above: it lives in `BlockLaunchGate.isOwnMainAppWindow` because naming an
        // Activity class inside a service file is exactly what `MonitorServiceContractTest`
        // forbids, and it is right to forbid it — "a service put its UI over another app" is a bug
        // report this repo has already received. The predicate compares a class NAME and starts
        // nothing, but the contract is worth more than the convenience of keeping the two together.

        // The overlay-bypass rule used to live here as `isOverlayBypassedByForeground`, and it is
        // now `BlockLaunchGate.isGenuineBypass`, which needs state this companion cannot hold: it
        // has to know whether the overlay we launched has actually reached the screen yet. Keeping
        // a second copy of the rule here, even an unused one, is the "two answers to one question"
        // shape that produced #5, #7, #19 and #28. `PassthroughTest` and `TransientWindowTest` pin
        // the surviving one; with no pending overlay it reduces to exactly the old rule, so every
        // case those suites cover still reads the same.
    }

    /** Cached once: our own user-visible app label, used to anchor escape-screen detection. */
    private val ownAppLabel: String by lazy {
        try {
            val info = packageManager.getApplicationInfo(applicationContext.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            ""
        }
    }

    /** Debounce: don't re-launch the guard overlay repeatedly for the same Settings window burst. */
    private var lastStrictGuardLaunchTime: Long = 0L

    /**
     * Cached Strict Mode state, collected off-main so the hot accessibility-event path never blocks
     * on DataStore. Read on the main thread when deciding whether to guard a Settings escape screen.
     */
    @Volatile private var strictModeEnabledCached: Boolean = false
    @Volatile private var strictModeChallengeLengthCached: Int =
        com.astraedus.nudge.domain.lock.StrictModeChallenge.DEFAULT_LENGTH

    /**
     * Cached global-enabled state (the home-screen master toggle), collected off-main so the hot
     * accessibility-event path can gate ALL enforcement synchronously without blocking on DataStore.
     * Defaults to true (fail toward enforcement) until the first emission corrects it — the pref
     * itself defaults to true. When it flips OFF, [onGlobalDisabled] neutralizes any active
     * enforcement state so a disabled Nudge behaves as if uninstalled.
     */
    @Volatile private var globalEnabledCached: Boolean = true

    /**
     * Package of the currently-selected default keyboard (IME), read from
     * [Settings.Secure.DEFAULT_INPUT_METHOD]. Cached so the hot accessibility path can recognise
     * ANY keyboard's window events as transient (issue #5) without a hardcoded list. Kept fresh via
     * [imeSettingObserver] so switching keyboards is picked up.
     */
    @Volatile private var currentImePackage: String? = null

    /**
     * Packages that count as the HOME SCREEN, resolved from PackageManager (`CATEGORY_HOME`) rather
     * than hardcoded, because the default launcher is user-choosable and OEM-specific. Cached like
     * the other hot-path flags so [onAccessibilityEvent] answers "did the user go home?" with a set
     * lookup and no binder call; refreshed lazily by [refreshLauncherPackagesIfStale].
     *
     * Empty until resolved (and on any failure), which degrades to the pre-fix behaviour: nothing is
     * treated as home, so nothing is cleared.
     */
    @Volatile private var launcherPackagesCached: Set<String> = emptySet()

    /** Main-thread only: when [launcherPackagesCached] was last read from PackageManager. */
    private var lastLauncherResolveTime: Long = 0L

    /**
     * Packages we have already shown the picture-in-picture escape explainer for (issue #19), cached
     * off-main from DataStore exactly like the Strict Mode / global-enabled flags so the hot path can
     * check "have we already explained this app?" synchronously.
     */
    @Volatile private var pipEscapePromptedCached: Set<String> = emptySet()

    /**
     * Reads the accessibility window list to answer "which packages are in picture-in-picture right
     * now?". Consulted only from [refreshPipOnlyPackages], on window-change events.
     */
    private val pipWindowProbe = PipWindowProbe(readWindows = ::readPipWindows)

    /**
     * Packages whose ONLY presence on screen is a floating picture-in-picture window (issue #19).
     *
     * Refreshed on window-change events (a PiP window can only appear or vanish via one) and read on
     * EVERY event as a zero-cost set lookup — which is what makes it affordable to gate the whole
     * event pipeline on it rather than one branch of it.
     */
    @Volatile private var pipOnlyPackagesCached: Set<String> = emptySet()

    /**
     * Ends the user's sitting when the screen goes off (backlog F5).
     *
     * The repro this closes: *complete Instagram's delay, lock the phone, unlock hours later
     * straight back into Instagram, no delay.* `PassthroughManager` has no time expiry by design
     * (issue #5: a timer would re-block a user mid-use), and the keyguard is a system surface, so
     * nothing ever ended that sitting. A screen-off is not a timer — it is an observation that the
     * user stopped using the phone, which is exactly the evidence the grant's lifetime needs.
     *
     * `ACTION_SCREEN_OFF` cannot be declared in a manifest (it is a protected, registered-only
     * broadcast), which is why it lives here and not in `BootReceiver`.
     */
    private val screenOffReceiver by lazy {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                entryPoint.passthroughManager().onScreenOff()
                // The same evidence that ends the sitting ends the ARRIVAL (issue #36): coming back
                // to a blocked app after the phone has been off is a fresh confrontation and owes
                // its own row. It arrives here rather than through the signal pipeline because a
                // screen-off is a broadcast and not an accessibility event at all -- and because
                // the overlay's own `onStop`/`finish()` on screen-off is one half of the loop that
                // produced 2500 interventions in a day.
                entryPoint.blockLaunchGuard().onDeparture("screen_off")
                // The awareness overlays and the clocks belong to a screen nobody is looking at.
                hideAllOverlays()
            }
        }
    }

    /** Refreshes [currentImePackage] whenever the default keyboard changes. */
    private val imeSettingObserver by lazy {
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshCurrentImePackage()
            }
        }
    }

    /**
     * Snapshot the accessibility window list for [pipWindowProbe] (issue #19).
     *
     * The owner of a window is only resolved for windows ALREADY flagged as picture-in-picture:
     * [android.view.accessibility.AccessibilityWindowInfo.getRoot] is a binder read per window, and
     * a device has many windows but at most one app in PiP. Non-PiP windows therefore come back with
     * a null package, which [PipWindowProbe.pipPackages] ignores.
     *
     * [AccessibilityWindowInfo.TYPE_APPLICATION] is carried through because SystemUI's
     * "Picture-in-Picture menu" window is ALSO flagged PiP and sorts ahead of the app's own window —
     * see [PipWindowProbe.pipPackages] for the field failure that caused.
     *
     * Fails soft to an empty list: an unreadable window list must never crash the service, and
     * "nothing in PiP" leaves every existing code path behaving exactly as it did before this fix.
     */
    private fun readPipWindows(): List<PipWindow> = try {
        windows.orEmpty().map { window ->
            val inPip = window.isInPictureInPictureMode
            PipWindow(
                packageName = if (inPip) window.root?.packageName?.toString() else null,
                isPictureInPicture = inPip,
                isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Recompute [pipOnlyPackagesCached] (issue #19). Called on window-change events only.
     *
     * The active-window read happens only when something is actually in PiP, so the steady-state
     * cost of this is one throttled `getWindows()` per window-change burst.
     *
     * When the set CHANGES we log it and run the explainer decision. Doing both here rather than
     * per-event is what keeps the logging bounded — and the log line is unconditional, which the
     * previous implementation got wrong: it logged only in the branch that actually showed the
     * explainer, so the field failure produced total silence with no way to tell whether detection
     * had fired and been suppressed, or never fired at all.
     */
    private fun refreshPipOnlyPackages() {
        val inPip = pipWindowProbe.packagesInPictureInPicture()
        val next = if (inPip.isEmpty()) {
            emptySet()
        } else {
            PipWindowProbe.pipOnlyPackages(inPip, activeWindowPackageOrNull())
        }
        if (next == pipOnlyPackagesCached) return

        pipOnlyPackagesCached = next
        entryPoint.nudgeLogger().i("picture-in-picture windows changed pipOnly=$next")
        next.forEach { maybeExplainPipEscape(it) }
    }

    /**
     * Show the picture-in-picture explainer for [packageName], at most once ever (issue #19).
     *
     * Gated on [hasBlockedThisSession]: an app floating in PiP is unremarkable, an app we were
     * blocking floating in PiP is the escape worth explaining. Both suppression reasons are logged,
     * because "detection fired but stayed silent" and "detection never fired" are indistinguishable
     * from the outside and that ambiguity cost a whole release cycle.
     */
    private fun maybeExplainPipEscape(packageName: String) {
        if (!hasBlockedThisSession(packageName)) {
            entryPoint.nudgeLogger().d(
                "pip escape not explained package=$packageName reason=never_blocked_this_session"
            )
            return
        }
        if (packageName in pipEscapePromptedCached) {
            entryPoint.nudgeLogger().d(
                "pip escape not explained package=$packageName reason=already_explained"
            )
            return
        }

        // Mark before the DataStore write and before starting the activity, so a burst of window
        // changes cannot stack explainers while the write is in flight.
        pipEscapePromptedCached = pipEscapePromptedCached + packageName
        serviceScope.launch {
            entryPoint.nudgePreferences().recordPipEscapePrompted(packageName)
        }

        entryPoint.nudgeLogger().i(
            "picture-in-picture escape detected package=$packageName — explaining once"
        )

        val intent = Intent(applicationContext, PipEscapeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(PipEscapeActivity.EXTRA_PACKAGE_NAME, packageName)
        }
        applicationContext.startActivity(intent)
    }

    /**
     * Re-read the home-screen packages if the cached set is older than [LAUNCHER_REFRESH_MS].
     *
     * Called from [onAccessibilityEvent] on every `TYPE_WINDOW_STATE_CHANGED`, because the
     * classifier needs the launcher set to answer "is this Home?" and now runs BEFORE the branch
     * that used to own this call. The per-event cost is one `Long` comparison; the throttle means
     * the worst case is still one small `queryIntentActivities` every five minutes.
     */
    private fun refreshLauncherPackagesIfStale(now: Long) {
        if (now - lastLauncherResolveTime < LAUNCHER_REFRESH_MS) return
        lastLauncherResolveTime = now
        launcherPackagesCached = resolveLauncherPackages()
    }

    /**
     * Ask PackageManager which packages can act as the home screen: the CURRENT default first (the
     * one Home actually goes to), plus every installed home-capable app so switching launchers is
     * covered between refreshes. `QUERY_ALL_PACKAGES` in the manifest makes the query complete on
     * API 30+.
     *
     * Fails soft to an empty set — an unresolvable launcher means "we cannot tell when the user goes
     * home", which is exactly how this service behaved before, not a licence to guess.
     */
    private fun resolveLauncherPackages(): Set<String> = try {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = mutableListOf<String?>()
        candidates += packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        packageManager
            .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(candidates) { it.activityInfo?.packageName }
        sanitizeLauncherPackages(candidates, applicationContext.packageName).also {
            entryPoint.nudgeLogger().d("launcher packages resolved packages=$it")
        }
    } catch (e: Exception) {
        entryPoint.nudgeLogger().w("failed to resolve launcher packages", e)
        emptySet()
    }

    /**
     * The user pressed Home: drop the post-overlay passthrough (and the web-domain equivalent) for
     * whatever app they were in, so re-opening it gets a fresh block.
     *
     * Deliberately touches NOTHING else. Session counts, the auto-kick cooldown and the interaction
     * tracker's 5-minute session-expiry semantics all treat a quick trip home as the SAME sitting on
     * purpose (a tab-out-and-back must not refill a time budget), and that stays true — leaving the
     * app revokes permission to skip the delay, it does not end the session.
     *
     * **Both passthrough axes are already gone by the time this runs.** `ForegroundSignal.Home` is
     * one of only two signals [com.astraedus.nudge.domain.sitting.SittingTracker] allows to end a
     * sitting, and the single `applyForegroundSignal` call at the top of [onAccessibilityEvent] revokes the
     * grant — app axis and web axis together — when it does. Clearing again here would be the "a
     * second piece of state means a second thing to remember to clear" trap that
     * `docs/architecture/foreground-detection.md` documents, and which left the web grant alive
     * across Home until v1.15.2. What remains is the web CLOCK, which is not a grant.
     */
    private fun onWentHome(packageName: String) {
        entryPoint.nudgeLogger().d(
            "sitting ended by home screen — passthrough revoked (both axes) package=$packageName"
        )
        endWebSession("went_home")
    }

    private fun refreshCurrentImePackage() {
        currentImePackage = try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )?.substringBefore('/')?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // The bind has just completed. Anything displaying protection state read the settings
        // string BEFORE this moment and saw "granted but not connected", i.e. crashed, so tell it
        // to look again. See AccessibilityConnectionSignal for the latch this deletes.
        AccessibilityConnectionSignal.onConnectionChanged()

        // A bind ends a gap we could not observe, so the AWAY CLOCK is meaningless -- it was timing
        // an interval whose end we did not see. The grant is a different question, and dropping it
        // here was wrong: `docs/BACKLOG.md` records this service churning and reconnecting on this
        // device under memory pressure, so a revoke-on-bind re-blocks a user who never left their
        // app. That is issue #28's own defect, reintroduced by its own fix.
        //
        // Same "unverifiable means do nothing" call this service makes for a null active window and
        // an unresolved launcher set, with the same failure direction: miss a revoke rather than
        // interrupt someone mid-use.
        entryPoint.passthroughManager().onObservationResumed()
        // The launch guard's claim about what is in front was made BEFORE a gap we did not observe,
        // so it is not a claim any more. Dropping it is not the same call as the sitting's above:
        // "no evidence" here means the gate never suppresses, which is the fail-toward-enforcement
        // direction, whereas dropping the sitting would revoke a grant and re-block someone who
        // never left. Same gap, opposite safe answers.
        entryPoint.blockLaunchGuard().reset()
        entryPoint.passthroughManager().setSittingReaction(::onSittingEvent)

        entryPoint.counterOverlayManager().setServiceContext(this)
        entryPoint.timeRemainingOverlayManager().setServiceContext(this)
        entryPoint.tabCoverOverlayManager().setServiceContext(this)

        val passthrough = entryPoint.passthroughManager()
        passthroughManagerInstance = passthrough

        timeRemainingHandler = TimeRemainingHandler(
            timeRemainingOverlayManager = entryPoint.timeRemainingOverlayManager(),
            usageRepository = entryPoint.usageRepository(),
            preferences = entryPoint.nudgePreferences(),
            counterCache = counterCache,
            passthroughManager = passthrough,
            logger = entryPoint.nudgeLogger(),
            serviceScope = serviceScope,
            // The daily-limit hard block is the fourth block-overlay launch site, and the one most
            // likely to land late: it fires from a 30-second clock tick rather than from a
            // foreground event, so the user can easily be elsewhere by the time the usage read comes
            // back. It used to own a Context and build its own intent, which put a launch outside
            // this service and therefore outside any gate. It goes through [launchBlockOverlay] now,
            // like the other three.
            onTimeLimitExceeded = { limitedPackage, dailyLimitMinutes ->
                launchBlockOverlay(
                    targetPackage = limitedPackage,
                    attributedPackage = limitedPackage
                ) {
                    putExtra(BlockOverlayActivity.EXTRA_BLOCK_MODE, "HARD_BLOCK")
                    putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, limitedPackage)
                    putExtra(BlockOverlayActivity.EXTRA_RULE_NAME, "Daily limit reached")
                    putExtra(BlockOverlayActivity.EXTRA_DAILY_TIME_REMAINING_MS, 0L)
                    putExtra(BlockOverlayActivity.EXTRA_DAILY_LIMIT_MINUTES, dailyLimitMinutes)
                }
                Unit
            }
        )

        // ONE kick path, shared by both auto-kick triggers (interaction count and session time).
        autoKickExecutor = AutoKickExecutor(
            interactionTracker = entryPoint.interactionTracker(),
            counterOverlayManager = entryPoint.counterOverlayManager(),
            counterCache = counterCache,
            logger = entryPoint.nudgeLogger(),
            // Prefer the accessibility global action, exactly as EmergencyPassManager does: a HOME
            // intent is not always honoured from inside another app's task, and a kick that leaves
            // the user sitting in the app they were meant to be removed from is a silent failure.
            goHome = {
                if (!requestGoHome()) {
                    startActivity(
                        Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                }
            }
        )

        autoKickTimeHandler = AutoKickTimeHandler(
            counterCache = counterCache,
            interactionTracker = entryPoint.interactionTracker(),
            usageProvider = entryPoint.usageRepository(),
            logger = entryPoint.nudgeLogger()
        )

        // Both clocks are ForegroundClock instances so a throwing tick cannot silently end either
        // one, and so logcat says when each started, stopped, or died and why.
        foregroundClock = ForegroundClock(
            scope = serviceScope,
            tickIntervalMs = FOREGROUND_TICK_MS,
            logger = entryPoint.nudgeLogger(),
            label = "app"
        )
        webClock = ForegroundClock(
            scope = serviceScope,
            tickIntervalMs = FOREGROUND_TICK_MS,
            logger = entryPoint.nudgeLogger(),
            label = "web"
        )

        // The same time-based auto-kick, for websites. Only the clock differs: a `web:` key has no
        // UsageStatsManager stream of its own, so it reads the browser's.
        webSessionUsageProvider = WebSessionUsageProvider(entryPoint.usageRepository())
        autoKickWebTimeHandler = AutoKickTimeHandler(
            counterCache = counterCache,
            interactionTracker = entryPoint.interactionTracker(),
            usageProvider = webSessionUsageProvider,
            logger = entryPoint.nudgeLogger()
        )

        interactionHandler = InteractionHandler(
            interactionTracker = entryPoint.interactionTracker(),
            counterOverlayManager = entryPoint.counterOverlayManager(),
            inAppDetector = entryPoint.inAppDetector(),
            timeRemainingHandler = timeRemainingHandler,
            counterCache = counterCache,
            logger = entryPoint.nudgeLogger(),
            autoKickExecutor = autoKickExecutor
        )

        entryPoint.nudgeLogger().i("accessibility service connected")

        // Track the active keyboard so its window events are recognised as transient (issue #5),
        // and keep it fresh if the user switches keyboards.
        refreshCurrentImePackage()

        // Which packages are the home screen — needed to tell "the user went home" (clears
        // passthrough) apart from the rest of SYSTEM_PACKAGES (transient, must not clear).
        refreshLauncherPackagesIfStale(System.currentTimeMillis())
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                imeSettingObserver
            )
        } catch (e: Exception) {
            entryPoint.nudgeLogger().w("failed to observe default IME setting", e)
        }

        // Backlog F5: a completed delay must not survive a locked phone. Registered here rather
        // than in the manifest because ACTION_SCREEN_OFF is a protected broadcast the system only
        // delivers to runtime-registered receivers.
        try {
            registerReceiver(screenOffReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (e: Exception) {
            // A failed registration degrades to the OLD behaviour (a grant survives screen-off) —
            // never to a false revoke. Same failure direction as an unresolvable launcher set.
            entryPoint.nudgeLogger().w("failed to register screen-off receiver", e)
        }

        // Keep Strict Mode state cached so the hot accessibility-event path can read it without
        // blocking on DataStore.
        serviceScope.launch {
            entryPoint.nudgePreferences().isStrictModeEnabled.collect { strictModeEnabledCached = it }
        }
        serviceScope.launch {
            entryPoint.nudgePreferences().strictModeChallengeLength.collect {
                strictModeChallengeLengthCached = it
            }
        }

        // Cache the global master toggle so the hot path can gate all enforcement synchronously.
        // On a true→false transition, neutralize any live enforcement state immediately (toggle off
        // = behave as if uninstalled).
        serviceScope.launch {
            entryPoint.nudgePreferences().isGlobalEnabled.collect { enabled ->
                val wasEnabled = globalEnabledCached
                globalEnabledCached = enabled
                if (wasEnabled && !enabled) onGlobalDisabled()
                // The monitor service's existence must track the master toggle, not track whether
                // the phone has rebooted since install. It is also what tells the user when the OS
                // has stopped THIS service, so it has to be running whenever Nudge is on.
                NudgeMonitorService.sync(applicationContext, enabled)
            }
        }

        // Issue #19: which apps we have already explained the picture-in-picture escape for. Cached
        // off-main so the check is synchronous on the event path.
        serviceScope.launch {
            entryPoint.nudgePreferences().pipEscapePromptedPackages.collect { raw ->
                pipEscapePromptedCached = PipEscapeLedger.parse(raw)
            }
        }

        serviceScope.launch {
            counterCache.forceRefresh { loadCounterCacheEntries() }
            entryPoint.nudgeLogger().d("counter cache eagerly populated packages=${counterCache.snapshot().size}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // ONE conversion, at the top, into pure data. Everything below reads the record rather than
        // the framework object, which is what lets a captured device session replay through the same
        // classifier and the same sitting model in a JVM test.
        val record = eventRecordFactory.toRecord(event) ?: return

        // The trace is FIRST, ahead of every gate below, on purpose: the events this method drops
        // (picture-in-picture, our own package, transient windows, system surfaces) are precisely
        // the ones a report like #5 / #7 / #28 turns out to be about, and a capture that could not
        // see them would be a capture of our assumptions rather than of the device.
        eventTrace.record(record)

        // Issue #19: the picture-in-picture explainer is a Nudge screen standing IN FOR the block
        // overlay — the block has not been abandoned, it has been superseded by the screen telling
        // the user why it failed. Re-evaluating behind it would relaunch the block overlay on top of
        // the explainer and log a second `wasBlocked` event for a block the user never re-triggered.
        // It is a short-lived modal the user dismisses in a tap, so swallowing events while it is up
        // costs nothing.
        if (PipEscapeActivity.isActive) return

        // Issue #19, and the root cause of the v1.12.0 field failure. A picture-in-picture window
        // fires accessibility events carrying its app's package, but that app is NOT in front — the
        // user is somewhere else entirely. Every evaluation path below is built on "an event
        // carrying package P means P is the foreground app", so a PiP bubble walks straight into
        // them: YouTube was re-blocked nine times in five minutes and the all-time Blocked count
        // rose by eleven during ONE incident, while the tester was navigating inside Nudge itself.
        //
        // The first attempt at this fix only guarded the branch where a block overlay was live, so
        // it missed the common case entirely — the overlay dismisses (markOverlayInactive) and the
        // orphaned bubble keeps firing events with no block in sight.
        //
        // So the gate is general and sits ahead of everything: a package present only as a PiP
        // window is not the foreground app, and its events drive nothing — no evaluation, no block,
        // no UsageEvent, no overlay bypass, no interaction counting.
        if (record.type.isWindowChange) refreshPipOnlyPackages()

        // CLASSIFY ONCE, AND APPLY THE SITTING ONCE, AHEAD OF EVERY EARLY RETURN BELOW.
        //
        // This ordering is the fix for issue #28 and the guard against the next bug of its family.
        // Every defect this file has shipped -- #5 (a keyboard cleared the passthrough), the Home
        // path (a launcher event returned before the clear), #7 (a re-entry returned before
        // evaluation), #19 (a gate added below a path that needed it), #28 (a picker cleared the
        // passthrough) -- was a branch that returned before something that had to happen. Deriving
        // "what is on screen" separately inside six branches is what made that possible.
        //
        // Now there is exactly one classification and exactly one sitting update, both above every
        // `return` in this method, and `SittingTracker` makes every signal except an app window and
        // Home structurally incapable of ending a sitting. A future early return therefore cannot
        // re-create this bug class: there is nothing left below it to skip.
        // Pinned at source level by `EventDispatchOrderContractTest`.
        if (record.type == A11yEventType.WINDOW_STATE_CHANGED) {
            refreshLauncherPackagesIfStale(System.currentTimeMillis())
        }
        val signal = eventClassifier.classify(
            record = record,
            currentImePackage = currentImePackage,
            launcherPackages = launcherPackagesCached,
            pipOnlyPackages = pipOnlyPackagesCached
        )
        applyForegroundSignal(signal)

        if (signal is ForegroundSignal.PipOnly) {
            return
        }

        // One of OUR OWN awareness overlays (the interaction counter, the time-remaining pill)
        // firing a window or content event. It is drawn OVER the app the user is still sitting in,
        // so it says nothing about what is in front and must drive nothing at all — exactly like
        // a keyboard or a toast, and unlike every other Nudge window.
        //
        // The foreground claim is where this bit (issue #41): `applyForegroundSignal` above has
        // already run, and `BlockLaunchGate.foregroundAfter` leaves the foreground alone for this
        // signal where it would have moved it to Nudge for `OwnUi`. Once it had moved, nothing
        // moved it back while the user sat still in the blocked app, so every 30-second daily-limit
        // tick was refused with DROP_FOREGROUND_MOVED and someone past their limit went unblocked.
        if (signal is ForegroundSignal.AwarenessOverlay) {
            return
        }

        if (isOverlayActive) {
            // If a real app has come to the foreground, the overlay is no longer covering it — the
            // user tabbed out and back into the blocked app, orphaning the overlay in its own task.
            // Clear the stale flag and fall through to normal evaluation so the block re-asserts.
            // (Same-package trailing events within DEBOUNCE_MS are still absorbed downstream, so a
            // genuinely-live overlay doesn't re-fire.) Everything else — the overlay's own window,
            // system windows, content-change churn under a live overlay — is swallowed as before.
            // ...but only once the overlay is genuinely ON SCREEN. Between `startActivity` and the
            // overlay's own `onResume` the blocked app is still starting up underneath and keeps
            // firing window events of its own; reading one of those as a bypass re-evaluated the
            // app and launched a SECOND overlay, writing a second `wasBlocked` row for one entry.
            // Device QA measured `wasBlocked` +25 across 10 launches. See
            // [BlockLaunchGate.isGenuineBypass] for the captured timings.
            if (entryPoint.blockLaunchGuard().isGenuineBypass(record.type, signal)) {
                markOverlayInactive()
                entryPoint.nudgeLogger().i(
                    "block overlay bypassed by foreground switch, re-evaluating package=$packageName"
                )
            } else {
                clearOverlays(applicationContext.packageName, "block_overlay_active")
                return
            }
        }

        if (signal is ForegroundSignal.OwnUi) {
            // ANY window of ours coming forward hides the awareness overlays: the user is looking
            // at Nudge, not at the app they were counting interactions in. Live again as of issue
            // #33 — this predicate compared the class name against the applicationId, which is
            // never its prefix, so for months this line ran and did nothing. The awareness
            // overlays' OWN windows cannot reach it: they classify as `AwarenessOverlay` and
            // returned above, so a pill can no longer order itself hidden.
            if (isOwnAppWindowEvent(event)) {
                clearOverlays(packageName, "own_app_window")
            }
            // Nudge's MAIN app window really is the user somewhere else, so it ends the arrival and
            // the next block for whatever they were in is a fresh confrontation that owes a row
            // (issue #36). Reported from here rather than read off the signal because the
            // classifier calls our block OVERLAY `OwnUi` too -- and the overlay is on screen for
            // every confrontation by construction, so letting it end an arrival would re-open the
            // arrival it belongs to. See [isOwnMainAppWindowEvent] for why this asks by exact
            // class instead of reusing the predicate on the line above.
            if (BlockLaunchGate.isOwnMainAppWindow(record.type, record.className)) {
                entryPoint.blockLaunchGuard().onDeparture("own_app_window")
            }
            return
        }

        // Transient, non-application windows (any soft keyboard, or the `android` framework package
        // that hosts the paste/long-press popups + toasts) must NOT be treated as an app switch.
        // Doing so cleared post-delay passthrough and re-triggered the block on return (issue #5).
        // Ignore the event entirely: don't clear overlays, don't clear passthrough, don't move
        // lastPackage — the real app underneath hasn't changed.
        if (signal is ForegroundSignal.Transient) {
            entryPoint.nudgeLogger().d("ignoring transient non-app window package=$packageName")
            return
        }

        // Strict Mode Phase 2: guard the OS escape routes (Settings → Accessibility toggle,
        // App Info → Force stop / Uninstall) BEFORE the system-surface early-return swallows
        // settings events. Only inspects window content on settings packages and only on window
        // change events; cheap pure checks gate the (more expensive) node-tree read.
        // DELIBERATELY still a package-set membership test, and not the classifier's business.
        // The classifier answers "is this the app the user is in"; this asks "is this one of the OS
        // surfaces from which Nudge can be switched off", which is a different question with a
        // different failure direction -- missing an OEM security-centre package costs a commitment
        // lock, not a spurious re-block. Widening the classifier to carry it would put an
        // enforcement concern inside the thing that decides what is on screen.
        if (packageName in StrictModeEscapeGuard.SETTINGS_PACKAGES && record.type.isWindowChange) {
            maybeGuardSettingsEscape(packageName)
            // fall through to the system-surface handling below (clears any stale counter overlays)
        }

        if (signal is ForegroundSignal.Home || signal is ForegroundSignal.SystemSurface) {
            // Going HOME is the user genuinely leaving the app — and it is the exit path this
            // early-return used to swallow, so a completed delay never re-armed for it. The GRANT
            // itself was already revoked above, by the one sitting update: `ForegroundSignal.Home`
            // is one of only two signals that can end a sitting, so there is no second piece of
            // state to remember to clear here. Every other system surface (shade, permission
            // dialog, installer, the Pixel's volume panel) is transient and ends nothing.
            val home = signal is ForegroundSignal.Home
            if (home) onWentHome(packageName)
            // ...and the SAME distinction governs the foreground-time clock. Stopping it for every
            // system surface meant a heads-up notification, a shade pull or a permission dialog
            // silently ended the clock mid-session, and nothing restarted it until the next
            // foreground RE-EVALUATION — which for a browser never arrives from content changes at
            // all. That is how a configured time-based auto-kick could sit minutes past its
            // threshold and never fire, with an empty logcat.
            clearOverlays(packageName, "system_package", stopClocks = home)
            return
        }

        // Global master-toggle gate (Bug 3): when Nudge is disabled, do NO enforcement of any kind —
        // no rule evaluation, no auto-kick cooldown overlay, no auto-kick, no counter/time-remaining
        // overlays, no web-domain/content-filter blocking, no in-app feature detection. This runs on
        // the cached flag so it is synchronous and correct on the hot path (the async globalEnabled
        // reads deeper in the pipeline were bypassed by the cooldown block that fired before them).
        // The Strict Mode escape guard above is intentionally independent — it is a commitment lock,
        // not app-blocking enforcement.
        if (!globalEnabledCached) {
            hideAllOverlays()
            return
        }

        refreshCounterCacheIfNeeded()

        // Exhaustive over the event types this service is registered for. `OTHER` is listed rather
        // than swept into an `else` so that registering for a new type in
        // `accessibility_service_config.xml` forces a decision here instead of silently doing
        // nothing — which is how an event type can be "handled" for a year without being handled.
        when (record.type) {
            A11yEventType.WINDOW_STATE_CHANGED,
            A11yEventType.WINDOWS_CHANGED -> evaluateForegroundPackage(packageName)

            A11yEventType.WINDOW_CONTENT_CHANGED -> handleWindowContentChanged(record)

            A11yEventType.VIEW_CLICKED,
            A11yEventType.VIEW_SCROLLED -> {
                // A click we performed ourselves (the Following steer) is reported by the platform
                // exactly like a finger. Counting it would inflate the number this overlay exists to
                // make honest -- issue #28's mistake from the other direction, with the blocker as
                // the phantom user. Scrolls are never ours, so only clicks consult the window.
                val ours = record.type == A11yEventType.VIEW_CLICKED &&
                    syntheticClicks.shouldSuppress(android.os.SystemClock.elapsedRealtime())
                if (ours) {
                    entryPoint.nudgeLogger().d("interaction ignored reason=our_own_click")
                } else {
                    interactionHandler.handleInteraction(record) {
                        eventRecordFactory.readSourceViewId(event)
                    }
                }
            }

            A11yEventType.OTHER -> Unit
        }
    }

    /**
     * Feed the ONE classification to everything that consumes it, once per event, above every early
     * return.
     *
     * There are exactly three consumers and they must never disagree:
     *
     *  - [PassthroughManager] owns *is the user still in a sitting with app X* (issue #28).
     *    Everything it does is inside that class deliberately: the grant and the sitting that owns
     *    it cannot be updated out of step, because there is one call that does both. What is left
     *    here is the *reaction*, the log, the counter's per-source state, and the web clock.
     *  - [BlockLaunchGuard] owns *what is in front right now, and is a departure in flight*
     *    (issues #31 and #26). It is fed here, from the same signal, rather than at the branches
     *    that happen to care: a branch-local update is precisely the shape that produced #5, #7,
     *    #19 and #28, each of which was a path that returned before something that had to happen.
     *  - [TabCoverOverlayManager] owns *is the cover over the Reels tab still over the app it was
     *    drawn for*. It is here for the same reason and not in the six branches that each mean "the
     *    user is somewhere else": a cover left behind is a black rectangle floating over the
     *    launcher, and there is no branch you can add one hide to that covers all of them. It
     *    delegates the whole decision to `TabCoverPresence`, which is exhaustive over
     *    [ForegroundSignal] with no `else`, so a signal added later cannot be forgotten here.
     *
     * This method used to be called `applySitting`, which was accurate when the sitting was the only
     * consumer. A name that describes one of two consumers is how the next person adds the third
     * one somewhere else -- and the third one did arrive, right here.
     */
    private fun applyForegroundSignal(signal: ForegroundSignal) {
        entryPoint.blockLaunchGuard().onForegroundSignal(signal)
        entryPoint.passthroughManager().onForegroundSignal(signal, sittingClock())
        entryPoint.tabCoverOverlayManager().onForegroundSignal(signal)
    }

    /**
     * The clock the sitting's return window is measured on.
     *
     * `SystemClock.elapsedRealtime()` rather than wall time: the window decides whether a grant
     * survives, and an epoch clock can jump. An NTP correction or a manual time change mid-sub-flow
     * would revoke a pass the user earned thirty seconds ago, or extend one indefinitely by moving
     * the clock backwards -- and the second of those is a bypass anyone could trigger from the
     * Settings app. Monotonic since boot, unaffected by both, and it counts while the device sleeps,
     * which is what "how long have they been away" means.
     */
    private fun sittingClock(): Long = android.os.SystemClock.elapsedRealtime()

    /**
     * React to every sitting transition, wherever it came from.
     *
     * Registered on `PassthroughManager` rather than called after each mutation, because a grant is
     * earned in `BlockOverlayActivity` and used to move the sitting with nobody listening.
     */
    private fun onSittingEvent(event: SittingEvent) {
        when (event) {
            is SittingEvent.Unchanged -> return
            is SittingEvent.Ended -> logSittingEnded(event)
            is SittingEvent.Started -> {
                event.ended?.let(::logSittingEnded)
                entryPoint.nudgeLogger().i("sitting started package=${event.packageName}")
            }
        }
        // A new sitting means a new screen: scroll sources from the old one must not be able to
        // count, or to hold the primary-source election, in the new one.
        interactionHandler.onSittingChanged()
    }

    private fun logSittingEnded(ended: SittingEvent.Ended) {
        entryPoint.nudgeLogger().i(
            "sitting ended package=${ended.packageName} cause=${ended.cause} — passthrough revoked"
        )
    }

    private fun isOwnAppWindowEvent(event: AccessibilityEvent): Boolean {
        return shouldClearForOwnPackageEvent(
            eventType = event.eventType,
            className = event.className?.toString(),
            ownClassNamespace = OWN_CLASS_NAMESPACE
        )
    }

    /**
     * Strict Mode Phase 2: if the user has landed on a protected Settings escape route (the Nudge
     * accessibility-service toggle, or Nudge's App Info / Force-stop / Uninstall page), intercept
     * with the unlock challenge. Reads the foreground node tree to harvest visible text, runs the
     * pure [StrictModeEscapeGuard.shouldGuardSettingsScreen] matcher, and launches
     * [StrictModeGuardActivity] on a match.
     *
     * Safety: Strict Mode OFF or an active grace window short-circuits inside the matcher; the
     * node read is wrapped so an exception can never crash the service or trap the user.
     */
    private fun maybeGuardSettingsEscape(packageName: String) {
        // Cheap, non-blocking gates BEFORE touching the node tree (the expensive part). State is
        // read from the cached flags so this hot path never blocks on DataStore.
        if (!strictModeEnabledCached) return
        val escapeManager = entryPoint.strictModeEscapeManager()
        if (escapeManager.isWithinGrace()) return
        // The guard overlay is itself a Nudge activity; don't re-guard while it's up.
        if (StrictModeGuardActivity.isActive) return

        val now = System.currentTimeMillis()
        if ((now - lastStrictGuardLaunchTime) < DEBOUNCE_MS) return

        val windowText = harvestWindowText()
        if (windowText.isEmpty()) return

        val shouldGuard = StrictModeEscapeGuard.shouldGuardSettingsScreen(
            foregroundPkg = packageName,
            windowText = windowText,
            appLabel = ownAppLabel,
            strictEnabled = true,
            withinGrace = false
        )
        if (!shouldGuard) return

        lastStrictGuardLaunchTime = now
        entryPoint.nudgeLogger().i("strict mode: guarding settings escape screen package=$packageName")

        val intent = Intent(applicationContext, StrictModeGuardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(StrictModeGuardActivity.EXTRA_CHALLENGE_LENGTH, strictModeChallengeLengthCached)
        }
        applicationContext.startActivity(intent)
    }

    /**
     * Concatenate the visible text + content descriptions of the foreground window's node tree into
     * one lowercase-able blob for the escape matcher. Bounded traversal (≤ [MAX_NODES_SCANNED]) so a
     * pathological tree can't stall the service. Returns "" on any failure (fail closed: no guard).
     */
    private fun harvestWindowText(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return ""
        val sb = StringBuilder()
        var scanned = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        try {
            while (queue.isNotEmpty() && scanned < MAX_NODES_SCANNED) {
                val node = queue.removeFirst()
                scanned++
                node.text?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
                node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (_: Exception) {
            return ""
        }
        return sb.toString()
    }

    private fun evaluateForegroundPackage(packageName: String) {
        val now = System.currentTimeMillis()
        val passthrough = entryPoint.passthroughManager()

        val grayscalePkg = grayscaleActiveForPackage
        if (grayscalePkg != null && grayscalePkg != packageName) {
            entryPoint.grayscaleManager().disableGrayscale()
            grayscaleActiveForPackage = null
        }

        // Leaving a browser stops the web session's CLOCK — the user is no longer looking at the
        // site, and the clock restarts by itself on return.
        //
        // It deliberately does NOT drop the web grant any more. That was issue #28 one axis over:
        // opening a photo picker or a share sheet from Chrome is not a browser package, so this
        // branch fired and the completed delay for the SITE was revoked exactly as the app-level one
        // was. Both axes now expire with the sitting (`PassthroughManager.clear()` drops both), and
        // a genuine navigation away is still caught by `WebDomainGate.Action.EVALUATE` below, which
        // is the check that actually knows the domain changed.
        if (entryPoint.webDomainDetector().isBrowser(lastPackage ?: "") &&
            !entryPoint.webDomainDetector().isBrowser(packageName)
        ) {
            endWebSession("left_the_browser")
        }

        if (!counterCache.hasEntry(packageName)) {
            clearOverlays(packageName, "counter_disabled", markForeground = false)
        } else if (packageName != lastPackage) {
            // The label is NOT cleared here any more. It belongs to the session (InteractionTracker
            // owns it) and a foreign package reaching this function is usually a sub-flow the user
            // will return from -- a picker, a share sheet. Wiping it dropped a correctly-detected
            // "reels" caption back to the generic word for the rest of the visit, until detection
            // happened to fire again. The session's own reset paths clear it.
            interactionHandler.onAppChanged(packageName)
            timeRemainingHandler.resetDebounce()
        }

        // Start/stop the foreground-time clock for this app. Deliberately BEFORE the emergency-pass,
        // cooldown and passthrough early-returns below: a user who has just completed a delay is
        // exactly who a time-based auto-kick is for, and their minutes must keep accruing.
        updateForegroundTimeTicker(packageName)

        // Emergency "2-minute daily pass": while a free window is open for this app, let it through —
        // overriding normal evaluation AND any auto-kick cooldown (placed before the cooldown block so
        // the user gets genuinely free use). The window is per-app; the lockout it recorded is global.
        // At expiry the manager kicks home and the next foreground event re-blocks normally as a
        // backstop.
        if (entryPoint.emergencyPassManager().isPassActive(packageName)) {
            entryPoint.nudgeLogger().d("skip evaluation package=$packageName reason=emergency_pass")
            if (counterCache.isCounterEnabled(packageName) && !interactionHandler.isCounterVisible()) {
                interactionHandler.onAppChanged(packageName)
            }
            return
        }

        val tracker = entryPoint.interactionTracker()
        // The ONE enforcement path in this service that runs before any rule is looked up, and the
        // only one that writes no UsageEvent. Its authority must be derived from a rule that still
        // exists, never remembered from one that used to: delete a rule (or turn its auto-kick off)
        // with a cooldown armed and the in-memory map would keep ejecting the user from an app
        // nothing is configured to block, invisibly, for the whole cooldown. See [CooldownGate].
        val hasRuleEntry = counterCache.hasEntry(packageName)
        if (CooldownGate.isStale(hasRuleEntry, tracker.isInCooldown(packageName))) {
            entryPoint.nudgeLogger().i(
                "stale auto-kick cooldown dropped package=$packageName reason=no_rule_entry"
            )
            tracker.clearCooldown(packageName)
        }
        if (CooldownGate.shouldEnforce(hasRuleEntry, tracker.isInCooldown(packageName))) {
            val remainingMs = tracker.getCooldownRemainingMs(packageName)
            val remainingSeconds = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(1)
            entryPoint.nudgeLogger().i(
                "cooldown enforced package=$packageName remaining=${remainingSeconds}s"
            )
            launchBlockOverlay(targetPackage = packageName, attributedPackage = packageName) {
                putExtra(BlockOverlayActivity.EXTRA_BLOCK_MODE, "DELAY")
                putExtra(BlockOverlayActivity.EXTRA_DELAY_SECONDS, remainingSeconds)
                putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(BlockOverlayActivity.EXTRA_RULE_NAME, "Auto-kick cooldown")
            }
            return
        }

        // Show time remaining overlay before passthrough check (awareness overlays always show)
        timeRemainingHandler.showIfNeeded(packageName)

        if (passthrough.shouldSkipForegroundEvaluation(packageName)) {
            entryPoint.nudgeLogger().d("skip evaluation package=$packageName reason=passthrough")
            // Ensure counter is visible post-delay (onAppChanged may not re-fire)
            if (counterCache.isCounterEnabled(packageName) && !interactionHandler.isCounterVisible()) {
                interactionHandler.onAppChanged(packageName)
            }
            return
        }

        // THE LINE THAT ISSUE #28 WAS: `passthrough.clearIfAppChanged(packageName)` used to sit
        // here, so ANY foreign package reaching this function revoked the user's completed delay.
        // A photo picker, a share sheet, the Pixel's `com.google.android.permissioncontroller`
        // dialog and an OEM volume panel are all ordinary app packages, so all of them re-blocked
        // the user on return, and no list would ever have contained them all. Revocation now belongs
        // to the sitting (see `applyForegroundSignal`), which a sub-flow cannot end. Nothing replaces it here
        // — a stale grant still cannot let the wrong app through, because
        // `shouldSkipForegroundEvaluation` compares against the granted package.

        if (packageName == lastPackage && (now - lastEvalTime) < DEBOUNCE_MS) {
            return
        }

        entryPoint.nudgeLogger().i("foreground evaluation package=$packageName")
        lastPackage = packageName
        lastEvalTime = now

        serviceScope.launch {
            val globalEnabled = entryPoint.nudgePreferences().isGlobalEnabled.first()
            if (!globalEnabled) return@launch

            // For browsers, evaluate web domain blocking instead of (or alongside) app blocking
            if (entryPoint.webDomainDetector().isBrowser(packageName)) {
                evaluateWebDomain(packageName)
            } else {
                val decision = entryPoint.evaluateBlockUseCase().invoke(packageName)
                entryPoint.nudgeLogger().d("whole-app decision package=$packageName decision=$decision")
                handleDecision(decision, packageName)
            }
        }
    }

    private suspend fun evaluateWebDomain(browserPackage: String) {
        val rootNode = withContext(Dispatchers.Main) {
            try { rootInActiveWindow } catch (_: Exception) { null }
        }
        val urlBarText = entryPoint.webDomainDetector().detectUrl(rootNode, browserPackage)

        if (urlBarText.isNullOrBlank()) {
            entryPoint.nudgeLogger().d("web domain: no URL detected in browser")
            return
        }

        val extractedDomain = WebDomainMatcher.extractDomain(urlBarText)
        val passthrough = entryPoint.passthroughManager()

        when (WebDomainGate.decide(extractedDomain, passthrough.lastDomain)) {
            // The URL bar was readable but held no domain (a page title, a search query, a
            // half-typed address, an internal scheme). Unverifiable means DO NOTHING -- the old code
            // treated it as "a different domain" and revoked a live pass mid-visit, re-blocking a
            // user who had not gone anywhere. Same call the issue-#7 fallback makes for a null
            // active window.
            WebDomainGate.Action.UNREADABLE -> {
                entryPoint.nudgeLogger().d("web domain: url=$urlBarText yields no domain — ignoring")
                return
            }

            // Still on the site whose block the user completed. The SESSION bookkeeping below the
            // `when` must still run: this is the branch the user spends their whole visit in, and
            // everything that measures that visit used to sit AFTER this return, which is why a
            // blocked website tracked nothing at all once you were on it.
            WebDomainGate.Action.PASSTHROUGH -> {
                onWebDomainForeground(browserPackage, extractedDomain)
                entryPoint.nudgeLogger().d(
                    "web domain: passthrough for already-blocked domain=$extractedDomain"
                )
                return
            }

            WebDomainGate.Action.EVALUATE -> passthrough.clearWebGrant()
        }

        onWebDomainForeground(browserPackage, extractedDomain)

        // The 2-minute daily pass is scoped to the app the user is IN -- the browser -- exactly as
        // the overlay grants it. Checked here for the same reason evaluateForegroundPackage checks
        // it: without this, taking the escape hatch on a website re-blocked on the next event.
        if (entryPoint.emergencyPassManager().isPassActive(browserPackage)) {
            entryPoint.nudgeLogger().d("web domain: skip evaluation reason=emergency_pass")
            return
        }

        if (enforceWebCooldown(browserPackage, extractedDomain)) return

        val result = entryPoint.evaluateBlockUseCase().evaluateWebDomain(urlBarText)
        entryPoint.nudgeLogger().d("web domain: url=$urlBarText decision=${result.decision}")

        when (result.decision) {
            is BlockDecision.Block -> {
                // The pass is EARNED, in BlockOverlayActivity.onTimerComplete, like every other
                // grant in this app. It used to be handed over here, before the overlay had even
                // been shown, so walking away from a website's delay (or tabbing out of it) let the
                // site through anyway. HARD_BLOCK has no completion path and so can never grant.
                handleDecision(
                    decision = result.decision,
                    packageName = result.trackingPackage ?: browserPackage,
                    web = WebBlockContext(browserPackage, extractedDomain)
                )
            }
            is BlockDecision.Allow -> {
                // Not blocked -- nothing to do
            }
        }
    }

    /**
     * Record that [domain] is the website in the foreground of [browserPackage], starting or
     * continuing its session.
     *
     * This is the web equivalent of the `counterCache.hasEntry` / `onAppChanged` /
     * `updateForegroundTimeTicker` block at the top of [evaluateForegroundPackage], and it is called
     * from BOTH the passthrough branch and the evaluate branch of [evaluateWebDomain] for the reason
     * that block sits above that function's own early returns: a user who has just completed a delay
     * is exactly who a time-based auto-kick is for, and their minutes must keep accruing.
     *
     * A domain with no cache entry (no rule wants a clock on it) ends any running session, so the
     * ticker only exists while it can do something.
     */
    private suspend fun onWebDomainForeground(browserPackage: String, domain: String?) {
        val key = domain
            ?.let { WebSessionKey.forDomain(it) }
            ?.takeIf { counterCache.getEntry(it) != null }

        if (key == null) {
            endWebSession("not_a_tracked_domain")
            return
        }
        // Cheap guard first: this runs on every debounced content change for the whole visit, and
        // everything below it only has to happen when the domain actually changes.
        if (key == activeWebSessionKey && webClock.isRunning) return

        activeWebSessionKey = key
        webSessionUsageProvider.browserPackage = browserPackage
        // Same session semantics as an app: a short hop away and back CONTINUES the session (a
        // detour must not refill a time budget), a real break restarts it.
        //
        // On Main because InteractionTracker holds plain (non-concurrent) maps and its other
        // structural writer, `interactionHandler.onAppChanged`, always runs on the accessibility
        // event thread. This function runs on the service's IO scope, so without the hop the two
        // would interleave on `currentPackage` / `lastLeftAt` whenever a browser window event and a
        // domain change land together — which is exactly when they both fire.
        withContext(Dispatchers.Main) { entryPoint.interactionTracker().onAppChanged(key) }
        startWebTimeTicker(key)
    }

    /**
     * After a web auto-kick, returning to the same site inside the cooldown gets the same DELAY
     * overlay the app-level cooldown gets. Keyed by DOMAIN, never by the browser package -- a
     * cooldown on `com.android.chrome` would lock every website the user has.
     *
     * @return true when the cooldown overlay was shown and evaluation must stop.
     */
    private fun enforceWebCooldown(browserPackage: String, domain: String?): Boolean {
        val key = domain?.let { WebSessionKey.forDomain(it) } ?: return false
        val tracker = entryPoint.interactionTracker()
        // Same gate as the app-level cooldown, for the same reason: a cooldown keyed on a domain no
        // rule enforces on any more is stale state, and acting on it blocks a site nothing blocks.
        val hasRuleEntry = counterCache.getEntry(key) != null
        if (CooldownGate.isStale(hasRuleEntry, tracker.isInCooldown(key))) {
            entryPoint.nudgeLogger().i(
                "stale web auto-kick cooldown dropped domain=$domain reason=no_rule_entry"
            )
            tracker.clearCooldown(key)
        }
        if (!CooldownGate.shouldEnforce(hasRuleEntry, tracker.isInCooldown(key))) return false

        val remainingMs = tracker.getCooldownRemainingMs(key)
        val remainingSeconds = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(1)
        entryPoint.nudgeLogger().i(
            "web cooldown enforced domain=$domain remaining=${remainingSeconds}s"
        )
        launchBlockOverlay(targetPackage = browserPackage, attributedPackage = browserPackage) {
            putExtra(BlockOverlayActivity.EXTRA_BLOCK_MODE, "DELAY")
            putExtra(BlockOverlayActivity.EXTRA_DELAY_SECONDS, remainingSeconds)
            putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, browserPackage)
            putExtra(BlockOverlayActivity.EXTRA_PASSTHROUGH_PACKAGE, browserPackage)
            putExtra(BlockOverlayActivity.EXTRA_WEB_DOMAIN, domain)
            // Named with the site, not just "Auto-kick cooldown": the overlay's app label resolves
            // to the BROWSER here (it is the package we are blocking re-entry to), and "Chrome" on
            // its own would not tell the user which site they were just removed from.
            putExtra(BlockOverlayActivity.EXTRA_RULE_NAME, "Auto-kick cooldown — $domain")
        }
        // True regardless of whether the overlay was actually shown: the cooldown IS in force, so
        // evaluation must stop either way. Falling through to a rule lookup because the gate
        // refused would evaluate a site the user has already navigated away from.
        return true
    }

    /**
     * Start (or keep) the 30s web foreground-time clock for [key].
     *
     * Idempotent per key for the same reason [updateForegroundTimeTicker] is: this is re-entered on
     * every debounced content change while the user browses, and restarting the job each time would
     * keep resetting the `delay` so the clock would never tick.
     */
    private fun startWebTimeTicker(key: String) {
        webClock.start(key) { tickWebTime(it) }
    }

    /** End the current web session: no domain is in front, so nothing should be on its clock. */
    private fun endWebSession(reason: String) {
        if (activeWebSessionKey == null && !webClock.isRunning) return
        activeWebSessionKey = null
        webSessionUsageProvider.browserPackage = null
        webClock.stop(reason)
    }

    /**
     * One pass of the web foreground-time clock. Mirrors [tickForegroundTime]: re-check the master
     * toggle and the emergency pass (a timer is not covered by the synchronous event gate), then
     * feed the same [AutoKickTimeHandler] / [AutoKickExecutor] the app path uses.
     */
    private suspend fun tickWebTime(key: String) {
        if (!globalEnabledCached) return
        if (activeWebSessionKey != key) return
        val browser = webSessionUsageProvider.browserPackage ?: return
        if (entryPoint.emergencyPassManager().isPassActive(browser)) return

        if (!autoKickWebTimeHandler.shouldKick(key)) return

        withContext(Dispatchers.Main) {
            autoKickExecutor.kick(key, reason = "web session time")
        }
        // The kick is only real if the site re-blocks on return: leaving the completed-delay pass
        // in place would put the user straight back on the page they were just removed from.
        entryPoint.passthroughManager().clearWebGrant()
        endWebSession("auto_kicked")
    }

    /** Identifies a block that happened on a website rather than in an app. */
    private data class WebBlockContext(val browserPackage: String, val domain: String?)

    /**
     * @param stopClocks whether the user has genuinely stopped looking at the app being clocked.
     *   **Not the same question as "should the awareness overlays go away"**, and conflating the two
     *   is what made the time-based auto-kick unreliable in the field: this function is reached from
     *   the `SYSTEM_PACKAGES` branch, which fires for the notification shade, a permission dialog,
     *   the installer and the launcher alike — and it killed the foreground-time clock for every one
     *   of them. A shade pull or a heads-up notification does NOT mean the user left the app, but it
     *   left the clock stopped until the next foreground *re-evaluation*, which for a browser never
     *   arrives from content changes at all. The minutes then simply stopped accruing, silently.
     *
     *   This is the same grouped-constant trap `SYSTEM_PACKAGES` already sprang on the passthrough
     *   grant (see `docs/architecture/foreground-detection.md`): one membership test answering two
     *   different questions. The launcher branch already knows how to tell "went home" from
     *   "transient", so the clock now uses that answer instead of stopping for all of them.
     */
    private fun clearOverlays(
        packageName: String,
        reason: String,
        markForeground: Boolean = true,
        stopClocks: Boolean = true
    ) {
        if (markForeground) {
            lastPackage = packageName
        }
        interactionHandler.onAppChanged(packageName)
        if (stopClocks) {
            stopForegroundTimeTicker(reason)
            // The web clock is separate precisely so a browser's own events don't tear it down —
            // a browser IS in front while a blocked site is open. Everything else (our own overlay,
            // another app) means the user is no longer looking at that site.
            if (!entryPoint.webDomainDetector().isBrowser(packageName)) endWebSession(reason)
        }

        try {
            interactionHandler.hideCounter()
            timeRemainingHandler.hide()
        } catch (e: Exception) {
            entryPoint.nudgeLogger().w("overlay clear failed package=$packageName", e)
        }
    }

    /**
     * Hide the awareness overlays (interaction counter + time-remaining). Called on the accessibility
     * hot path (already the main thread) when Nudge is globally disabled, so no stale overlay lingers.
     */
    private fun hideAllOverlays() {
        stopForegroundTimeTicker("globally_disabled")
        endWebSession("globally_disabled")
        try {
            if (::interactionHandler.isInitialized) interactionHandler.hideCounter()
            if (::timeRemainingHandler.isInitialized) timeRemainingHandler.hide()
            // The cover is enforcement, so it goes with the rest of it: a globally-disabled Nudge
            // must behave as if uninstalled, and a screen nobody is looking at owes no cover either.
            entryPoint.tabCoverOverlayManager().hide()
            // The steer's "already done this arrival" is about a visit that has now ended.
            followingSteer.reset()
            syntheticClicks.reset()
        } catch (e: Exception) {
            entryPoint.nudgeLogger().w("overlay hide-all failed", e)
        }
    }

    /**
     * React to the master toggle flipping OFF: neutralize all active enforcement state so a disabled
     * Nudge behaves as if uninstalled. Clears auto-kick cooldowns, cancels any emergency-pass windows
     * and their scheduled home-kicks, and tears down awareness overlays (posted to the main thread —
     * this runs on the collector's IO scope). New events are already gated by [globalEnabledCached].
     */
    private fun onGlobalDisabled() {
        // ALL of this on Main, not just the sitting reset. This runs on the isGlobalEnabled
        // collector, which lives on serviceScope (Dispatchers.IO), and `InteractionTracker` holds
        // the same plain non-concurrent maps that the accessibility thread reads on the hot path
        // (`isInCooldown` on every foreground evaluation). Hopping only the newest of the three
        // calls would have left the two older ones racing, which is how this kind of thing survives
        // a fix.
        serviceScope.launch(Dispatchers.Main) {
            entryPoint.interactionTracker().clearAllCooldowns()
            entryPoint.emergencyPassManager().cancelAll()
            // A disabled Nudge must behave as if uninstalled, and a live sitting is enforcement
            // state: leaving one standing would mean the first app re-enabling Nudge finds itself
            // mid-sitting with a grant it never earned in this session.
            //
            // ON MAIN, not on this collector's IO scope. `SittingTracker` holds plain fields that
            // `onAccessibilityEvent` writes from the main thread, so an IO-thread reset would race
            // them — the exact hop `onWebDomainForeground` already makes for `InteractionTracker`,
            // and which that class's own doc requires.
            entryPoint.passthroughManager().resetSitting()
            // Same reason, and the same direction: whatever the guard believed is in front was
            // observed under a Nudge that is now off, and a stale claim could suppress the FIRST
            // block after the user switches back on. "No claim" never suppresses anything.
            entryPoint.blockLaunchGuard().reset()
            hideAllOverlays()
        }
    }

    private fun handleWindowContentChanged(record: AccessibilityEventRecord) {
        val packageName = record.packageName
        // For browsers, content changes may indicate URL navigation -- re-evaluate web domain
        if (entryPoint.webDomainDetector().isBrowser(packageName)) {
            val now = System.currentTimeMillis()
            val lastTime = lastContentChangedTime[packageName] ?: 0L
            if ((now - lastTime) < contentChangedDebounceMs) return
            lastContentChangedTime[packageName] = now

            serviceScope.launch {
                val globalEnabled = entryPoint.nudgePreferences().isGlobalEnabled.first()
                if (!globalEnabled) return@launch
                evaluateWebDomain(packageName)
            }
            return
        }

        // THE STEER'S FAST PATH, and it sits ABOVE the debounce deliberately.
        //
        // `detectAndEvaluateFeature` is debounced to `contentChangedDebounceMs` (2s) because a tree
        // read is expensive and these events arrive in bursts. That debounce made
        // `SteerAction.ClickFollowing` UNREACHABLE on a real device: we click the dropdown, the menu
        // opens ~250ms later and fires a burst of content changes, and every one of them is swallowed
        // until 2s have passed -- by which time the attempt had already aged out. Nudge opened
        // Instagram's dropdown and then never clicked anything, leaving it hanging open over the feed
        // until the user pressed back. The state machine was correct in isolation and every unit test
        // passed, because the tests drive `onObservation` directly and the CADENCE is what was wrong.
        //
        // Cost is bounded to the ~250ms between our click and the menu appearing, once per home-feed
        // arrival, and only while an attempt is actually in flight.
        if (followingSteer.isMenuPending) completePendingSteer(packageName)

        if (packageName !in InAppDetector.SUPPORTED_PACKAGES) {
            // Issue #7: a re-entry the OS delivers WITHOUT a TYPE_WINDOW_STATE_CHANGED (recents
            // overview, notification tap) would otherwise never be evaluated for this package —
            // only SUPPORTED_PACKAGES fell through to evaluation below. Verify against the real
            // active window before treating it as a switch (see the pure decision function), and
            // throttle the attempt: evaluation early-returns (emergency pass, passthrough) leave
            // lastPackage untouched, so without this the node-tree read would repeat on every
            // content change for the whole of that window.
            maybeEvaluateContentChangeAsAppSwitch(record)

            // What used to be here: `interactionHandler.handleContentChanged(packageName)`, which
            // counted one "tap" per second of content change for any package outside
            // SUPPORTED_PACKAGES, as a proxy for input in React Native apps. It is gone. A content
            // change is not input — a captured Instagram session with ZERO gestures produced a
            // steady stream of them (issue #28), so the proxy counted autoplaying video as taps and
            // fed that to auto-kick. Those apps now get a real count from their scroll events
            // instead, and where we genuinely cannot measure, the counter reads zero rather than
            // making a number up.
            return
        }

        evaluateForegroundPackage(packageName)
        detectAndEvaluateFeature(packageName)
    }

    /**
     * Inspect the foreground tree for an in-app feature (Reels / Shorts / TikTok feed) and block if
     * a feature rule matches. Debounced per package via [lastContentChangedTime] — the tree read is
     * the expensive part and these events arrive in bursts.
     *
     * Driven by `TYPE_WINDOW_CONTENT_CHANGED`, which is plentiful inside these apps — a device
     * capture measured ~26k content-change events against ~1.8k scrolls during a few minutes of
     * Instagram use, of which ~800 detection attempts survived the debounce. Detection opportunity
     * has never been the bottleneck; recognising the surface is.
     */
    private fun detectAndEvaluateFeature(packageName: String) {
        val now = System.currentTimeMillis()
        val lastTime = lastContentChangedTime[packageName] ?: 0L
        if ((now - lastTime) < contentChangedDebounceMs) return
        lastContentChangedTime[packageName] = now

        val rootNode = try { rootInActiveWindow } catch (_: Exception) { null } ?: return

        // Tab Vanish and the Following steer ride THIS tree read rather than adding one. Both are
        // node-tree questions about the same instant, the read is the expensive part on this path
        // (a device capture measured ~26k content-change events in a few minutes of Instagram use),
        // and asking them here means they inherit the same debounce the detector already pays for.
        maintainHostSurfaces(packageName, rootNode)

        val feature = entryPoint.inAppDetector().detectFeature(packageName, rootNode)

        // TOLD FIRST, AND TOLD EVEN WHEN THE ANSWER IS NULL. This used to be
        // `detectFeature(...) ?: return`, which meant `noteDetectedFeature` was only ever reached
        // with a RECOGNISED feature -- so the null branch that invalidates a stale caption was
        // unreachable from production, and the caption stayed stuck to whatever surface last set it.
        // Device-reproduced 2026-09-12: YouTube opens on Shorts, the user taps the Home tab, and
        // every item counted on the feed is still captioned "shorts". A guard clause that skips
        // telling someone the answer is not a guard clause, it is a dropped message.
        //
        // The counter's LABEL is the only thing detection still owes it. It used to owe it
        // permission to count at all, which is why the counter has never worked on surfaces we
        // cannot recognise (`docs/BACKLOG.md`: "counter doesn't increment on YouTube swipes"). This
        // reuses the tree read that was already happening here rather than adding one.
        interactionHandler.noteDetectedFeature(packageName, feature)
        if (feature == null) return

        val passthrough = entryPoint.passthroughManager()

        if (passthrough.shouldSkipFeatureEvaluation(packageName, feature.key)) return

        serviceScope.launch {
            val globalEnabled = entryPoint.nudgePreferences().isGlobalEnabled.first()
            if (!globalEnabled) return@launch

            val decision = entryPoint.evaluateBlockUseCase().invoke(
                packageName = packageName,
                detectedFeature = feature.key,
                includeWholeAppRulesForFeature = !passthrough.shouldSkipForegroundEvaluation(packageName)
            )
            handleDecision(decision, packageName, feature.key)
        }
    }

    /**
     * Maintain the two IN-HOST-APP surfaces for [packageName]: the Reels tab cover (Tab Vanish) and
     * the Following steer. Called from [detectAndEvaluateFeature] with the tree read it already did.
     *
     * ## Everything the node tree is asked is asked HERE, synchronously, before anything suspends
     *
     * Both features need a database read (the block decision, the per-rule steer toggle) and an
     * [AccessibilityNodeInfo] is only valid while its window is. So this function converts the tree
     * into PURE VALUES on the event thread -- a classified [HostSurface], a boolean, a
     * [TabCoverPlacement] -- and everything past the `launch` reasons about those values. It is the
     * same boundary `AccessibilityEventRecord` draws for the event pipeline, and for the same
     * reason: what crosses a suspend point has to be data, not a handle.
     *
     * The one exception is the steer's tap, which needs a live node and therefore re-reads the tree
     * on the main thread -- but only on the rare tick where a tap is actually due (once per arrival),
     * never in the steady state.
     */
    private fun maintainHostSurfaces(packageName: String, rootNode: AccessibilityNodeInfo) {
        val surfaces = PlatformSurfacesRegistry.forPackage(packageName) ?: return

        val executor = entryPoint.followingSteerExecutor()
        val surface = surfaces.classify(executor.observe(rootNode, surfaces))
        val menuVisible = surfaces.followingSteer?.let { executor.isMenuVisible(rootNode, it) } ?: false

        // The tab we would cover, if any is on screen at all. Absent inside the reel player, a
        // story, a DM thread and the Following screen -- all of which drop the nav bar entirely.
        val vanishable = surfaces.vanishableTabs.entries.firstNotNullOfOrNull { (feature, locator) ->
            HostNodeFinder.findPlacement(rootNode, locator)?.let { feature to it }
        }

        serviceScope.launch {
            if (!entryPoint.nudgePreferences().isGlobalEnabled.first()) return@launch
            maintainTabCover(packageName, surfaces, vanishable)
            maintainFollowingSteer(packageName, surfaces, surface, menuVisible)
        }
    }

    /**
     * Draw, move or drop the cover over [packageName]'s vanishable tab.
     *
     * The cover is wanted only where the decision for that FEATURE is a hard block and the deciding
     * rule opted in -- which is what makes Anti's headline behaviour fall out of the existing rule
     * model rather than needing a mechanism of its own: a `NONE` + daily-limit rule evaluates to
     * `HARD_BLOCK` the moment the budget is spent, so the tab goes on its own, on the same tick the
     * limit is crossed, with nothing here that knows what a daily limit is.
     *
     * DELAY / HOLD / BREATHING deliberately do not qualify. Those modes are friction with a choice
     * at the end of it, and the tab has to stay tappable to reach the interstitial -- covering it
     * would remove the choice the mode exists to offer.
     */
    private suspend fun maintainTabCover(
        packageName: String,
        surfaces: PlatformSurfaces,
        vanishable: Pair<String, TabCoverPlacement>?
    ) {
        val cover = entryPoint.tabCoverOverlayManager()

        val requested = vanishable != null && run {
            val decision = entryPoint.evaluateBlockUseCase().invoke(
                packageName = packageName,
                detectedFeature = vanishable.first
            )
            decision is BlockDecision.Block &&
                decision.mode == BlockMode.HARD_BLOCK &&
                decision.tabVanish
        }

        val effect = tabCoverDecider.decide(
            vanishRequested = requested,
            bounds = vanishable?.second,
            shown = cover.shownPlacement()
        )
        if (effect is TabCoverEffect.None) return

        cover.apply(
            packageName = packageName,
            effect = effect,
            color = surfaces.navBarColor(isNightMode()),
            label = coverLabel(vanishable?.first)
        )
    }

    /**
     * Steer [packageName]'s home feed to its Following feed, at most once per arrival.
     *
     * The decision is [FollowingSteer]'s; this function only supplies the observation and performs
     * whatever it asks for. When the per-rule toggle is off the state machine is RESET rather than
     * simply skipped, so switching the toggle on mid-session starts from a clean arrival instead of
     * inheriting an "already steered" that was recorded while the feature was inert.
     */
    private suspend fun maintainFollowingSteer(
        packageName: String,
        surfaces: PlatformSurfaces,
        surface: HostSurface,
        menuVisible: Boolean
    ) {
        val recipe = surfaces.followingSteer ?: return

        if (!entryPoint.evaluateBlockUseCase().isFollowingSteerEnabled(packageName)) {
            followingSteer.reset()
            return
        }

        val action = followingSteer.onObservation(
            surface = surface,
            inHostApp = true,
            menuVisible = menuVisible,
            // Monotonic, for the reason `sittingClock` is: this measures "how long have we been
            // waiting for the menu", and an epoch clock can jump backwards mid-wait.
            nowMs = android.os.SystemClock.elapsedRealtime()
        )
        if (action is SteerAction.None) return

        withContext(Dispatchers.Main) { performSteerAction(action, recipe, packageName) }
    }

    /**
     * Resolve a steer attempt that is ALREADY in flight, off the debounced feature path.
     *
     * Asks only "is the menu up", which is the one thing a caller here can actually vouch for, and
     * lets [FollowingSteer.resolvePending] decide. Main thread, no suspend, no database read: the
     * toggle was already checked when the attempt was started, and re-reading it here would put a
     * Room query on the burst of content changes the opening menu produces.
     */
    private fun completePendingSteer(packageName: String) {
        val surfaces = PlatformSurfacesRegistry.forPackage(packageName) ?: return
        val recipe = surfaces.followingSteer ?: return

        val menuRoot = findMenuRoot(recipe)
        val action = followingSteer.resolvePending(
            menuVisible = menuRoot != null,
            nowMs = android.os.SystemClock.elapsedRealtime()
        )
        if (action is SteerAction.None) return
        performSteerAction(action, recipe, packageName, menuRoot)
    }

    /**
     * The root of whichever window currently holds the dropdown, or null when it is not up.
     *
     * Checks the active window first and then every other window, because a dropdown is a POPUP and
     * we cannot assume which window `rootInActiveWindow` will hand back while one is open -- the
     * recorded device dump of that moment contains the popup alone, with none of the activity's own
     * chrome. Searching one window would make this feature depend on an ordering nobody controls.
     * `flagRetrieveInteractiveWindows` is already set in the service config, so the list is available.
     *
     * Only ever called while an attempt is pending, so the per-window `root` binder reads are paid
     * for a few hundred milliseconds once per home-feed arrival, not on the hot path.
     */
    private fun findMenuRoot(recipe: SteerRecipe): AccessibilityNodeInfo? {
        val executor = entryPoint.followingSteerExecutor()
        try {
            rootInActiveWindow?.let { if (executor.isMenuVisible(it, recipe)) return it }
        } catch (_: Exception) {
            // fall through to the full window sweep
        }
        return try {
            windows.orEmpty().firstNotNullOfOrNull { window ->
                val root = try { window.root } catch (_: Exception) { null }
                root?.takeIf { executor.isMenuVisible(it, recipe) }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Perform one steer action on the main thread, and mark it as OURS so the interaction counter
     * does not attribute our click to the user.
     */
    private fun performSteerAction(
        action: SteerAction,
        recipe: SteerRecipe,
        packageName: String,
        knownMenuRoot: AccessibilityNodeInfo? = null
    ) {
        if (action is SteerAction.CloseMenu) {
            // We opened this dropdown and could not finish; leaving it hanging over the user's feed
            // is a half-performed interaction in someone else's app, which is worse than not acting.
            //
            // BUT THE BACK PRESS IS GUARDED, and this is the important half. `CloseMenu` is emitted
            // on TIMEOUT, i.e. precisely when we could NOT see the menu -- which covers two very
            // different situations: the menu is open and we failed to find it, or the user already
            // dismissed it themselves. Pressing back blindly is only correct in the first. In the
            // second it is a back navigation the user did not ask for, inside their app, possibly
            // out of the feed or out of Instagram entirely. So we look ONE more time, across every
            // window, and press back only with the menu actually in front of us. No evidence, no
            // action: the same fail-toward-doing-nothing this whole feature is built on.
            val stillOpen = findMenuRoot(recipe) != null
            if (!stillOpen) {
                entryPoint.nudgeLogger().i(
                    "following steer gave up, menu already gone package=$packageName"
                )
                return
            }
            val dismissed = try {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
                entryPoint.nudgeLogger().w("following steer menu dismiss failed", e)
                false
            }
            entryPoint.nudgeLogger().i(
                "following steer gave up and closed the menu dismissed=$dismissed package=$packageName"
            )
            return
        }

        val root = knownMenuRoot
            ?: try { rootInActiveWindow } catch (_: Exception) { null }
        if (root == null) {
            entryPoint.nudgeLogger().d("following steer skipped action=$action reason=no_root")
            return
        }

        // BEFORE the click, not after: the accessibility event our own click produces can be
        // delivered before `execute` returns.
        syntheticClicks.onDispatch(android.os.SystemClock.elapsedRealtime())
        val performed = entryPoint.followingSteerExecutor().execute(root, recipe, action)

        // `i`, not `d`, and this is the one line in the feature that earns it: this is Nudge
        // performing a CLICK inside somebody else's app. Debug logging is off by default, so a
        // `d` here would make the only action this app takes in another app invisible in every
        // field report -- the failure this repo has paid for twice ("a surface you cannot
        // observe cannot be debugged"). At most two lines per home-feed arrival, so it cannot
        // flood. `performed=false` is the interesting half: the node was absent and the steer
        // silently did nothing, which is the designed failure and otherwise looks identical to
        // never having run.
        entryPoint.nudgeLogger().i(
            "following steer action=$action performed=$performed package=$packageName"
        )
    }

    /**
     * Whether the device is in dark mode, which is what the host app's nav-bar colour follows.
     *
     * Read per call rather than cached: the user can flip the system theme (or cross a scheduled
     * light/dark boundary) while sitting in the app, and a cached white rectangle on a black nav bar
     * is the single most visible way this feature can look broken.
     */
    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * What TalkBack says about the covered tab.
     *
     * DERIVED from the feature enum rather than written out: this app's own accessibility service
     * is what puts a blank region over somebody's navigation bar, so leaving it unlabelled would be
     * the wrong end of that -- and a hand-typed "Reels" here would be a second name for a thing that
     * already has one (`docs/TESTING.md`, fixture honesty).
     */
    private fun coverLabel(featureKey: String?): String {
        val feature = InAppDetector.Feature.entries.firstOrNull { it.key == featureKey }
        return "${feature?.displayName ?: "This tab"} blocked by $ownAppLabel"
    }

    /**
     * Issue #7 fallback: treat a content-change event as a genuine foreground app switch when the
     * event's package really does own the active window, and route it into normal evaluation so the
     * re-entry gets its delay re-block / counter session / time-remaining overlay.
     *
     * Throttled per package: [evaluateForegroundPackage] early-returns (an active emergency pass,
     * post-delay passthrough) without advancing `lastPackage`, so the cheap same-package rejection
     * inside the decision function would not fire and the active-window read would run on every
     * content-change event for the duration of that window.
     */
    private fun maybeEvaluateContentChangeAsAppSwitch(record: AccessibilityEventRecord) {
        val packageName = record.packageName
        val now = System.currentTimeMillis()
        val lastAttempt = lastSwitchCheckTime[packageName] ?: 0L
        if ((now - lastAttempt) < SWITCH_CHECK_DEBOUNCE_MS) return
        lastSwitchCheckTime[packageName] = now

        // The cheap rejection that is about COST, not about what is on screen: an event from the
        // app we already believe we are in cannot be a switch into it, and skipping it here is what
        // keeps the node-tree read off the overwhelmingly common case.
        if (packageName.isBlank() || packageName == lastPackage) return

        // Then ONE classification, from the same classifier every other path uses. This used to be
        // a second, parallel eligibility gate (`shouldTreatContentChangeAsAppSwitch`) re-testing
        // blank / own / system / transient by hand before the classifier tested them again: "what is
        // on screen" answered twice, on the one entry point where the two answers could disagree.
        val signal = eventClassifier.classifyVerifiedContentChangeAsSwitch(
            record = record,
            currentImePackage = currentImePackage,
            pipOnlyPackages = pipOnlyPackagesCached
        )
        if (signal !is ForegroundSignal.AppWindow) return

        // Only NOW is the binder read worth paying for, and it is what earns the promotion: a
        // content change claims nothing about the foreground unless its package owns the REAL
        // active window. A null or unreadable window is never a switch -- a false positive costs
        // the user their passthrough, a false negative just retries on the next event.
        if (activeWindowPackageOrNull() != packageName) return

        entryPoint.nudgeLogger().i(
            "foreground switch detected from content change package=$packageName"
        )
        // THE SITTING MUST BE UPDATED HERE TOO. This is the service's SECOND entry point into
        // evaluation, and the only one that is not a window event, so the classification at the top
        // of onAccessibilityEvent saw a `NotForeground` signal and correctly did nothing with it.
        // Without this the away clock never starts for an app entered via a notification tap
        // (exactly the case issue #7 exists for), and the user could switch away for half an hour,
        // come back, and still skip the delay.
        applyForegroundSignal(signal)
        evaluateForegroundPackage(packageName)
    }

    /**
     * Package that owns the current active window, or null if it cannot be read. Null means "not
     * verified", which callers must treat as "do not act" — never as a match.
     */
    private fun activeWindowPackageOrNull(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    /**
     * THE ONE PLACE THIS APP PUTS A BLOCK OVERLAY ON SCREEN.
     *
     * Four paths used to build their own intent, set the overlay flag and call `startActivity`
     * themselves: a rule block, the auto-kick cooldown, the web auto-kick cooldown and the
     * daily-limit hard block. Four copies of a launch means four places to remember a gate, and
     * remembering it in three of four is how [#19](https://github.com/astraedus/nudge/issues/19)
     * shipped, its first fix guarded the branch that had been reported and missed the common case.
     *
     * @param targetPackage the package a launch would block RE-ENTRY to: the app the user is
     *   sitting in. For a web block that is the browser, not the rule's app. This is what the gate
     *   compares against the foreground; see [BlockLaunchGate.decide] for why using the attributed
     *   package here would drop every web block ever written.
     * @param attributedPackage what the block is recorded against: the overlay's label, the
     *   `UsageEvent`, the picture-in-picture session record.
     * @param extras fills in the intent. Called only when the launch is going to happen.
     * @return false when the gate refused, in which case the caller must not record the block
     *   either, a `UsageEvent` for an overlay nobody saw is the stat inflation issue #19 measured.
     */
    private fun launchBlockOverlay(
        targetPackage: String,
        attributedPackage: String,
        extras: Intent.() -> Unit
    ): Boolean {
        val guard = entryPoint.blockLaunchGuard()
        val decision = guard.decide(targetPackage)

        // THE STORM DIAGNOSTIC (issue #36), and it counts ATTEMPTS rather than launches.
        //
        // The arrival invariant below (`claimConfrontation`) makes the intervention count safe
        // whatever loop produces the launches, which is the point of it -- and it also makes the
        // loop invisible, because a mechanism that used to arrive as "2500 interventions" now
        // arrives as nothing at all. One `w` line per storm, naming the target, how many attempts,
        // what the gate said and what it believed was in front, so the NEXT field report carries
        // its own mechanism instead of costing a device session to find.
        guard.onLaunchAttempt(targetPackage, decision)?.let { storm ->
            entryPoint.nudgeLogger().w(
                "block overlay launch storm target=${storm.targetPackage} " +
                    "attempts=${storm.launches} withinMs=${storm.windowMs} " +
                    "decisions=${storm.decisions.joinToString("/")} ${guard.stateDescription()}"
            )
        }

        if (decision != BlockLaunchGate.Decision.LAUNCH) {
            // Logged unconditionally and with BOTH the target and what is actually in front,
            // because "the block was dropped" and "the block never happened" are indistinguishable
            // from a device otherwise, the ambiguity that cost the v1.12.0 release cycle.
            entryPoint.nudgeLogger().i(
                "block overlay launch dropped target=$targetPackage " +
                    "attributed=$attributedPackage reason=${decision.name} " +
                    "foreground=${guard.foregroundPackage}"
            )
            return false
        }

        val overlayIntent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            extras()
        }
        // Mark the overlay active synchronously (before any further event) so the flag is
        // authoritative even if the singleInstance activity is re-delivered via onNewIntent
        // (which never re-runs onCreate).
        markOverlayActive(attributedPackage)
        // ...and record that it is only STARTED, not yet on screen. Those are different facts and
        // conflating them is what produced two blocks for one app entry: see
        // [BlockLaunchGate.isGenuineBypass]. Keyed on the TARGET, because that is the package whose
        // trailing window events must not be mistaken for the user getting past the overlay.
        guard.onOverlayLaunched(targetPackage)
        applicationContext.startActivity(overlayIntent)
        return true
    }

    /**
     * @param packageName what the block is ATTRIBUTED to: the app whose rule matched. Drives the
     *   `UsageEvent`, the overlay's app label and the PiP session record. For a web block this is
     *   the rule's app (Instagram), not the browser, so a website block still shows up in that
     *   app's stats and the overlay still names the app the user recognises.
     * @param web set when the block happened on a website. Carries the browser (the app the user is
     *   actually in, and therefore what a passthrough grant or an emergency pass must apply to) and
     *   the domain (so the grant is scoped to the site, not the whole browser).
     */
    private suspend fun handleDecision(
        decision: BlockDecision,
        packageName: String,
        featureKey: String? = null,
        web: WebBlockContext? = null
    ) {
        when (decision) {
            is BlockDecision.Block -> {
                entryPoint.nudgeLogger().i(
                    "handling block package=$packageName mode=${decision.mode} " +
                        "delaySeconds=${decision.delaySeconds} grayscale=${decision.grayscale}"
                )

                // THE GATE, AND IT IS AHEAD OF THE `UsageEvent` DELIBERATELY (issue #31).
                //
                // This function is the far side of a coroutine: `evaluateForegroundPackage` decided
                // the user was in this app, then handed off to the IO scope for a rule lookup, a
                // usage read and sometimes a URL-bar read, while foreground changes kept arriving on
                // the main thread. By the time we get here the premise may be false, the reporter's
                // words are "a race between asynchronous rule evaluation and foreground changes".
                //
                // Recording the block and then refusing to show it would be worse than either
                // outcome: the all-time Blocked count would climb for overlays nobody ever saw,
                // which is exactly the stat inflation issue #19 measured (+11 in one incident).
                // So the gate runs before grayscale, before the row, before everything.
                val launched = launchBlockOverlay(
                    targetPackage = web?.browserPackage ?: packageName,
                    attributedPackage = packageName
                ) {
                    putExtra(BlockOverlayActivity.EXTRA_BLOCK_MODE, decision.mode.name)
                    putExtra(BlockOverlayActivity.EXTRA_DELAY_SECONDS, decision.delaySeconds)
                    putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(BlockOverlayActivity.EXTRA_FEATURE_KEY, featureKey)
                    putExtra(BlockOverlayActivity.EXTRA_RULE_NAME, decision.ruleName)
                    web?.let {
                        putExtra(BlockOverlayActivity.EXTRA_PASSTHROUGH_PACKAGE, it.browserPackage)
                        putExtra(BlockOverlayActivity.EXTRA_WEB_DOMAIN, it.domain)
                    }
                    decision.dailyTimeRemainingMs?.let {
                        putExtra(BlockOverlayActivity.EXTRA_DAILY_TIME_REMAINING_MS, it)
                    }
                    decision.dailyLimitMinutes?.let {
                        putExtra(BlockOverlayActivity.EXTRA_DAILY_LIMIT_MINUTES, it)
                    }
                }
                if (!launched) return

                if (decision.grayscale) {
                    entryPoint.grayscaleManager().enableGrayscale()
                    grayscaleActiveForPackage = packageName
                }

                // ONE CONFRONTATION PER ARRIVAL (issue #36), and it sits BELOW the launch and below
                // grayscale on purpose: this refuses a ROW, never a block. The overlay is already
                // on its way, and it should be -- somebody still sitting in a blocked app should go
                // on meeting the block. What must not go on is the COUNT rising for a confrontation
                // the user never walked into.
                //
                // The 2500-in-a-day report is what happens without it. Nothing above this line
                // answers "has the user arrived here since the last time we counted them", so every
                // mechanism that can re-launch an overlay by itself -- an overlay stopped and
                // finished by a screen-off or a re-fronting app, a re-delivery through
                // `onNewIntent`, the walk-away fail-safe popping the app back -- also re-counted it,
                // once per iteration, for as long as it ran.
                val counted = entryPoint.blockLaunchGuard().claimConfrontation(
                    targetPackage = web?.browserPackage ?: packageName,
                    key = BlockLaunchGate.confrontationKey(
                        attributedPackage = packageName,
                        featureKey = featureKey,
                        webDomain = web?.domain
                    )
                )
                if (!counted) {
                    entryPoint.nudgeLogger().i(
                        "block shown but NOT counted package=$packageName " +
                            "reason=already_counted_this_arrival feature=$featureKey " +
                            "domain=${web?.domain}"
                    )
                    return
                }

                entryPoint.usageRepository().logEvent(
                    UsageEvent(
                        packageName = packageName,
                        wasBlocked = true,
                        blockMode = decision.mode.name
                    )
                )
            }

            is BlockDecision.Allow -> {
                entryPoint.usageRepository().logEvent(
                    UsageEvent(packageName = packageName)
                )
            }
        }
    }

    /**
     * Starts (or keeps) the periodic foreground-time clock for [packageName], or stops it if this
     * app has nothing clock-driven configured.
     *
     * Why a clock at all: every other awareness path in this service is edge-triggered by
     * accessibility events, which is fine for counting taps and scrolls but useless for a user
     * watching passively — no events means no ticks means a time-based auto-kick that never fires
     * and a daily limit that is only noticed the next time something happens to be tapped. One
     * timer per foreground app closes that hole for both.
     *
     * Idempotence, the immediate first tick, the per-tick exception guard and the start/stop logging
     * all live in [ForegroundClock] — see that class for why the inline loop this replaced was a
     * silent single-point-of-failure.
     */
    private fun updateForegroundTimeTicker(packageName: String) {
        val entry = counterCache.getEntry(packageName)
        if (entry == null || !entry.needsForegroundTimeTick) {
            stopForegroundTimeTicker("no_clock_config")
            return
        }
        foregroundClock.start(packageName) { tickForegroundTime(it) }
    }

    private fun stopForegroundTimeTicker(reason: String) {
        foregroundClock.stop(reason)
    }

    /**
     * One pass of the foreground-time clock: read usage once, feed the time-based auto-kick, then
     * refresh the time-remaining overlay / daily-limit block.
     *
     * Runs on the service's IO scope (the usage read is a binder call); anything touching the
     * WindowManager is hopped to Main.
     */
    private suspend fun tickForegroundTime(packageName: String) {
        // A disabled Nudge behaves as if uninstalled — same invariant as the synchronous gate in
        // onAccessibilityEvent, re-checked here because this runs on a timer, not on an event.
        if (!globalEnabledCached) return

        // The daily pass promises uninterrupted minutes; it overrides the time trigger exactly as
        // it already overrides rule evaluation and the auto-kick cooldown.
        if (entryPoint.emergencyPassManager().isPassActive(packageName)) return

        if (autoKickTimeHandler.shouldKick(packageName)) {
            withContext(Dispatchers.Main) {
                autoKickExecutor.kick(packageName, reason = "session time")
            }
            // The user is on their way home; the next foreground event restarts the clock.
            stopForegroundTimeTicker("auto_kicked")
            return
        }

        withContext(Dispatchers.Main) { timeRemainingHandler.maybeUpdate(packageName) }
    }

    private fun refreshCounterCacheIfNeeded() {
        val now = System.currentTimeMillis()
        serviceScope.launch {
            val refreshed = counterCache.refreshIfNeeded(now) { loadCounterCacheEntries() }
            if (refreshed) {
                entryPoint.nudgeLogger().d("counter cache refreshed packages=${counterCache.snapshot().size}")
            }
        }
    }

    private suspend fun loadCounterCacheEntries(): Map<String, CounterCacheEntry> {
        val rules = entryPoint.blockRuleRepository().getEnabledRules().first()
        val appEntries = rules
            // A time-based auto-kick needs no counter and no overlay, so it must be able to put
            // a package in the cache on its own — otherwise the hot path would never see it.
            .filter { it.showCounter || it.showTimeRemaining || it.autoKickAfterMinutes != null }
            .mapNotNull { rule ->
                rule.packageName?.let { pkg ->
                    pkg to CounterCacheEntry(
                        showCounter = rule.showCounter,
                        autoKickAfter = rule.autoKickAfter,
                        showTimeRemaining = rule.showTimeRemaining,
                        dailyLimitMinutes = rule.dailyLimitMinutes,
                        autoKickCooldownSeconds = rule.autoKickCooldownSeconds,
                        autoKickAfterMinutes = rule.autoKickAfterMinutes
                    )
                }
            }

        // A rule's websites are tracked under their own keys, so a kick or a cooldown lands on the
        // site rather than on the whole browser. Gated on the resolved WEB mode (#21), never the
        // app-level one: a rule that blocks nothing on the web must not eject anyone from it.
        val webEntries = rules.flatMap { rule ->
            CounterCacheRefresher.webEntriesFor(
                webDomains = rule.webDomains,
                webEnforces = WebBlockMode.resolve(rule.mode, rule.webBlockMode) != BlockMode.NONE,
                autoKickAfterMinutes = rule.autoKickAfterMinutes,
                autoKickCooldownSeconds = rule.autoKickCooldownSeconds
            )
        }

        return CounterCacheRefresher.mergeEntries(appEntries + webEntries)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        // The other direction: blocking has just stopped, and a screen sitting on a green tick
        // needs to stop claiming otherwise.
        AccessibilityConnectionSignal.onConnectionChanged()
        stopForegroundTimeTicker("service_destroyed")
        endWebSession("service_destroyed")
        try {
            contentResolver.unregisterContentObserver(imeSettingObserver)
        } catch (_: Exception) {
            // Observer may never have registered (register failed) — ignore.
        }
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
            // Receiver may never have registered (register failed) — ignore.
        }
        entryPoint.passthroughManager().setSittingReaction(null)
        entryPoint.counterOverlayManager().clearServiceContext()
        entryPoint.timeRemainingOverlayManager().clearServiceContext()
        entryPoint.tabCoverOverlayManager().clearServiceContext()
        passthroughManagerInstance = null
        if (grayscaleActiveForPackage != null) {
            entryPoint.grayscaleManager().disableGrayscale()
            grayscaleActiveForPackage = null
        }
        serviceScope.cancel()
    }
}
