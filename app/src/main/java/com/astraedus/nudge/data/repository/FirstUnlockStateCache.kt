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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** Serialises [stampIfStale]: two events in the same instant must not both decide to write. */
    private val stampLock = Mutex()

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
     * Decide, against the PERSISTED anchor, whether [nowMs] is this logical day's first unlock, and
     * write it if so. Returns the stamp written, or [FirstUnlockWindow.NO_STAMP] when a fresh one
     * was kept.
     *
     * The persisted read is the point of this function, not a belt and braces. The in-memory stamp
     * starts at [FirstUnlockWindow.NO_STAMP] and only reaches its stored value when the Flow emits,
     * which is a few milliseconds after this object is first touched. Device QA caught the
     * consequence: the unlock that CREATED the cache saw `0`, concluded "no anchor today", and
     * re-stamped, moving the window's end later and blocking longer than the rule asked. So the
     * caller's cheap in-memory check is only a fast path; the answer that writes is this one.
     *
     * The day start is read the same way, for the same reason: the cached copy is also empty at
     * that moment, and a wrong day start can make a fresh stamp look stale.
     */
    suspend fun stampIfStale(nowMs: Long): Long = stampLock.withLock {
        val persistedStamp = runCatching { preferences.firstUnlockAt.first() }
            .getOrElse { stamp }
        val persistedDayStart = runCatching { preferences.dayStartMinute.first() }
            .getOrDefault(dayStartMinute)
        dayStartMinute = persistedDayStart
        val anchor = if (persistedStamp != FirstUnlockWindow.NO_STAMP) persistedStamp else stamp

        if (!FirstUnlockWindow.shouldStamp(nowMs, anchor, persistedDayStart, zoneId())) {
            // Keep the better of the two: the stored one is authoritative, and adopting it here
            // also heals the in-memory copy without waiting for the Flow.
            stamp = anchor
            return@withLock FirstUnlockWindow.NO_STAMP
        }

        stamp = nowMs
        runCatching { preferences.setFirstUnlockAt(nowMs) }
            .onFailure { log.w("failed to persist the first-unlock stamp", it) }
        nowMs
    }

    private companion object {
        const val LOG_TAG = "FirstUnlock"
    }
}
