package com.astraedus.nudge.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
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

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(packageName, event)
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

        // Rate limit
        val now = System.currentTimeMillis()
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

    override fun onInterrupt() {
        // Required override -- nothing to clean up on interrupt
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disable grayscale on service shutdown to avoid leaving the screen gray
        if (grayscaleActiveForPackage != null) {
            entryPoint.grayscaleManager().disableGrayscale()
            grayscaleActiveForPackage = null
        }
        serviceScope.cancel()
    }
}
