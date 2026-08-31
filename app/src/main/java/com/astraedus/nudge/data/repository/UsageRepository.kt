package com.astraedus.nudge.data.repository

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.astraedus.nudge.data.db.dao.UsageEventDao
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.UsageEventKey
import com.astraedus.nudge.data.export.HistoryMerge
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.service.UsageProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageEventDao: UsageEventDao,
    private val timeTracker: TimeTracker,
    private val screenTimeProvider: ScreenTimeProvider
) : UsageProvider {

    suspend fun logEvent(event: UsageEvent) = usageEventDao.insert(event)

    /** Every event, oldest first — the corpus an export carries. */
    suspend fun getAllEventsForExport(): List<UsageEvent> = usageEventDao.getAllForExport()

    /** Identities of the events already stored in an inclusive timestamp window. */
    suspend fun getEventKeysInRange(from: Long, to: Long): List<UsageEventKey> =
        usageEventDao.getKeysInRange(from, to)

    /**
     * Restores imported events in ONE DAO call, because one `@Insert` call is one Room
     * transaction and that atomicity is load-bearing: re-import dedup is by key MEMBERSHIP,
     * so a crash that persisted only part of an import would make every later re-import of
     * the file skip a whole key group — the unstored copy of an intra-file duplicate would
     * be unrecoverable. (The old 500-row chunking added no safety anyway: `@Insert` runs
     * one prepared statement per row, never a single unbounded statement.)
     */
    suspend fun insertEvents(events: List<UsageEvent>) {
        if (events.isNotEmpty()) usageEventDao.insertAll(events)
    }

    /**
     * Today's foreground time for [packageName], the number the daily budget is spent against and
     * the clock the time-based auto-kick measures its session with.
     *
     * **Delegates to [ScreenTimeProvider], deliberately.** This used to be its own `queryEvents`
     * walk, and it was the FOURTH copy of the RESUMED/PAUSED pairing loop in the app, the three in
     * `ScreenTimeProvider` were collapsed into [com.astraedus.nudge.domain.usage.ForegroundSpanTracker]
     * in v1.15.1 after a phone reported ~17 hours of screen time before lunchtime, but this one
     * lives in a different file and the sweep missed it. It still carried both defects that fix
     * exists to remove:
     *
     *  - **`if (event.packageName != packageName) continue`** filtered the stream BEFORE pairing, so
     *    it could not see the event that ended this app's span, another app coming to the
     *    foreground. Filter the spans, never the stream.
     *  - **`totalMs += now - lastResumed`** extended a still-open span to the present with no cap.
     *    A single dropped `ACTIVITY_PAUSED` (a killed process, a screen-off with no pause) was
     *    therefore worth every minute since, so this reading could jump by hours at once. On this
     *    code path that means a time-based auto-kick firing out of nowhere, long after the user
     *    stopped doing anything, which is precisely the unexplained kick device QA saw late in a
     *    session full of earlier tests.
     *
     * Going through the one tracker buys the guarantees back: only one app is foreground at a time,
     * screen-off / keyguard / shutdown end a span, and an INFERRED open tail is capped while a
     * measured span never is.
     */
    override fun getDailyForegroundTimeMs(packageName: String): Long {
        val todayStart = timeTracker.startOfToday()
        val now = System.currentTimeMillis()
        return screenTimeProvider
            .getPerAppSessionStats(todayStart, now)[packageName]
            ?.totalMs
            ?: 0L
    }

    fun getEventsSince(since: Long): Flow<List<UsageEvent>> =
        usageEventDao.getEventsSince(since)

    fun getChangedMindCountForDay(dayStart: Long, dayEnd: Long): Flow<Int> =
        usageEventDao.getChangedMindCount(dayStart, dayEnd)

    fun getBlockedCountForDay(dayStart: Long, dayEnd: Long): Flow<Int> =
        usageEventDao.getBlockedCount(dayStart, dayEnd)

    fun getAllTimeBlockedCount(): Flow<Int> =
        usageEventDao.getAllTimeBlockedCount()

    fun getAllTimeChangedMindCount(): Flow<Int> =
        usageEventDao.getAllTimeChangedMindCount()

    /** Delete events older than [retainDays] days. */
    suspend fun cleanup(retainDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - retainDays.toLong() * 24L * 60L * 60L * 1000L
        usageEventDao.deleteOlderThan(cutoff)
    }
}
