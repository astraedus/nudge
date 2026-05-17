package com.astraedus.nudge.service

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.logging.NudgeLog

class InteractionHandler(
    private val interactionTracker: InteractionTracker,
    private val counterOverlayManager: CounterOverlayManagerApi,
    private val inAppDetector: InAppDetectorApi,
    private val timeRemainingHandler: TimeRemainingHandlerApi,
    private val counterCache: CounterCacheRefresher,
    private val logger: NudgeLog,
    private val startActivity: (Intent) -> Unit
) {
    private var lastClickTime: Long = 0L
    private val clickDebounceMs = 300L
    private var lastScrollTime: Long = 0L
    private val scrollDebounceMs = 500L
    private var lastContentChangeTime: Long = 0L
    private val contentChangeDebounceMs = 1000L
    var activeReelLabel: String? = null

    fun handleViewClicked(packageName: String) {
        if (!counterCache.isEnabled(packageName)) return
        val now = System.currentTimeMillis()
        if ((now - lastClickTime) < clickDebounceMs) return
        lastClickTime = now
        if (packageName in InAppDetector.SUPPORTED_PACKAGES) return

        val count = interactionTracker.recordInteraction(packageName)
        showOrUpdateCounter(count, "taps")
    }

    fun handleViewScrolled(packageName: String, rootNodeProvider: () -> AccessibilityNodeInfo?) {
        if (!counterCache.isEnabled(packageName)) return
        val now = System.currentTimeMillis()
        if ((now - lastScrollTime) < scrollDebounceMs) return
        lastScrollTime = now
        if (packageName !in InAppDetector.SUPPORTED_PACKAGES) return

        val label = activeReelLabel ?: run {
            val rootNode = rootNodeProvider()
            val feature = if (rootNode != null) {
                inAppDetector.detectFeature(packageName, rootNode)
            } else null

            when (feature) {
                InAppDetector.Feature.SHORTS -> "shorts"
                InAppDetector.Feature.REELS -> "reels"
                InAppDetector.Feature.TIKTOK_FEED -> "videos"
                InAppDetector.Feature.EXPLORE, null -> return
            }.also { activeReelLabel = it }
        }

        val count = interactionTracker.recordInteraction(packageName)
        showOrUpdateCounter(count, label)
    }

    private fun showOrUpdateCounter(count: InteractionTracker.SessionCount, label: String) {
        try {
            if (!counterOverlayManager.isVisible()) {
                counterOverlayManager.show(label)
            }
            counterOverlayManager.updateCount(count.sessionCount, count.dailyTotal)
            timeRemainingHandler.maybeUpdate(count.packageName)
            checkAutoKick(count)
        } catch (e: Exception) {
            logger.w("counter overlay update failed package=${count.packageName}", e)
        }
    }

    private fun checkAutoKick(count: InteractionTracker.SessionCount) {
        val cacheEntry = counterCache.getEntry(count.packageName) ?: return
        val autoKickAfter = cacheEntry.autoKickAfter ?: return
        if (count.sessionCount < autoKickAfter) return

        logger.i("auto-kick triggered package=${count.packageName} session=${count.sessionCount} threshold=$autoKickAfter")

        val cooldownSeconds = cacheEntry.autoKickCooldownSeconds
        if (cooldownSeconds > 0) {
            interactionTracker.setCooldown(count.packageName, cooldownSeconds.toLong() * 1000L)
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        interactionTracker.resetSession(count.packageName)
        counterOverlayManager.hide()
    }

    /**
     * Called when the foreground app changes to a counter-enabled package.
     * Shows the counter overlay immediately with current session count so the user
     * sees the counter as soon as they enter the app (not only after first interaction).
     */
    fun onAppChanged(packageName: String) {
        interactionTracker.onAppChanged(packageName)
        // Show counter immediately on app entry so user sees it right away
        if (counterCache.isEnabled(packageName)) {
            val sessionCount = interactionTracker.getSessionCount(packageName)
            val dailyTotal = interactionTracker.getDailyTotal(packageName)
            val label = activeReelLabel ?: "taps"
            try {
                if (!counterOverlayManager.isVisible()) {
                    counterOverlayManager.show(label)
                }
                counterOverlayManager.updateCount(sessionCount, dailyTotal)
            } catch (e: Exception) {
                logger.w("counter overlay show on app entry failed package=$packageName", e)
            }
        }
    }

    /**
     * Handles content change events for packages that don't fire TYPE_VIEW_CLICKED
     * (e.g., React Native apps like Discord). Uses content changes as a proxy for
     * user interaction. Debounced at 1 second to avoid overcounting from rapid
     * React Native re-renders.
     */
    fun handleContentChanged(packageName: String) {
        if (!counterCache.isEnabled(packageName)) return
        // Only count content changes for non-SUPPORTED packages (Supported ones use scroll detection)
        if (packageName in InAppDetector.SUPPORTED_PACKAGES) return
        val now = System.currentTimeMillis()
        if ((now - lastContentChangeTime) < contentChangeDebounceMs) return
        lastContentChangeTime = now

        val count = interactionTracker.recordInteraction(packageName)
        showOrUpdateCounter(count, "taps")
    }

    fun hideCounter() {
        if (counterOverlayManager.isVisible()) counterOverlayManager.hide()
    }
}
