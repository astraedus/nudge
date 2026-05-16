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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            NudgeAccessibilityEntryPoint::class.java
        )
    }

    /** Package last evaluated and the timestamp -- used for debouncing. */
    private var lastPackage: String? = null
    private var lastEvalTime: Long = 0L

    /** Rate-limit TYPE_WINDOW_CONTENT_CHANGED per package (in-app detection). */
    private val lastContentChangedTime = mutableMapOf<String, Long>()
    private val contentChangedDebounceMs = 2000L

    /** Track which package triggered grayscale so we know when to disable. */
    @Volatile
    private var grayscaleActiveForPackage: String? = null

    /** Interaction counter: debounce fields. */
    private var lastClickTime: Long = 0L
    private val clickDebounceMs = 300L
    private var lastScrollTime: Long = 0L
    private val scrollDebounceMs = 500L

    /** Cache of packages with showCounter enabled. Refreshed every 10 seconds. */
    private val counterEnabledCache = mutableSetOf<String>()
    private var lastCacheRefresh: Long = 0L
    private val cacheRefreshIntervalMs = 10_000L

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

        /** Set by BlockOverlayActivity when it starts/stops. */
        @Volatile
        var isOverlayActive = false

        /** Called by BlockOverlayActivity when delay/breathing completes — grants cooldown. */
        @Volatile
        var lastPassthroughPackage: String? = null
        @Volatile
        var lastPassthroughTime: Long = 0L

        fun grantPassthrough(packageName: String) {
            lastPassthroughPackage = packageName
            lastPassthroughTime = System.currentTimeMillis()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        entryPoint.counterOverlayManager().setServiceContext(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // Skip our own package
        if (packageName == applicationContext.packageName) return

        // Skip system packages
        if (packageName in SYSTEM_PACKAGES) return

        // Skip if the overlay is currently showing
        if (isOverlayActive) return

        // Periodically refresh which packages have the counter enabled
        refreshCounterCacheIfNeeded()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(packageName)
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

    /**
     * Original flow: foreground app changed. Evaluate whole-app rules.
     * Also handles grayscale toggle when navigating away from a grayscale-blocked app.
     */
    private fun handleWindowStateChanged(packageName: String) {
        val now = System.currentTimeMillis()

        // If user navigated away from a grayscale-enabled app, disable grayscale
        val grayscalePkg = grayscaleActiveForPackage
        if (grayscalePkg != null && grayscalePkg != packageName) {
            entryPoint.grayscaleManager().disableGrayscale()
            grayscaleActiveForPackage = null
        }

        // Counter overlay: hide when switching to an app without counter, reset session for new app
        if (packageName !in counterEnabledCache) {
            activeReelLabel = null
            entryPoint.counterOverlayManager().hide()
        } else if (packageName != lastPackage) {
            activeReelLabel = null
            entryPoint.interactionTracker().onAppChanged(packageName)
        }

        // Post-overlay passthrough: user completed delay/breathing, let them use app until they leave
        if (packageName == lastPassthroughPackage) {
            return
        }

        // User switched to a different app — clear passthrough
        if (lastPassthroughPackage != null && packageName != lastPassthroughPackage) {
            lastPassthroughPackage = null
            lastPassthroughTime = 0L
        }

        // Debounce: don't re-evaluate if same package was checked < 1 second ago
        if (packageName == lastPackage && (now - lastEvalTime) < DEBOUNCE_MS) return

        lastPackage = packageName
        lastEvalTime = now

        serviceScope.launch {
            val globalEnabled = entryPoint.nudgePreferences().isGlobalEnabled.first()
            if (!globalEnabled) return@launch

            val decision = entryPoint.evaluateBlockUseCase().invoke(packageName)
            handleDecision(decision, packageName)
        }
    }

    /**
     * In-app content changed: run feature detection for supported apps.
     * Rate-limited to once per 2 seconds per package to avoid performance issues.
     */
    private fun handleWindowContentChanged(packageName: String, event: AccessibilityEvent) {
        // Only inspect supported packages
        if (packageName !in InAppDetector.SUPPORTED_PACKAGES) return

        // Post-overlay passthrough applies to in-app detection too
        if (packageName == lastPassthroughPackage) return

        val now = System.currentTimeMillis()

        // Rate limit
        val lastTime = lastContentChangedTime[packageName] ?: 0L
        if ((now - lastTime) < contentChangedDebounceMs) return
        lastContentChangedTime[packageName] = now

        val rootNode = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val feature = entryPoint.inAppDetector().detectFeature(packageName, rootNode)

        // Only re-evaluate if a specific feature was detected
        if (feature != null) {
            serviceScope.launch {
                val globalEnabled = entryPoint.nudgePreferences().isGlobalEnabled.first()
                if (!globalEnabled) return@launch

                val decision = entryPoint.evaluateBlockUseCase().invoke(packageName, feature.key)
                handleDecision(decision, packageName)
            }
        }
    }

    private suspend fun handleDecision(decision: BlockDecision, packageName: String) {
        when (decision) {
            is BlockDecision.Block -> {
                // Enable grayscale if the rule requests it
                if (decision.grayscale) {
                    entryPoint.grayscaleManager().enableGrayscale()
                    grayscaleActiveForPackage = packageName
                }

                // Log the usage event as blocked
                entryPoint.usageRepository().logEvent(
                    UsageEvent(
                        packageName = packageName,
                        wasBlocked = true,
                        blockMode = decision.mode.name
                    )
                )

                // Launch the overlay activity
                val overlayIntent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockOverlayActivity.EXTRA_BLOCK_MODE, decision.mode.name)
                    putExtra(BlockOverlayActivity.EXTRA_DELAY_SECONDS, decision.delaySeconds)
                    putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                }
                applicationContext.startActivity(overlayIntent)
            }

            is BlockDecision.Allow -> {
                // Log normal usage event (not blocked)
                entryPoint.usageRepository().logEvent(
                    UsageEvent(packageName = packageName)
                )
            }
        }
    }

    // --- Interaction counter handlers ---

    private fun handleViewClicked(packageName: String) {
        if (packageName !in counterEnabledCache) return

        val now = System.currentTimeMillis()
        if ((now - lastClickTime) < clickDebounceMs) return
        lastClickTime = now

        // Reels/Shorts apps use scroll counting, not tap counting
        if (packageName in InAppDetector.SUPPORTED_PACKAGES) return

        val count = entryPoint.interactionTracker().recordInteraction(packageName)
        updateCounterOverlay(count, "taps")
    }

    /** Once a reel feature is detected, keep counting scrolls without re-checking the tree. */
    private var activeReelLabel: String? = null

    private fun handleViewScrolled(packageName: String) {
        if (packageName !in counterEnabledCache) return

        val now = System.currentTimeMillis()
        if ((now - lastScrollTime) < scrollDebounceMs) return
        lastScrollTime = now

        // Only count scrolls for reels/shorts-type apps
        if (packageName !in InAppDetector.SUPPORTED_PACKAGES) return

        // If counter is already showing, keep counting without expensive tree inspection
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
        updateCounterOverlay(count, label)
    }

    private fun updateCounterOverlay(
        count: InteractionTracker.SessionCount,
        label: String
    ) {
        val manager = entryPoint.counterOverlayManager()
        if (!manager.isVisible()) {
            manager.show(label)
        }
        manager.updateCount(count.sessionCount, count.dailyTotal)
    }

    private fun refreshCounterCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if ((now - lastCacheRefresh) < cacheRefreshIntervalMs) return
        lastCacheRefresh = now

        serviceScope.launch {
            val rules = entryPoint.blockRuleRepository().getEnabledRules().first()
            val enabled = rules
                .filter { it.showCounter }
                .mapNotNull { it.packageName }
                .toSet()
            counterEnabledCache.clear()
            counterEnabledCache.addAll(enabled)
        }
    }

    override fun onInterrupt() {
        // Required override -- nothing to clean up on interrupt
    }

    override fun onDestroy() {
        super.onDestroy()
        // Hide counter overlay on service shutdown
        entryPoint.counterOverlayManager().hide()
        // Disable grayscale on service shutdown to avoid leaving the screen gray
        if (grayscaleActiveForPackage != null) {
            entryPoint.grayscaleManager().disableGrayscale()
            grayscaleActiveForPackage = null
        }
        serviceScope.cancel()
    }
}
