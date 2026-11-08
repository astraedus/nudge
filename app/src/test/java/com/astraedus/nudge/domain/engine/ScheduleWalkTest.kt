package com.astraedus.nudge.domain.engine

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L1: [ScheduleWalk], the time arithmetic behind the boundary timer (#169) and the block screen's
 * "Until" (spec 3.9). Every instant is built from an explicit zone, never the JVM default, so the DST
 * cases mean the same thing on every machine and in CI.
 *
 * Europe/London, 2026: clocks go forward on Sunday 29 March at 01:00 GMT (local 01:00-01:59 never
 * exists) and back on Sunday 25 October at 02:00 BST (local 01:00-01:59 happens twice).
 */
class ScheduleWalkTest {

    private val london = ZoneId.of("Europe/London")
    private val utc = ZoneOffset.UTC

    private fun ms(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    /** November 2026 in UTC: the 2nd is a Monday (ISO 1), the 6th a Friday, the 9th a Monday. */
    private fun nov(d: Int, h: Int, mi: Int, s: Int = 0) = ms(utc, 2026, 11, d, h, mi, s)

    private fun nextStart(days: List<Int>?, start: Int?, end: Int?, now: Long, zone: ZoneId = utc) =
        ScheduleWalk.nextStart(days, start, end, now, zone)

    private fun windowEnd(days: List<Int>?, start: Int?, end: Int?, now: Long, zone: ZoneId = utc) =
        ScheduleWalk.windowEnd(days, start, end, now, zone)

    // ---- next start ----

    @Test
    fun `a same-day window starts at its start time`() {
        assertEquals(nov(4, 9, 0), nextStart(null, 9 * 60, 17 * 60, nov(4, 8, 0)))
    }

    @Test
    fun `seconds before the start minute still answer that minute`() {
        assertEquals(nov(4, 9, 0), nextStart(null, 9 * 60, 17 * 60, nov(4, 8, 59, 30)))
    }

    @Test
    fun `at or inside the window the next start is the following window`() {
        // Exactly 09:00:00 is already ON; the start is strictly after now, so it is tomorrow's.
        assertEquals(nov(5, 9, 0), nextStart(null, 9 * 60, 17 * 60, nov(4, 9, 0)))
        assertEquals(nov(5, 9, 0), nextStart(null, 9 * 60, 17 * 60, nov(4, 12, 0)))
    }

    @Test
    fun `an overnight window starts tonight, or tomorrow night from inside it`() {
        assertEquals(nov(4, 22, 0), nextStart(null, 22 * 60, 6 * 60, nov(4, 12, 0)))
        assertEquals(nov(5, 22, 0), nextStart(null, 22 * 60, 6 * 60, nov(4, 23, 0)))
        // After midnight, still inside last night's window.
        assertEquals(nov(5, 22, 0), nextStart(null, 22 * 60, 6 * 60, nov(5, 2, 0)))
    }

    @Test
    fun `a weekday window asked on Friday evening starts on Monday`() {
        assertEquals(nov(9, 9, 0), nextStart(listOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60, nov(6, 18, 0)))
    }

    @Test
    fun `a weekday window asked on Saturday morning starts on Monday, not at Saturday's 09-00`() {
        assertEquals(nov(9, 9, 0), nextStart(listOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60, nov(7, 8, 0)))
    }

    @Test
    fun `a days-only rule starts at midnight of its next day`() {
        // Wednesday only, asked Monday 10:00: Wednesday 00:00.
        assertEquals(nov(4, 0, 0), nextStart(listOf(3), null, null, nov(2, 10, 0)))
        // Asked on the Wednesday itself: the next Wednesday.
        assertEquals(nov(11, 0, 0), nextStart(listOf(3), null, null, nov(4, 10, 0)))
    }

    @Test
    fun `back-to-back days are one window, so the next start is after the gap`() {
        // Mon-Fri all day, asked Tuesday: the run Mon-Fri ends Saturday, the next starts Monday 9 Nov.
        assertEquals(nov(9, 0, 0), nextStart(listOf(1, 2, 3, 4, 5), null, null, nov(3, 10, 0)))
    }

    @Test
    fun `no schedule never starts`() {
        assertNull(nextStart(null, null, null, nov(4, 10, 0)))
    }

    @Test
    fun `every day all day never starts, because it never stops`() {
        assertNull(nextStart(listOf(1, 2, 3, 4, 5, 6, 7), null, null, nov(4, 10, 0)))
    }

    // ---- window end ----

    @Test
    fun `a window ends at its end time, and an off window has no end`() {
        assertEquals(nov(4, 17, 0), windowEnd(null, 9 * 60, 17 * 60, nov(4, 10, 0)))
        assertNull(windowEnd(null, 9 * 60, 17 * 60, nov(4, 17, 0)))
        assertNull(windowEnd(null, null, null, nov(4, 17, 0)))
    }

    // ---- DST (decision 3: the boundary must land on the wall-clock instant) ----

    @Test
    fun `spring forward - an overnight window ends at 06-00 local, not an hour late`() {
        // Asked Saturday 28 March 23:00 GMT. The old walk added seven WALL hours as absolute ones and
        // answered 07:00 BST.
        val end = windowEnd(null, 22 * 60, 6 * 60, ms(london, 2026, 3, 28, 23, 0), london)
        assertEquals(ms(london, 2026, 3, 29, 6, 0), end)
    }

    @Test
    fun `spring forward - a start inside the missing hour lands on the first instant that exists`() {
        // 01:30 local never happens on 29 March; 02:00 BST (01:00 UTC) is the first wall clock inside
        // a 01:30-03:00 window, which is exactly when isActiveAt first says true.
        val start = nextStart(null, 90, 180, ms(london, 2026, 3, 29, 0, 0), london)
        assertEquals(ms(utc, 2026, 3, 29, 1, 0), start)
        assertEquals(ms(london, 2026, 3, 29, 2, 0), start)
    }

    @Test
    fun `fall back - an overnight window ends at 06-00 local`() {
        val end = windowEnd(null, 22 * 60, 6 * 60, ms(london, 2026, 10, 24, 23, 0), london)
        assertEquals(ms(utc, 2026, 10, 25, 6, 0), end)
    }

    @Test
    fun `fall back - a window in the repeated hour stays on through both copies`() {
        // 01:00-02:00 local starts at the FIRST 01:00 (00:00 UTC, BST) and ends at 02:00 GMT (02:00 UTC):
        // two absolute hours.
        val start = nextStart(null, 60, 120, ms(london, 2026, 10, 25, 0, 30), london)
        assertEquals(ms(utc, 2026, 10, 25, 0, 0), start)
        assertEquals(ms(utc, 2026, 10, 25, 2, 0), windowEnd(null, 60, 120, start!!, london))
    }

    @Test
    fun `fall back - a start after the change is still on the wall clock`() {
        // A 09:00 start on the Sunday the clocks went back is 09:00 GMT.
        assertEquals(ms(utc, 2026, 10, 25, 9, 0), nextStart(null, 9 * 60, 17 * 60, ms(london, 2026, 10, 25, 0, 30), london))
    }

    // ---- timezone change ----

    @Test
    fun `the same instant read in another zone has another next start`() {
        // 07:00 UTC on Wednesday 4 Nov is 17:00 in Brisbane (UTC+10, no DST): its 09:00-17:00 window
        // has just ended, so the next start is Thursday 09:00 Brisbane, 23:00 UTC on the 4th.
        val now = nov(4, 7, 0)
        assertEquals(nov(4, 9, 0), nextStart(null, 9 * 60, 17 * 60, now, utc))
        assertEquals(nov(4, 23, 0), nextStart(null, 9 * 60, 17 * 60, now, ZoneId.of("Australia/Brisbane")))
    }

    // ---- next local midnight (shared with the daily-limit block, #170) ----

    @Test
    fun `next local midnight is tomorrow's start of day in the zone`() {
        assertEquals(nov(5, 0, 0), ScheduleWalk.nextLocalMidnight(nov(4, 10, 0), utc))
        assertEquals(nov(5, 0, 0), ScheduleWalk.nextLocalMidnight(nov(4, 0, 0), utc))
        // Brisbane midnight on the 5th is 14:00 UTC on the 4th.
        assertEquals(nov(4, 14, 0), ScheduleWalk.nextLocalMidnight(nov(4, 7, 0), ZoneId.of("Australia/Brisbane")))
    }

    @Test
    fun `next local midnight across a DST change is the local midnight, not 24 hours later`() {
        // Saturday 24 October 12:00 BST -> Sunday 00:00 BST; Sunday 12:00 GMT -> Monday 00:00 GMT, which
        // is 25 absolute hours after Sunday 00:00 BST.
        assertEquals(ms(london, 2026, 10, 25, 0, 0), ScheduleWalk.nextLocalMidnight(ms(london, 2026, 10, 24, 12, 0), london))
        assertEquals(ms(london, 2026, 10, 26, 0, 0), ScheduleWalk.nextLocalMidnight(ms(london, 2026, 10, 25, 12, 0), london))
    }
}
