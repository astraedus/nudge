package com.astraedus.nudge.domain.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The "first unlock + N minutes" schedule (spec 5.3, from @danieltsam's #109).
 *
 * A rule with `firstUnlockMinutes = N` is active from the first unlock of the user's logical day
 * until N minutes later. Everything here is wall-clock arithmetic on epoch milliseconds and a
 * [ZoneId]: the window is "8:12 to 8:42" in the user's own terms, so it has to survive the device
 * sleeping, and a clock jump can only ever end it early, never extend it.
 *
 * **The logical day** runs from `dayStartMinute` to `dayStartMinute` the next calendar day (default
 * 4:00 AM). With that default, a 2:00 AM Saturday unlock belongs to Friday, and a Friday rule is
 * still active at 2:00 AM Saturday. That is the whole reason the day start is a setting: a night
 * owl's "today" is not the calendar's.
 *
 * **The stamp** is the wall-clock ms of the first unlock at or after the current logical day's
 * start. It is recorded whether or not any rule uses it (a rule switched on mid-morning then has a
 * real anchor), and it is stale the moment the next logical day begins, which is what makes the
 * next unlock the day's anchor again. There are no timers: every caller asks "is now inside the
 * window", which is why the app needs no wakeup to end a morning lock.
 *
 * The three ways a stamp stops counting, all fail-open (the app is allowed), because a blocker
 * acting on a wrong clock must never lock someone out:
 *
 * - **Stale**: `stamp < logical day start`. Yesterday's (or this morning's previous-day) unlock.
 * - **Future**: `stamp > now`. The clock moved backwards; the stamp describes a moment that has not
 *   happened yet, so it is discarded and the next unlock replaces it.
 * - **Before arming**: `stamp < armedAt`. The rule was saved or switched on after the unlock, so it
 *   starts at the NEXT first unlock (owner decision 2, section 8) rather than locking the phone
 *   mid-setup. `armedAt = 0` means "always armed".
 *
 * Clock jump forward needs no rule of its own: `now < stamp + minutes` simply fails sooner.
 *
 * DST and time-zone changes: the window end is an absolute instant (`stamp + minutes`), so a
 * 30-minute window is 30 real minutes across a DST transition. The day start, the stale check and
 * the logical weekday are recomputed in whatever zone the device now reports, so travelling
 * mid-window can end the window early (the new zone's logical day already began after the stamp).
 * That direction is deliberate: it allows apps, it never blocks them.
 */
object FirstUnlockWindow {

    /** No stamp recorded: [firstUnlockAt] has never been set. */
    const val NO_STAMP: Long = 0L

    /** The default logical day start, 4:00 AM (spec 5.1). */
    const val DEFAULT_DAY_START_MINUTE: Int = 4 * 60

    /** The window length an editor offers first: 30 minutes (spec 3.5). */
    const val DEFAULT_FIRST_UNLOCK_MINUTES: Int = 30

    private const val MINUTES_PER_DAY = 24 * 60
    private const val MILLIS_PER_MINUTE = 60_000L

    /**
     * Epoch ms of the start of the logical day containing [nowMs].
     *
     * A `dayStartMinute` outside a day is clamped rather than rejected: the value comes from a
     * preference, and a corrupt one must not throw on the event path.
     *
     * On a spring-forward day whose gap swallows the day start (some zones shift at 4:00), the local
     * time does not exist and [LocalDateTime.atZone] resolves it forward to the first real instant
     * after the gap. That is the horizon the day actually has.
     */
    fun logicalDayStart(nowMs: Long, dayStartMinute: Int, zone: ZoneId): Long {
        val date = logicalDayDate(nowMs, dayStartMinute, zone)
        return startOf(date, dayStartMinute, zone)
    }

    /**
     * ISO weekday (Monday = 1 .. Sunday = 7) of the logical day containing [nowMs]. This is the day
     * a first-unlock rule's `scheduleDays` are checked against, so a 2:00 AM Saturday unlock on a
     * Friday rule still reads as Friday.
     */
    fun logicalDayIso(nowMs: Long, dayStartMinute: Int, zone: ZoneId): Int =
        logicalDayDate(nowMs, dayStartMinute, zone).dayOfWeek.value

    /**
     * True when [nowMs] is inside the window opened by [stampMs]. [days] is the rule's ISO weekday
     * list; null or empty means every day, the same reading [ScheduleEvaluator] uses.
     *
     * [armedAt] is the wall-clock ms the rule was last saved or switched on, 0 for none.
     */
    fun isActive(
        nowMs: Long,
        stampMs: Long,
        dayStartMinute: Int,
        minutes: Int,
        days: List<Int>?,
        armedAt: Long,
        zone: ZoneId,
    ): Boolean = endsAt(nowMs, stampMs, dayStartMinute, minutes, days, armedAt, zone) != null

    /**
     * When the window [isActive] or null when it is not, as epoch ms: the block screen's
     * "Until 8:42 AM" (spec 3.9).
     *
     * The answer is the earlier of the rule's own end (`stamp + minutes`) and the next logical day
     * start, because the day boundary makes the stamp stale: a 30-minute window opened at 3:50 with
     * a 4:00 day start really does end at 4:00, and "Until" has to say so.
     */
    fun endsAt(
        nowMs: Long,
        stampMs: Long,
        dayStartMinute: Int,
        minutes: Int,
        days: List<Int>?,
        armedAt: Long,
        zone: ZoneId,
    ): Long? {
        if (minutes <= 0) return null
        if (stampMs <= NO_STAMP) return null
        // The clock moved backwards: this stamp is for a moment that has not happened yet.
        if (stampMs > nowMs) return null
        if (armedAt > NO_STAMP && stampMs < armedAt) return null

        val dayDate = logicalDayDate(nowMs, dayStartMinute, zone)
        val dayStart = startOf(dayDate, dayStartMinute, zone)
        // Stale: yesterday's unlock (or the previous logical day's) opens nothing today.
        if (stampMs < dayStart) return null

        if (days != null && days.isNotEmpty() && dayDate.dayOfWeek.value !in days) return null

        val windowEnd = minOf(
            stampMs + minutes.toLong() * MILLIS_PER_MINUTE,
            startOf(dayDate.plusDays(1), dayStartMinute, zone),
        )
        return if (nowMs < windowEnd) windowEnd else null
    }

    /**
     * Feeding rule for the service: true when [stampMs] cannot serve as the current logical day's
     * anchor and the next unlock should replace it. A future stamp counts as replaceable here (it
     * is already discarded by [isActive]), so a clock moved backwards heals on the next unlock
     * instead of pinning the app in "no window" until the clock catches up.
     */
    fun shouldStamp(nowMs: Long, stampMs: Long, dayStartMinute: Int, zone: ZoneId): Boolean {
        if (stampMs <= NO_STAMP) return true
        if (stampMs > nowMs) return true
        return stampMs < logicalDayStart(nowMs, dayStartMinute, zone)
    }

    /**
     * The AppWindow fallback's gate: is the device actually past the keyguard?
     *
     * `KeyguardManager.isDeviceLocked` is NOT this question. It is false on swipe-only and no-PIN
     * phones while the lock screen is showing, so an alarm or an incoming-call screen over the
     * keyguard would read as a wake-up and stamp the day's anchor while the user is still in bed.
     * The two facts that answer it are whether the keyguard is showing and whether the screen is
     * interactive; [interactive] is nullable because `PowerManager` can be absent.
     *
     * Pure and separate from the service so the truth table is pinned by a JVM test.
     */
    fun isPastKeyguard(keyguardLocked: Boolean, interactive: Boolean?): Boolean =
        !keyguardLocked && interactive == true

    private fun logicalDayDate(nowMs: Long, dayStartMinute: Int, zone: ZoneId): LocalDate {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = startOf(local.toLocalDate(), dayStartMinute, zone)
        return if (nowMs < today) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    private fun startOf(date: LocalDate, dayStartMinute: Int, zone: ZoneId): Long {
        val minute = dayStartMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        return LocalDateTime.of(date, LocalTime.MIDNIGHT)
            .plusMinutes(minute.toLong())
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }
}
