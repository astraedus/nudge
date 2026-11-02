package com.astraedus.nudge.data.repository

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.engine.FirstUnlockState
import com.astraedus.nudge.domain.engine.FirstUnlockWindow
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.util.CrashSafeScope
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The first-unlock anchor, in memory, for the accessibility event path (spec 5.3/5.7).
 *
 * Two reasons this is not a `Flow.first()` at the point of use:
 *
 * 1. **Cost.** `ScheduleEvaluator` runs on every foreground-app event. A DataStore read there would
 *    be a suspension point on the hottest path this app has, for a value that changes twice a day.
 * 2. **Ordering.** [record] is called from the unlock broadcast. The app window that follows may
 *    arrive before DataStore has emitted the write, and the first app opened after an unlock is
 *    exactly the one the window is for. Writing the volatile FIRST and persisting afterwards makes
 *    the in-memory answer right immediately, with DataStore as the durable copy.
 *
 * The day start arrives from the preferences Flow, so the editor's "New day starts at" row is
 * reflected without a restart. [zoneId] is read per call rather than cached: a traveller's zone can
 * change while the app runs, and the stale check has to use the zone the device is in now.
 */
@Singleton
class FirstUnlockStateCache @Inject constructor(
    private val preferences: NudgePreferences,
    logger: NudgeLog,
) : FirstUnlockState {

    private val log = logger.tagged(LOG_TAG)
    private val scope = CrashSafeScope.create(
        name = "first-unlock",
        dispatcher = Dispatchers.Default,
        logger = { log }
    )

    @Volatile private var stamp: Long = FirstUnlockWindow.NO_STAMP
    @Volatile private var dayStartMinute: Int = FirstUnlockWindow.DEFAULT_DAY_START_MINUTE

    init {
        scope.launch {
            preferences.firstUnlockAt.collect { value -> stamp = value }
        }
        scope.launch {
            preferences.dayStartMinute.collect { value -> dayStartMinute = value }
        }
    }

    override fun firstUnlockAt(): Long = stamp

    override fun dayStartMinute(): Int = dayStartMinute

    override fun zoneId(): ZoneId = ZoneId.systemDefault()

    /**
     * Stamp [nowMs] as this logical day's first unlock and persist it. Called on `USER_PRESENT` and
     * on the app-window fallback; the caller decides whether the existing stamp is still fresh
     * ([FirstUnlockWindow.shouldStamp]), so this does not read the clock itself.
     */
    suspend fun record(nowMs: Long) {
        stamp = nowMs
        runCatching { preferences.setFirstUnlockAt(nowMs) }
            .onFailure { log.w("failed to persist the first-unlock stamp", it) }
    }

    private companion object {
        const val LOG_TAG = "FirstUnlock"
    }
}
