package com.astraedus.nudge.service

import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimeRemainingHandler(
    private val timeRemainingOverlayManager: TimeRemainingOverlayManagerApi,
    private val usageRepository: UsageProvider,
    private val preferences: GlobalEnabledProvider,
    private val counterCache: CounterCacheRefresher,
    private val passthroughManager: PassthroughManager,
    private val logger: NudgeLog,
    private val serviceScope: CoroutineScope,
    /**
     * Show the daily-limit hard block for (package, limit minutes).
     *
     * A callback rather than an intent this class builds itself. It used to own a `Context` and
     * start `BlockOverlayActivity` directly, which made it the one block-overlay launch that lived
     * outside `NudgeAccessibilityService` — and therefore outside the launch gate that stops a late
     * decision landing on top of whatever the user moved to (issue #31). This one is the most
     * exposed of the four: it fires from a 30-second clock tick, not from a foreground event.
     */
    private val onTimeLimitExceeded: (String, Int) -> Unit = { _, _ -> }
) : TimeRemainingHandlerApi {
    private var lastUpdateMs: Long = 0L
    private val updateIntervalMs = 30_000L

    fun showIfNeeded(packageName: String) {
        val entry = counterCache.getEntry(packageName) ?: return
        if (!entry.showTimeRemaining || entry.dailyLimitMinutes == null) return

        serviceScope.launch {
            val globalEnabled = preferences.isGlobalEnabled.first()
            if (!globalEnabled) return@launch

            if (!timeRemainingOverlayManager.isVisible()) {
                withContext(Dispatchers.Main) {
                    timeRemainingOverlayManager.show()
                }
            }
            lastUpdateMs = 0L
            maybeUpdate(packageName)
        }
    }

    override fun maybeUpdate(packageName: String) {
        val entry = counterCache.getEntry(packageName) ?: return
        if (!entry.showTimeRemaining || entry.dailyLimitMinutes == null) {
            timeRemainingOverlayManager.updateTimeRemaining(null, null)
            return
        }

        val now = System.currentTimeMillis()
        if ((now - lastUpdateMs) < updateIntervalMs) return
        lastUpdateMs = now

        serviceScope.launch {
            try {
                val usageMs = usageRepository.getDailyForegroundTimeMs(packageName)
                val limitMs = entry.dailyLimitMinutes.toLong() * 60L * 1000L
                val remainingMs = (limitMs - usageMs).coerceAtLeast(0L)
                withContext(Dispatchers.Main) {
                    timeRemainingOverlayManager.updateTimeRemaining(remainingMs, entry.dailyLimitMinutes)
                }

                if (remainingMs <= 0L) {
                    passthroughManager.clear()
                    withContext(Dispatchers.Main) {
                        timeRemainingOverlayManager.hide()
                        onTimeLimitExceeded(packageName, entry.dailyLimitMinutes)
                    }
                }
            } catch (e: Exception) {
                logger.w("time remaining update failed", e)
            }
        }
    }

    override fun resetDebounce() {
        lastUpdateMs = 0L
    }

    override fun hide() = timeRemainingOverlayManager.hide()
    fun isVisible() = timeRemainingOverlayManager.isVisible()
}
