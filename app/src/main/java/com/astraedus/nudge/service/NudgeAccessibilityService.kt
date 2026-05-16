package com.astraedus.nudge.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.model.BlockDecision
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
        fun blockRuleRepository(): BlockRuleRepository
        fun nudgeLogger(): NudgeLogger
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

    @Volatile
    private var grayscaleActiveForPackage: String? = null

    private var lastClickTime: Long = 0L
    private val clickDebounceMs = 300L
    private var lastScrollTime: Long = 0L
    private val scrollDebounceMs = 500L

    private val counterCache = CounterCacheRefresher()

    private var lastTimeRemainingUpdateMs: Long = 0L
    private val timeRemainingUpdateIntervalMs = 30_000L

    companion object {
        private const val DEBOUNCE_MS = 1000L

        private val SYSTEM_PACKAGES = setOf(
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

        private val WINDOW_CHANGE_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )

        @Volatile
        var isOverlayActive = false

        @Volatile
        var lastPassthroughPackage: String? = null
        @Volatile
        var lastPassthroughFeature: String? = null
        @Volatile
        var lastPassthroughTime: Long = 0L

        fun grantPassthrough(packageName: String, featureKey: String? = null) {
            lastPassthroughPackage = packageName
            lastPassthroughFeature = featureKey
            lastPassthroughTime = System.currentTimeMillis()
        }

        internal fun resetPassthroughForTests() {
            lastPassthroughPackage = null
            lastPassthroughFeature = null
            lastPassthroughTime = 0L
        }

        internal fun isPassthroughGranted(packageName: String): Boolean {
            return packageName == lastPassthroughPackage
        }

        internal fun shouldSkipForegroundEvaluationForPassthrough(packageName: String): Boolean {
            return isPassthroughGranted(packageName)
        }

        internal fun shouldSkipFeatureEvaluationForPassthrough(
            packageName: String,
            featureKey: String
        ): Boolean {
            return isPassthroughGranted(packageName) && lastPassthroughFeature == featureKey
        }

        internal fun clearPassthroughIfAppChanged(packageName: String): Boolean {
            if (lastPassthroughPackage == null || packageName == lastPassthroughPackage) return false
            lastPassthroughPackage = null
            lastPassthroughFeature = null
            lastPassthroughTime = 0L
            return true
        }

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
        entryPoint.nudgeLogger().i("accessibility service connected")

        // Eagerly populate counter cache so the first scroll event works
        serviceScope.launch {
            counterCache.forceRefresh { loadCounterCacheEntries() }
            entryPoint.nudgeLogger().d("counter cache eagerly populated packages=${counterCache.snapshot().size}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        if (isOverlayActive) {
            clearCounterOverlay(applicationContext.packageName, "block_overlay_active")
            return
        }

        if (packageName == applicationContext.packageName) {
            if (isOwnAppWindowEvent(event)) {
                clearCounterOverlay(packageName, "own_app_window")
            }
            return
        }

        if (packageName in SYSTEM_PACKAGES) {
            clearCounterOverlay(packageName, "system_package")
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
                handleViewClicked(packageName)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleViewScrolled(packageName)
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

        val grayscalePkg = grayscaleActiveForPackage
        if (grayscalePkg != null && grayscalePkg != packageName) {
            entryPoint.grayscaleManager().disableGrayscale()
            grayscaleActiveForPackage = null
        }

        if (!counterCache.isEnabled(packageName)) {
            clearCounterOverlay(packageName, "counter_disabled", markForeground = false)
        } else if (packageName != lastPackage) {
            activeReelLabel = null
            entryPoint.interactionTracker().onAppChanged(packageName)
            lastTimeRemainingUpdateMs = 0L
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

        if (shouldSkipForegroundEvaluationForPassthrough(packageName)) {
            entryPoint.nudgeLogger().d("skip evaluation package=$packageName reason=passthrough")
            return
        }

        if (clearPassthroughIfAppChanged(packageName)) {
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

            val decision = entryPoint.evaluateBlockUseCase().invoke(packageName)
            entryPoint.nudgeLogger().d("whole-app decision package=$packageName decision=$decision")
            handleDecision(decision, packageName)
        }
    }

    private fun clearCounterOverlay(
        packageName: String,
        reason: String,
        markForeground: Boolean = true
    ) {
        activeReelLabel = null
        if (markForeground) {
            lastPackage = packageName
        }
        entryPoint.interactionTracker().onAppChanged(packageName)

        try {
            val manager = entryPoint.counterOverlayManager()
            if (manager.isVisible()) {
                manager.hide()
            }
        } catch (e: Exception) {
            entryPoint.nudgeLogger().w("counter overlay clear failed package=$packageName", e)
        }
    }

    private fun handleWindowContentChanged(packageName: String, event: AccessibilityEvent) {
        if (packageName !in InAppDetector.SUPPORTED_PACKAGES) return

        evaluateForegroundPackage(packageName)

        val now = System.currentTimeMillis()
        val lastTime = lastContentChangedTime[packageName] ?: 0L
        if ((now - lastTime) < contentChangedDebounceMs) return
        lastContentChangedTime[packageName] = now

        val rootNode = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val feature = entryPoint.inAppDetector().detectFeature(packageName, rootNode)

        if (feature != null) {
            if (shouldSkipFeatureEvaluationForPassthrough(packageName, feature.key)) {
                return
            }

            serviceScope.launch {
                val globalEnabled = entryPoint.nudgePreferences().isGlobalEnabled.first()
                if (!globalEnabled) return@launch

                val decision = entryPoint.evaluateBlockUseCase().invoke(
                    packageName = packageName,
                    detectedFeature = feature.key,
                    includeWholeAppRulesForFeature = !shouldSkipForegroundEvaluationForPassthrough(packageName)
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

    // --- Interaction counter ---

    private var activeReelLabel: String? = null

    private fun handleViewClicked(packageName: String) {
        if (!counterCache.isEnabled(packageName)) return

        val now = System.currentTimeMillis()
        if ((now - lastClickTime) < clickDebounceMs) return
        lastClickTime = now

        if (packageName in InAppDetector.SUPPORTED_PACKAGES) return

        val count = entryPoint.interactionTracker().recordInteraction(packageName)
        showOrUpdateCounter(count, "taps")
    }

    private fun handleViewScrolled(packageName: String) {
        if (!counterCache.isEnabled(packageName)) return

        val now = System.currentTimeMillis()
        if ((now - lastScrollTime) < scrollDebounceMs) return
        lastScrollTime = now

        if (packageName !in InAppDetector.SUPPORTED_PACKAGES) return

        val label = activeReelLabel ?: run {
            val rootNode = try { rootInActiveWindow } catch (_: Exception) { null }
            val feature = if (rootNode != null) {
                entryPoint.inAppDetector().detectFeature(packageName, rootNode)
            } else null

            when (feature) {
                InAppDetector.Feature.SHORTS -> "shorts"
                InAppDetector.Feature.REELS -> "reels"
                InAppDetector.Feature.TIKTOK_FEED -> "videos"
                InAppDetector.Feature.EXPLORE, null -> return
            }.also { activeReelLabel = it }
        }

        val count = entryPoint.interactionTracker().recordInteraction(packageName)
        showOrUpdateCounter(count, label)
    }

    private fun showOrUpdateCounter(
        count: InteractionTracker.SessionCount,
        label: String
    ) {
        try {
            val manager = entryPoint.counterOverlayManager()
            if (!manager.isVisible()) {
                manager.show(label)
            }
            manager.updateCount(count.sessionCount, count.dailyTotal)
            maybeUpdateTimeRemaining(count.packageName, manager)
            checkAutoKick(count, manager)
        } catch (e: Exception) {
            entryPoint.nudgeLogger().w(
                "counter overlay update failed package=${count.packageName}", e
            )
        }
    }

    private fun checkAutoKick(
        count: InteractionTracker.SessionCount,
        manager: CounterOverlayManager
    ) {
        val cacheEntry = counterCache.getEntry(count.packageName) ?: return
        val autoKickAfter = cacheEntry.autoKickAfter ?: return
        if (count.sessionCount < autoKickAfter) return

        entryPoint.nudgeLogger().i(
            "auto-kick triggered package=${count.packageName} " +
                "session=${count.sessionCount} threshold=$autoKickAfter"
        )

        val cooldownSeconds = cacheEntry.autoKickCooldownSeconds
        if (cooldownSeconds > 0) {
            entryPoint.interactionTracker().setCooldown(
                count.packageName,
                cooldownSeconds.toLong() * 1000L
            )
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)

        entryPoint.interactionTracker().resetSession(count.packageName)
        manager.hide()
    }

    private fun maybeUpdateTimeRemaining(
        packageName: String,
        manager: CounterOverlayManager
    ) {
        val entry = counterCache.getEntry(packageName) ?: return
        if (!entry.showTimeRemaining || entry.dailyLimitMinutes == null) {
            manager.updateTimeRemaining(null, null)
            return
        }

        val now = System.currentTimeMillis()
        if ((now - lastTimeRemainingUpdateMs) < timeRemainingUpdateIntervalMs) return
        lastTimeRemainingUpdateMs = now

        serviceScope.launch {
            try {
                val usageMs = entryPoint.usageRepository().getDailyForegroundTimeMs(packageName)
                val limitMs = entry.dailyLimitMinutes.toLong() * 60L * 1000L
                val remainingMs = (limitMs - usageMs).coerceAtLeast(0L)
                withContext(Dispatchers.Main) {
                    manager.updateTimeRemaining(remainingMs, entry.dailyLimitMinutes)
                }
            } catch (e: Exception) {
                entryPoint.nudgeLogger().w("time remaining update failed", e)
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
        if (grayscaleActiveForPackage != null) {
            entryPoint.grayscaleManager().disableGrayscale()
            grayscaleActiveForPackage = null
        }
        serviceScope.cancel()
    }
}
