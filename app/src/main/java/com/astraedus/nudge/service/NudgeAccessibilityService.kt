package com.astraedus.nudge.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
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

    /** Track whether BlockOverlayActivity is currently showing. */
    @Volatile
    private var overlayShowing = false

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
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Skip our own package
        if (packageName == applicationContext.packageName) return

        // Skip system packages
        if (packageName in SYSTEM_PACKAGES) return

        // Skip if the overlay is currently showing
        if (isOverlayActive) return

        // Debounce: don't re-evaluate if same package was checked < 1 second ago
        val now = System.currentTimeMillis()
        if (packageName == lastPackage && (now - lastEvalTime) < DEBOUNCE_MS) return

        lastPackage = packageName
        lastEvalTime = now

        serviceScope.launch {
            // Check if global monitoring is enabled
            val globalEnabled = entryPoint.nudgePreferences().isGlobalEnabled.first()
            if (!globalEnabled) return@launch

            val decision = entryPoint.evaluateBlockUseCase().invoke(packageName)

            when (decision) {
                is BlockDecision.Block -> {
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
    }

    override fun onInterrupt() {
        // Required override -- nothing to clean up on interrupt
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
