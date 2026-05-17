package com.astraedus.nudge.service

import android.content.Context
import android.content.Intent
import com.astraedus.nudge.ui.overlay.BlockOverlayActivity
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
    private val onTimeLimitExceeded: (String, Int) -> Unit = { _, _ -> }
) : TimeRemainingHandlerApi {
    private var lastUpdateMs: Long = 0L
    private val updateIntervalMs = 30_000L

    /**
     * Secondary constructor preserving the original call-site API (Context-based).
     * Creates the onTimeLimitExceeded lambda from a Context + BlockOverlayActivity intent.
     */
    constructor(
        timeRemainingOverlayManager: TimeRemainingOverlayManagerApi,
        usageRepository: UsageProvider,
        preferences: GlobalEnabledProvider,
        counterCache: CounterCacheRefresher,
        passthroughManager: PassthroughManager,
        logger: NudgeLog,
        context: Context,
        serviceScope: CoroutineScope
    ) : this(
        timeRemainingOverlayManager = timeRemainingOverlayManager,
        usageRepository = usageRepository,
        preferences = preferences,
        counterCache = counterCache,
        passthroughManager = passthroughManager,
        logger = logger,
        serviceScope = serviceScope,
        onTimeLimitExceeded = { packageName, dailyLimitMinutes ->
            val intent = Intent(context, BlockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(BlockOverlayActivity.EXTRA_BLOCK_MODE, "HARD_BLOCK")
                putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(BlockOverlayActivity.EXTRA_RULE_NAME, "Daily limit reached")
                putExtra(BlockOverlayActivity.EXTRA_DAILY_TIME_REMAINING_MS, 0L)
                putExtra(BlockOverlayActivity.EXTRA_DAILY_LIMIT_MINUTES, dailyLimitMinutes)
            }
            context.startActivity(intent)
        }
    )

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
