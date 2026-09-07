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
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.lock.StrictModeEscapeGuard
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.model.WebBlockMode
import com.astraedus.nudge.domain.pip.PipEscapeLedger
import com.astraedus.nudge.domain.web.WebDomainGate
import com.astraedus.nudge.domain.web.WebSessionKey
import com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase
import com.astraedus.nudge.ui.lock.StrictModeGuardActivity
import com.astraedus.nudge.ui.overlay.BlockOverlayActivity
import com.astraedus.nudge.ui.overlay.PipEscapeActivity
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        fun blockRuleRepository(): BlockRuleRepository
        fun nudgeLogger(): NudgeLogger
        fun passthroughManager(): PassthroughManager
        fun webDomainDetector(): WebDomainDetector
        fun strictModeEscapeManager(): StrictModeEscapeManager
        fun emergencyPassManager(): EmergencyPassManager
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            NudgeAccessibilityEntryPoint::class.java
        )
    }

    private var lastPackage: String? = null
    private var lastEvalTime: Long = 0L

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

        /**
         * True when this event means the user went HOME — i.e. genuinely left whatever app they were
         * in — and any post-overlay passthrough for that app must therefore be dropped.
         *
         * The bug this exists for: [SYSTEM_PACKAGES] contains the stock launchers, and the
         * `SYSTEM_PACKAGES` early-return in [onAccessibilityEvent] fires long before
         * [evaluateForegroundPackage] reaches `PassthroughManager.clearIfAppChanged`. So completing a
         * delay for app X, pressing Home and re-opening X skipped the delay — indefinitely, and only
         * opening some OTHER non-system app in between re-armed it. That is the most common exit
         * path there is, so the delay was effectively one-shot per app.
         *
         * The launcher must be distinguished from the rest of [SYSTEM_PACKAGES] rather than clearing
         * for all of them: the notification shade / SystemUI, the IME, a permission dialog and our
         * own overlay all foreground briefly WITHOUT the user leaving the app, and clearing on those
         * would re-delay a user for pulling the shade — a worse bug than the one being fixed. The
         * allowlist direction is deliberate: an unresolvable or stale launcher set clears nothing and
         * behaves exactly as this service did before.
         *
         * Restricted to `TYPE_WINDOW_STATE_CHANGED`, for the same reason
         * [isOverlayBypassedByForeground] is: that is the only event type that means "a new activity
         * is in front". Launcher content-change churn (widgets, wallpaper, the icon grid redrawing
         * behind a fullscreen app) is not evidence that anything came forward.
         *
         * Note a launcher that is NOT in [SYSTEM_PACKAGES] (a third-party one like Nova) already
         * worked: those events fall through to [evaluateForegroundPackage], which clears passthrough
         * via the normal app-switch path. This restores parity for the stock launchers.
         */
        internal fun isHomeScreenForeground(
            eventType: Int,
            packageName: String,
            launcherPackages: Set<String>,
            ownPackageName: String,
            currentImePackage: String?
        ): Boolean {
            if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
            if (packageName.isBlank()) return false
            if (packageName == ownPackageName) return false
            if (isTransientNonAppPackage(packageName, currentImePackage)) return false
            return packageName in launcherPackages
        }

        /**
         * Issue #7: re-entering an app via the recents overview or a notification tap sometimes
         * delivers ONLY [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED] — no
         * TYPE_WINDOW_STATE_CHANGED — so [evaluateForegroundPackage] never ran for that return.
         * The user got no delay re-block, no counter session and no time-remaining overlay: "the
         * app timer does not start on re-entrance".
         *
         * Edge-triggering on *every* content change would fix that and immediately reintroduce
         * issue #5: content-change events also arrive from windows that are NOT in front, so a
         * ghost app-switch would clear the post-delay passthrough and re-block the user. The
         * decision is therefore STATE-VERIFIED — the event's package must also own the real active
         * window ([AccessibilityService.getRootInActiveWindow]) before it counts as a switch.
         *
         * Cheap rejections run before [activeWindowPackage] is invoked, so the node-tree read never
         * happens for the app the user is already in (the overwhelmingly common case on this very
         * hot path).
         *
         * @param activeWindowPackage package owning the active window, or null when it could not be
         *   read — an unverifiable event is never treated as a switch (fail soft toward doing
         *   nothing, since a false positive costs the user their passthrough).
         */
        internal fun shouldTreatContentChangeAsAppSwitch(
            packageName: String,
            lastPackage: String?,
            ownPackageName: String,
            currentImePackage: String?,
            activeWindowPackage: () -> String?
        ): Boolean {
            if (packageName.isBlank()) return false
            if (packageName == lastPackage) return false
            if (packageName == ownPackageName) return false
            if (packageName in SYSTEM_PACKAGES) return false
            if (isTransientNonAppPackage(packageName, currentImePackage)) return false
            return activeWindowPackage() == packageName
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

        internal fun shouldClearForOwnPackageEvent(
            eventType: Int,
            className: String?,
            ownPackageName: String
        ): Boolean {
            return eventType in WINDOW_CHANGE_EVENT_TYPES &&
                className?.startsWith(ownPackageName) == true
        }

        /**
         * True when a foreground event means the block overlay has been BYPASSED and the flag is
         * stale. The overlay lives in its own task ([BlockOverlayActivity] is singleInstance with an
         * empty taskAffinity), so the user can bring the blocked app's task back to the foreground
         * directly — e.g. tapping the app icon or Recents after leaving the block screen — which
         * orphans the overlay in a background task while [isOverlayActive] is still true. When that
         * happens a real (non-own, non-system) app fires a genuine foreground switch
         * (TYPE_WINDOW_STATE_CHANGED). We must treat the overlay as gone and re-evaluate so the block
         * re-asserts, instead of swallowing the event as "overlay is handling it".
         *
         * Restricted to TYPE_WINDOW_STATE_CHANGED (a true foreground change): content-change churn
         * from the app underneath a genuinely-live overlay must NOT clear the flag. Own-package and
         * system-package events are also excluded (the overlay itself / launcher / systemui).
         */
        internal fun isOverlayBypassedByForeground(
            eventType: Int,
            packageName: String,
            ownPackageName: String,
            currentImePackage: String? = null
        ): Boolean {
            return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                packageName != ownPackageName &&
                packageName !in SYSTEM_PACKAGES &&
                !isTransientNonAppPackage(packageName, currentImePackage)
        }

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
     * Did this system-package event mean the user went to the home screen?
     *
     * The cheap event-type test runs FIRST so launcher/SystemUI content-change churn never reaches
     * the staleness check, let alone PackageManager. [isHomeScreenForeground] re-checks the event
     * type so the decision stays self-contained and unit-testable.
     */
    private fun wentHome(eventType: Int, packageName: String): Boolean {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        refreshLauncherPackagesIfStale(System.currentTimeMillis())
        return isHomeScreenForeground(
            eventType = eventType,
            packageName = packageName,
            launcherPackages = launcherPackagesCached,
            ownPackageName = applicationContext.packageName,
            currentImePackage = currentImePackage
        )
    }

    /**
     * Re-read the home-screen packages if the cached set is older than [LAUNCHER_REFRESH_MS].
     *
     * Called only from the system-package branch of [onAccessibilityEvent] and only for a genuine
     * foreground change, so the steady-state cost inside an app is zero and the worst case is one
     * small `queryIntentActivities` every five minutes.
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
     */
    private fun clearPassthroughForHome(packageName: String) {
        val passthrough = entryPoint.passthroughManager()
        if (passthrough.clearIfAppChanged(packageName)) {
            entryPoint.nudgeLogger().d("passthrough cleared on home screen package=$packageName")
        }
        // Same "the user left" semantics for the web passthrough: the web grant is otherwise only
        // cleared inside evaluateForegroundPackage, which the system-package return skips, so a
        // completed web delay survived Home exactly as the app-level one did.
        passthrough.clearWebGrant()
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
        entryPoint.counterOverlayManager().setServiceContext(this)
        entryPoint.timeRemainingOverlayManager().setServiceContext(this)

        val passthrough = entryPoint.passthroughManager()
        passthroughManagerInstance = passthrough

        timeRemainingHandler = TimeRemainingHandler(
            timeRemainingOverlayManager = entryPoint.timeRemainingOverlayManager(),
            usageRepository = entryPoint.usageRepository(),
            preferences = entryPoint.nudgePreferences(),
            counterCache = counterCache,
            passthroughManager = passthrough,
            logger = entryPoint.nudgeLogger(),
            context = applicationContext,
            serviceScope = serviceScope
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
        if (event.eventType in WINDOW_CHANGE_EVENT_TYPES) refreshPipOnlyPackages()
        if (packageName in pipOnlyPackagesCached) {
            return
        }

        if (isOverlayActive) {
            // If a real app has come to the foreground, the overlay is no longer covering it — the
            // user tabbed out and back into the blocked app, orphaning the overlay in its own task.
            // Clear the stale flag and fall through to normal evaluation so the block re-asserts.
            // (Same-package trailing events within DEBOUNCE_MS are still absorbed downstream, so a
            // genuinely-live overlay doesn't re-fire.) Everything else — the overlay's own window,
            // system windows, content-change churn under a live overlay — is swallowed as before.
            if (isOverlayBypassedByForeground(
                    event.eventType,
                    packageName,
                    applicationContext.packageName,
                    currentImePackage
                )
            ) {
                markOverlayInactive()
                entryPoint.nudgeLogger().i(
                    "block overlay bypassed by foreground switch — re-evaluating package=$packageName"
                )
            } else {
                clearOverlays(applicationContext.packageName, "block_overlay_active")
                return
            }
        }

        if (packageName == applicationContext.packageName) {
            if (isOwnAppWindowEvent(event)) {
                clearOverlays(packageName, "own_app_window")
            }
            return
        }

        // Transient, non-application windows (any soft keyboard, or the `android` framework package
        // that hosts the paste/long-press popups + toasts) must NOT be treated as an app switch.
        // Doing so cleared post-delay passthrough and re-triggered the block on return (issue #5).
        // Ignore the event entirely: don't clear overlays, don't clear passthrough, don't move
        // lastPackage — the real app underneath hasn't changed.
        if (isTransientNonAppPackage(packageName, currentImePackage)) {
            entryPoint.nudgeLogger().d("ignoring transient non-app window package=$packageName")
            return
        }

        // Strict Mode Phase 2: guard the OS escape routes (Settings → Accessibility toggle,
        // App Info → Force stop / Uninstall) BEFORE the SYSTEM_PACKAGES early-return swallows
        // settings events. Only inspects window content on settings packages and only on window
        // change events; cheap pure checks gate the (more expensive) node-tree read.
        if (packageName in StrictModeEscapeGuard.SETTINGS_PACKAGES &&
            event.eventType in WINDOW_CHANGE_EVENT_TYPES
        ) {
            maybeGuardSettingsEscape(packageName)
            // fall through to the SYSTEM_PACKAGES handling below (clears any stale counter overlays)
        }

        if (packageName in SYSTEM_PACKAGES) {
            // Going HOME is the user genuinely leaving the app — and it is the exit path this
            // early-return used to swallow, so a completed delay never re-armed for it. Every other
            // system surface (shade, permission dialog, installer) is transient and must NOT clear.
            val home = wentHome(event.eventType, packageName)
            if (home) clearPassthroughForHome(packageName)
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

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                evaluateForegroundPackage(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(packageName, event)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                interactionHandler.handleViewClicked(packageName)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                interactionHandler.handleViewScrolled(packageName) {
                    try { rootInActiveWindow } catch (_: Exception) { null }
                }
            }
        }
    }

    private fun isOwnAppWindowEvent(event: AccessibilityEvent): Boolean {
        return shouldClearForOwnPackageEvent(
            eventType = event.eventType,
            className = event.className?.toString(),
            ownPackageName = applicationContext.packageName
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

        // If leaving a browser, the user has stopped being on whatever site they earned entry to:
        // drop the web grant and end the web session's clock. The app-level grant is a separate
        // axis and is handled by clearIfAppChanged further down.
        if (entryPoint.webDomainDetector().isBrowser(lastPackage ?: "") &&
            !entryPoint.webDomainDetector().isBrowser(packageName)
        ) {
            passthrough.clearWebGrant()
            endWebSession("left_the_browser")
        }

        if (!counterCache.hasEntry(packageName)) {
            clearOverlays(packageName, "counter_disabled", markForeground = false)
        } else if (packageName != lastPackage) {
            interactionHandler.activeReelLabel = null
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
        if (tracker.isInCooldown(packageName)) {
            val remainingMs = tracker.getCooldownRemainingMs(packageName)
            val remainingSeconds = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(1)
            entryPoint.nudgeLogger().i(
                "cooldown enforced package=$packageName remaining=${remainingSeconds}s"
            )
            val overlayIntent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(BlockOverlayActivity.EXTRA_BLOCK_MODE, "DELAY")
                putExtra(BlockOverlayActivity.EXTRA_DELAY_SECONDS, remainingSeconds)
                putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(BlockOverlayActivity.EXTRA_RULE_NAME, "Auto-kick cooldown")
            }
            // Mark the overlay active synchronously (before any further event) so the flag is
            // authoritative even if the singleInstance activity is re-delivered via onNewIntent
            // (which never re-runs onCreate).
            markOverlayActive(packageName)
            applicationContext.startActivity(overlayIntent)
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

        if (passthrough.clearIfAppChanged(packageName)) {
            entryPoint.nudgeLogger().d("passthrough cleared on app switch package=$packageName")
        }

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
        if (!tracker.isInCooldown(key)) return false

        val remainingMs = tracker.getCooldownRemainingMs(key)
        val remainingSeconds = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(1)
        entryPoint.nudgeLogger().i(
            "web cooldown enforced domain=$domain remaining=${remainingSeconds}s"
        )
        val overlayIntent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
        markOverlayActive(browserPackage)
        applicationContext.startActivity(overlayIntent)
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
        interactionHandler.activeReelLabel = null
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
        entryPoint.interactionTracker().clearAllCooldowns()
        entryPoint.emergencyPassManager().cancelAll()
        serviceScope.launch(Dispatchers.Main) { hideAllOverlays() }
    }

    private fun handleWindowContentChanged(packageName: String, event: AccessibilityEvent) {
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

        if (packageName !in InAppDetector.SUPPORTED_PACKAGES) {
            // Issue #7: a re-entry the OS delivers WITHOUT a TYPE_WINDOW_STATE_CHANGED (recents
            // overview, notification tap) would otherwise never be evaluated for this package —
            // only SUPPORTED_PACKAGES fell through to evaluation below. Verify against the real
            // active window before treating it as a switch (see the pure decision function), and
            // throttle the attempt: evaluation early-returns (emergency pass, passthrough) leave
            // lastPackage untouched, so without this the node-tree read would repeat on every
            // content change for the whole of that window.
            maybeEvaluateContentChangeAsAppSwitch(packageName)

            // For non-SUPPORTED packages (e.g., React Native apps like Discord that don't
            // fire TYPE_VIEW_CLICKED), use content changes as a proxy for user interaction.
            interactionHandler.handleContentChanged(packageName)
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
        val feature = entryPoint.inAppDetector().detectFeature(packageName, rootNode) ?: return
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
     * Issue #7 fallback: treat a content-change event as a genuine foreground app switch when the
     * event's package really does own the active window, and route it into normal evaluation so the
     * re-entry gets its delay re-block / counter session / time-remaining overlay.
     *
     * Throttled per package: [evaluateForegroundPackage] early-returns (an active emergency pass,
     * post-delay passthrough) without advancing `lastPackage`, so the cheap same-package rejection
     * inside the decision function would not fire and the active-window read would run on every
     * content-change event for the duration of that window.
     */
    private fun maybeEvaluateContentChangeAsAppSwitch(packageName: String) {
        val now = System.currentTimeMillis()
        val lastAttempt = lastSwitchCheckTime[packageName] ?: 0L
        if ((now - lastAttempt) < SWITCH_CHECK_DEBOUNCE_MS) return
        lastSwitchCheckTime[packageName] = now

        val isSwitch = shouldTreatContentChangeAsAppSwitch(
            packageName = packageName,
            lastPackage = lastPackage,
            ownPackageName = applicationContext.packageName,
            currentImePackage = currentImePackage,
            activeWindowPackage = { activeWindowPackageOrNull() }
        )
        if (!isSwitch) return

        entryPoint.nudgeLogger().i(
            "foreground switch detected from content change package=$packageName"
        )
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
                if (decision.grayscale) {
                    entryPoint.grayscaleManager().enableGrayscale()
                    grayscaleActiveForPackage = packageName
                }

                entryPoint.usageRepository().logEvent(
                    UsageEvent(
                        packageName = packageName,
                        wasBlocked = true,
                        blockMode = decision.mode.name
                    )
                )

                val overlayIntent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
                // Mark the overlay active synchronously (before any further event) so the flag is
                // authoritative even if the singleInstance activity is re-delivered via onNewIntent
                // (which never re-runs onCreate).
                markOverlayActive(packageName)
                applicationContext.startActivity(overlayIntent)
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
        entryPoint.counterOverlayManager().clearServiceContext()
        entryPoint.timeRemainingOverlayManager().clearServiceContext()
        passthroughManagerInstance = null
        if (grayscaleActiveForPackage != null) {
            entryPoint.grayscaleManager().disableGrayscale()
            grayscaleActiveForPackage = null
        }
        serviceScope.cancel()
    }
}
