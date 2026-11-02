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
 * 2. **Ordering.** The unlock broadcast calls [stampIfStale]. The app window that follows may
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
     * The decision and the write are one `dataStore.edit` inside
     * [com.astraedus.nudge.data.preferences.NudgePreferences.recordFirstUnlock], not a read
     * followed by a write. That matters because the in-memory copy starts at
     * [FirstUnlockWindow.NO_STAMP] and only learns the stored value when the Flow emits: device QA
     * caught the consequence on the bench phone, where the unlock that CREATED the cache saw `0`,
     * re-stamped, and moved the window's end later. Two recorders in the same moment could also both
     * decide to write. The caller's cheap in-memory check is only a fast path; the answer that
     * writes is the stored one.
     *
     * The result also heals the in-memory copy, so a cache that was cold is correct from then on
     * without waiting for the Flow.
     */
    suspend fun stampIfStale(nowMs: Long): Long = stampLock.withLock {
        val result = runCatching { preferences.recordFirstUnlock(nowMs, zoneId()) }
            .getOrElse { error ->
                log.w("failed to record the first-unlock stamp", error)
                return@withLock FirstUnlockWindow.NO_STAMP
            }
        stamp = result.stampMs
        dayStartMinute = result.dayStartMinute
        if (result.written) nowMs else FirstUnlockWindow.NO_STAMP
    }

    private companion object {
        const val LOG_TAG = "FirstUnlock"
    }
}
