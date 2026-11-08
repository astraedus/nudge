package com.astraedus.nudge.domain.engine

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a days-and-times window next CHANGES: its next start, or the end of the window it is in.
 * Pure arithmetic over epoch ms and a [ZoneId].
 *
 * ## Why it walks absolute minutes
 *
 * [ScheduleEvaluator.isActiveAt] reads the LOCAL wall clock (day of week, minute of day) of an
 * instant. The honest answer to "when does that answer change" is therefore the first absolute minute
 * whose local reading changes it. This walk visits each absolute minute in turn and reads the wall
 * clock at it, using the zone's offset at that instant. It re-reads the offset only when it crosses
 * one of the zone's own transitions. That keeps it integer arithmetic and still exact across a DST
 * change:
 *
 *  - **Spring forward.** Local 02:00-02:59 never exists, so a window starting at 02:30 starts at the
 *    first instant whose wall clock is inside it, 03:00. That is the instant `isActiveAt` first says
 *    true.
 *  - **Fall back.** Local 01:00-01:59 happens twice, so a 01:00-02:00 window stays on through both
 *    copies. That is exactly what `isActiveAt` reads at each of those instants.
 *
 * The walk `windowEndAt` used before this counted WALL minutes and added them as ABSOLUTE ones
 * (`Calendar.add(MINUTE, step)`). Across a DST change its "Until 6:00 AM" was an hour off.
 *
 * Bounded at eight days of minutes, a week plus a day of slack for a DST change in the week. A window
 * that never changes in that span (no schedule, every day all day, or never on) answers null.
 */
internal object ScheduleWalk {

    private const val MS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_DAY = 24 * 60
    private const val MAX_STEPS = 8 * MINUTES_PER_DAY

    /**
     * The first minute boundary strictly after [nowMs] at which the window turns ON, having been off
     * the minute before. For a window that is on at [nowMs], that is the start of the NEXT window,
     * after this one ends. Null with no schedule (always on, so it never starts) or when nothing
     * starts within eight days.
     */
    fun nextStart(days: List<Int>?, startMinute: Int?, endMinute: Int?, nowMs: Long, zone: ZoneId): Long? =
        if (hasNoSchedule(days, startMinute, endMinute)) {
            null
        } else {
            firstChange(days, startMinute, endMinute, nowMs, zone, wantOn = true)
        }

    /**
     * The first minute boundary strictly after [nowMs] at which the window is OFF, given that it is on
     * at [nowMs]. Null with no schedule, when it is off at [nowMs], or when it does not end within
     * eight days.
     */
    fun windowEnd(days: List<Int>?, startMinute: Int?, endMinute: Int?, nowMs: Long, zone: ZoneId): Long? {
        if (hasNoSchedule(days, startMinute, endMinute)) return null
        val clock = LocalClock(zone, minuteFloor(nowMs))
        if (!clock.isOn(days, startMinute, endMinute)) return null
        return firstChange(days, startMinute, endMinute, nowMs, zone, wantOn = false)
    }

    /**
     * The first minute boundary after [nowMs] whose state is [wantOn] while the minute before it was
     * not. The state at [nowMs] itself is read at its own minute, so a window that starts at exactly
     * the minute [nowMs] falls in is already on and is not reported again.
     */
    private fun firstChange(
        days: List<Int>?,
        startMinute: Int?,
        endMinute: Int?,
        nowMs: Long,
        zone: ZoneId,
        wantOn: Boolean
    ): Long? {
        val clock = LocalClock(zone, minuteFloor(nowMs))
        var previous = clock.isOn(days, startMinute, endMinute)
        repeat(MAX_STEPS) {
            clock.advanceOneMinute()
            val on = clock.isOn(days, startMinute, endMinute)
            if (on == wantOn && previous != wantOn) return clock.epochMs
            previous = on
        }
        return null
    }

    /**
     * The next local midnight strictly after [nowMs]: when a day's usage starts again from zero
     * (`TimeTracker.startOfToday`), so when a spent daily limit stops blocking. `atStartOfDay` is
     * DST-safe: in a zone whose clocks jump at midnight, the day starts at the first instant that
     * exists.
     */
    fun nextLocalMidnight(nowMs: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    private fun hasNoSchedule(days: List<Int>?, startMinute: Int?, endMinute: Int?): Boolean =
        days == null && startMinute == null && endMinute == null

    private fun minuteFloor(ms: Long): Long = Math.floorDiv(ms, MS_PER_MINUTE) * MS_PER_MINUTE

    /**
     * A wall clock that steps one absolute minute at a time and knows the local day and minute of
     * each step. The offset is re-read only when a step reaches the zone's next transition.
     */
    private class LocalClock(private val zone: ZoneId, start: Long) {
        var epochMs: Long = start
            private set
        private var offsetMs = 0L
        private var nextTransitionMs = Long.MAX_VALUE

        init {
            readOffset()
        }

        fun advanceOneMinute() {
            epochMs += MS_PER_MINUTE
            if (epochMs >= nextTransitionMs) readOffset()
        }

        fun isOn(days: List<Int>?, startMinute: Int?, endMinute: Int?): Boolean {
            val localMinute = Math.floorDiv(epochMs + offsetMs, MS_PER_MINUTE)
            val minuteOfDay = Math.floorMod(localMinute, MINUTES_PER_DAY.toLong()).toInt()
            val epochDay = Math.floorDiv(localMinute, MINUTES_PER_DAY.toLong())
            // 1970-01-01 was a Thursday, ISO day 4.
            val dayIso = Math.floorMod(epochDay + 3, 7L).toInt() + 1
            return ScheduleEvaluator.isScheduledAt(days, startMinute, endMinute, dayIso, minuteOfDay)
        }

        private fun readOffset() {
            val instant = Instant.ofEpochMilli(epochMs)
            val rules = zone.rules
            offsetMs = rules.getOffset(instant).totalSeconds * 1000L
            nextTransitionMs = rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
        }
    }
}
