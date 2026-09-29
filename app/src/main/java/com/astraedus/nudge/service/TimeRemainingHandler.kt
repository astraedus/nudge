package com.astraedus.nudge.service

import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The daily-budget watcher: it draws the "42m left" readout for the rules that asked for one, and it
 * ENFORCES every daily limit, readout or not.
 *
 * The two jobs are not the same and the name only describes the first. Enforcement lives here
 * because this is the one place that re-reads the budget off a clock rather than off an accessibility
 * event, and a budget that is only re-read on app entry can be outlived by anyone who does not leave
 * (the v1.18.4 fix; see [maybeUpdate] and `CounterCacheEntry.needsForegroundTimeTick`).
 */
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
     * outside `NudgeAccessibilityService`, and therefore outside the launch gate that stops a late
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

    /**
     * Re-read the budget for [packageName] and act on it: refresh the readout if this rule draws
     * one, and hard-block once the budget reaches zero.
     *
     * **The two halves have DIFFERENT conditions, and collapsing them into one was the bug.** The
     * readout needs `showTimeRemaining`; the ENFORCEMENT needs only a limit to exist. Until v1.18.4
     * both sat behind `showTimeRemaining && dailyLimitMinutes != null`, so a plain daily limit was
     * re-read by nothing and the budget was only noticed on the next app entry (measured: 150s on a
     * 1-minute budget, one evaluation, no block). Anyone still inside the app simply outlived it.
     */
    override fun maybeUpdate(packageName: String) {
        val entry = counterCache.getEntry(packageName) ?: return
        val limitMinutes = entry.dailyLimitMinutes
        // No budget at all: nothing to read, nothing to show, and (the readout being meaningless
        // without a limit) the overlay is cleared exactly as it was before.
        if (limitMinutes == null) {
            timeRemainingOverlayManager.updateTimeRemaining(null, null)
            return
        }
        val drawsReadout = entry.showTimeRemaining
        if (!drawsReadout) timeRemainingOverlayManager.updateTimeRemaining(null, null)

        val now = System.currentTimeMillis()
        if ((now - lastUpdateMs) < updateIntervalMs) return
        lastUpdateMs = now

        serviceScope.launch {
            try {
                val usageMs = usageRepository.getDailyForegroundTimeMs(packageName)
                val limitMs = limitMinutes.toLong() * 60L * 1000L
                val remainingMs = (limitMs - usageMs).coerceAtLeast(0L)
                if (drawsReadout) {
                    withContext(Dispatchers.Main) {
                        timeRemainingOverlayManager.updateTimeRemaining(remainingMs, limitMinutes)
                    }
                }

                if (remainingMs <= 0L) {
                    // BEFORE the launch, and it is the (c) half of this fix: a completed
                    // delay/hold/breathing grant must not let someone outlive their budget. The
                    // grant suppresses the EVENT path (`shouldSkipForegroundEvaluation`), so
                    // without this the user would be blocked here and then walk straight back in.
                    // Nothing on the tick path consults the grant, so this clear is the whole
                    // interaction between the two.
                    passthroughManager.clear()
                    withContext(Dispatchers.Main) {
                        timeRemainingOverlayManager.hide()
                        onTimeLimitExceeded(packageName, limitMinutes)
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
