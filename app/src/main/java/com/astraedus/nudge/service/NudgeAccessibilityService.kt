package com.astraedus.nudge.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.lock.StrictModeEscapeGuard
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase
import com.astraedus.nudge.ui.lock.StrictModeGuardActivity
import com.astraedus.nudge.ui.overlay.BlockOverlayActivity
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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

    // Web domain blocking state
    @Volatile
    private var lastBlockedDomain: String? = null

    @Volatile
    private var grayscaleActiveForPackage: String? = null

    private val counterCache = CounterCacheRefresher()

    private lateinit var interactionHandler: InteractionHandler
    private lateinit var timeRemainingHandler: TimeRemainingHandler
    private lateinit var autoKickExecutor: AutoKickExecutor
    private lateinit var autoKickTimeHandler: AutoKickTimeHandler

    /**
     * The periodic foreground-time clock (see [updateForegroundTimeTicker]). At most one runs at a
     * time, for the package named by [tickingPackage].
     */
    private var foregroundTimeJob: Job? = null

    @Volatile
    private var tickingPackage: String? = null

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

    /** Refreshes [currentImePackage] whenever the default keyboard changes. */
    private val imeSettingObserver by lazy {
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshCurrentImePackage()
            }
        }
    }

    /**
     * Read and cache the current default IME's package. The secure setting value looks like
     * `org.futo.inputmethod.latin/.LatinIME`; we keep only the package half. Fails soft to null.
     */
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

        serviceScope.launch {
            counterCache.forceRefresh { loadCounterCacheEntries() }
            entryPoint.nudgeLogger().d("counter cache eagerly populated packages=${counterCache.snapshot().size}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

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
                isOverlayActive = false
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
            clearOverlays(packageName, "system_package")
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

        // If leaving a browser, clear web domain passthrough state
        if (entryPoint.webDomainDetector().isBrowser(lastPackage ?: "") &&
            !entryPoint.webDomainDetector().isBrowser(packageName)
        ) {
            lastBlockedDomain = null
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
            isOverlayActive = true
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

        // If domain hasn't changed and we already blocked it, skip (passthrough)
        if (extractedDomain != null && extractedDomain == lastBlockedDomain) {
            entryPoint.nudgeLogger().d("web domain: passthrough for already-blocked domain=$extractedDomain")
            return
        }

        // Domain changed -- clear passthrough
        if (extractedDomain != lastBlockedDomain) {
            lastBlockedDomain = null
        }

        val result = entryPoint.evaluateBlockUseCase().evaluateWebDomain(urlBarText)
        entryPoint.nudgeLogger().d("web domain: url=$urlBarText decision=${result.decision}")

        when (result.decision) {
            is BlockDecision.Block -> {
                // Only set passthrough for delay/breathing (user completed the exercise).
                // HARD_BLOCK has no "completed" state — always re-evaluate on return.
                if (result.decision.mode != BlockMode.HARD_BLOCK) {
                    lastBlockedDomain = extractedDomain
                }
                handleDecision(result.decision, result.trackingPackage ?: browserPackage)
            }
            is BlockDecision.Allow -> {
                // Not blocked -- nothing to do
            }
        }
    }

    private fun clearOverlays(
        packageName: String,
        reason: String,
        markForeground: Boolean = true
    ) {
        interactionHandler.activeReelLabel = null
        if (markForeground) {
            lastPackage = packageName
        }
        interactionHandler.onAppChanged(packageName)
        // Whatever is in front now is not a clock-driven package (or is our own window / a system
        // one), so stop reading the foreground-time clock.
        stopForegroundTimeTicker()

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
        stopForegroundTimeTicker()
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

    private suspend fun handleDecision(
        decision: BlockDecision,
        packageName: String,
        featureKey: String? = null
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
                isOverlayActive = true
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
     * Idempotent: repeated calls for the same package leave the running job (and therefore the tick
     * phase) alone, which matters because `evaluateForegroundPackage` is re-entered on debounced
     * events and on the issue #7 content-change fallback — restarting the job each time would keep
     * resetting the `delay` and the clock would never actually tick.
     */
    private fun updateForegroundTimeTicker(packageName: String) {
        val entry = counterCache.getEntry(packageName)
        if (entry == null || !entry.needsForegroundTimeTick) {
            stopForegroundTimeTicker()
            return
        }
        if (packageName == tickingPackage && foregroundTimeJob?.isActive == true) return

        stopForegroundTimeTicker()
        tickingPackage = packageName
        foregroundTimeJob = serviceScope.launch {
            // Tick immediately so the session baseline is taken at (near) session start rather than
            // one interval in, then settle into the periodic cadence.
            while (isActive) {
                tickForegroundTime(packageName)
                delay(FOREGROUND_TICK_MS)
            }
        }
    }

    private fun stopForegroundTimeTicker() {
        foregroundTimeJob?.cancel()
        foregroundTimeJob = null
        tickingPackage = null
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
            stopForegroundTimeTicker()
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
        return CounterCacheRefresher.mergeEntries(
            rules
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
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        stopForegroundTimeTicker()
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
