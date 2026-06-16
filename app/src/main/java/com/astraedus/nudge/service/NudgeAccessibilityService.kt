package com.astraedus.nudge.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.usecase.EvaluateBlockUseCase
import com.astraedus.nudge.ui.overlay.BlockOverlayActivity
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

    // Web domain blocking state
    @Volatile
    private var lastBlockedDomain: String? = null

    @Volatile
    private var grayscaleActiveForPackage: String? = null

    private val counterCache = CounterCacheRefresher()

    private lateinit var interactionHandler: InteractionHandler
    private lateinit var timeRemainingHandler: TimeRemainingHandler

    companion object {
        private const val DEBOUNCE_MS = 1000L

        val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.sec.android.inputmethod",
            "com.samsung.android.launcher",
        )

        val WINDOW_CHANGE_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )

        @Volatile
        var isOverlayActive = false

        @Volatile
        var passthroughManagerInstance: PassthroughManager? = null
            private set

        internal fun shouldClearForOwnPackageEvent(
            eventType: Int,
            className: String?,
            ownPackageName: String
        ): Boolean {
            return eventType in WINDOW_CHANGE_EVENT_TYPES &&
                className?.startsWith(ownPackageName) == true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
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

        interactionHandler = InteractionHandler(
            interactionTracker = entryPoint.interactionTracker(),
            counterOverlayManager = entryPoint.counterOverlayManager(),
            inAppDetector = entryPoint.inAppDetector(),
            timeRemainingHandler = timeRemainingHandler,
            counterCache = counterCache,
            logger = entryPoint.nudgeLogger(),
            startActivity = { intent -> startActivity(intent) }
        )

        entryPoint.nudgeLogger().i("accessibility service connected")

        serviceScope.launch {
            counterCache.forceRefresh { loadCounterCacheEntries() }
            entryPoint.nudgeLogger().d("counter cache eagerly populated packages=${counterCache.snapshot().size}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        if (isOverlayActive) {
            clearOverlays(applicationContext.packageName, "block_overlay_active")
            return
        }

        if (packageName == applicationContext.packageName) {
            if (isOwnAppWindowEvent(event)) {
                clearOverlays(packageName, "own_app_window")
            }
            return
        }

        if (packageName in SYSTEM_PACKAGES) {
            clearOverlays(packageName, "system_package")
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

        if (!counterCache.isEnabled(packageName)) {
            clearOverlays(packageName, "counter_disabled", markForeground = false)
        } else if (packageName != lastPackage) {
            interactionHandler.activeReelLabel = null
            interactionHandler.onAppChanged(packageName)
            timeRemainingHandler.resetDebounce()
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
            applicationContext.startActivity(overlayIntent)
            return
        }

        // Show time remaining overlay before passthrough check (awareness overlays always show)
        timeRemainingHandler.showIfNeeded(packageName)

        if (passthrough.shouldSkipForegroundEvaluation(packageName)) {
            entryPoint.nudgeLogger().d("skip evaluation package=$packageName reason=passthrough")
            // Ensure counter is visible post-delay (onAppChanged may not re-fire)
            if (counterCache.isEnabled(packageName) && !interactionHandler.isCounterVisible()) {
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

        try {
            interactionHandler.hideCounter()
            timeRemainingHandler.hide()
        } catch (e: Exception) {
            entryPoint.nudgeLogger().w("overlay clear failed package=$packageName", e)
        }
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
            // For non-SUPPORTED packages (e.g., React Native apps like Discord that don't
            // fire TYPE_VIEW_CLICKED), use content changes as a proxy for user interaction.
            interactionHandler.handleContentChanged(packageName)
            return
        }

        evaluateForegroundPackage(packageName)

        val now = System.currentTimeMillis()
        val lastTime = lastContentChangedTime[packageName] ?: 0L
        if ((now - lastTime) < contentChangedDebounceMs) return
        lastContentChangedTime[packageName] = now

        val rootNode = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val feature = entryPoint.inAppDetector().detectFeature(packageName, rootNode)
        val passthrough = entryPoint.passthroughManager()

        if (feature != null) {
            if (passthrough.shouldSkipFeatureEvaluation(packageName, feature.key)) {
                return
            }

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
                applicationContext.startActivity(overlayIntent)
            }

            is BlockDecision.Allow -> {
                entryPoint.usageRepository().logEvent(
                    UsageEvent(packageName = packageName)
                )
            }
        }
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
                .filter { it.showCounter || it.showTimeRemaining }
                .mapNotNull { rule ->
                    rule.packageName?.let { pkg ->
                        pkg to CounterCacheEntry(
                            autoKickAfter = rule.autoKickAfter,
                            showTimeRemaining = rule.showTimeRemaining,
                            dailyLimitMinutes = rule.dailyLimitMinutes,
                            autoKickCooldownSeconds = rule.autoKickCooldownSeconds
                        )
                    }
                }
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
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
