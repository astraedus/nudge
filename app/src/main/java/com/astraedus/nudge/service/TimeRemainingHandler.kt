package com.astraedus.nudge.service

import android.content.Context
import android.content.Intent
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.ui.overlay.BlockOverlayActivity
import com.astraedus.nudge.util.NudgeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimeRemainingHandler(
    private val timeRemainingOverlayManager: TimeRemainingOverlayManager,
    private val usageRepository: UsageRepository,
    private val preferences: NudgePreferences,
    private val counterCache: CounterCacheRefresher,
    private val passthroughManager: PassthroughManager,
    private val logger: NudgeLogger,
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
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

    fun maybeUpdate(packageName: String) {
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
                        val intent = Intent(context, BlockOverlayActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra(BlockOverlayActivity.EXTRA_BLOCK_MODE, "HARD_BLOCK")
                            putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                            putExtra(BlockOverlayActivity.EXTRA_RULE_NAME, "Daily limit reached")
                            putExtra(BlockOverlayActivity.EXTRA_DAILY_TIME_REMAINING_MS, 0L)
                            putExtra(BlockOverlayActivity.EXTRA_DAILY_LIMIT_MINUTES, entry.dailyLimitMinutes!!)
                        }
                        context.startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                logger.w("time remaining update failed", e)
            }
        }
    }

    fun resetDebounce() {
        lastUpdateMs = 0L
    }

    fun hide() = timeRemainingOverlayManager.hide()
    fun isVisible() = timeRemainingOverlayManager.isVisible()
}
