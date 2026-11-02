package com.astraedus.nudge.domain.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1, pure: the section 7 matrix for the first-unlock window (spec 5.3). Every case names an
 * explicit [ZoneId] so the DST and travel cases describe a real place rather than the machine the
 * tests happen to run on.
 */
class FirstUnlockWindowTest {

    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    /** 2026-06-06 is a Saturday; 2026-06-08 is a Monday. */
    private fun at(zone: ZoneId, date: String, hour: Int, minute: Int): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun active(
        nowMs: Long,
        stampMs: Long,
        minutes: Int = 30,
        days: List<Int>? = null,
        armedAt: Long = 0L,
        dayStartMinute: Int = FirstUnlockWindow.DEFAULT_DAY_START_MINUTE,
        zone: ZoneId = newYork,
    ) = FirstUnlockWindow.isActive(nowMs, stampMs, dayStartMinute, minutes, days, armedAt, zone)

    private fun end(
        nowMs: Long,
        stampMs: Long,
        minutes: Int = 30,
        days: List<Int>? = null,
        armedAt: Long = 0L,
        dayStartMinute: Int = FirstUnlockWindow.DEFAULT_DAY_START_MINUTE,
        zone: ZoneId = newYork,
    ) = FirstUnlockWindow.endsAt(nowMs, stampMs, dayStartMinute, minutes, days, armedAt, zone)

    // ---- the window's edges -------------------------------------------------------------

    @Test
    fun `the window opens at the stamp and lasts its minutes`() {
        val stamp = at(newYork, "2026-06-08", 8, 12)

        assertTrue(active(stamp, stamp))
        assertEquals(stamp + 30 * 60_000L, end(stamp, stamp))
    }

    @Test
    fun `the end is exclusive and the minute before it is inside`() {
        val stamp = at(newYork, "2026-06-08", 8, 12)
        val windowEnd = stamp + 30 * 60_000L

        assertTrue(active(windowEnd - 1, stamp))
        assertEquals(windowEnd, end(windowEnd - 1, stamp))
        assertFalse(active(windowEnd, stamp))
        assertNull(end(windowEnd, stamp))
    }

    @Test
    fun `no stamp and a non-positive window are never active`() {
        val now = at(newYork, "2026-06-08", 8, 12)

        assertFalse(active(now, FirstUnlockWindow.NO_STAMP))
        assertFalse(active(now, now, minutes = 0))
        assertFalse(active(now, now, minutes = -5))
    }

    // ---- the logical day ----------------------------------------------------------------

    @Test
    fun `a two am unlock belongs to the previous logical day`() {
        val stamp = at(newYork, "2026-06-06", 1, 0) // Saturday 1 AM
        val now = at(newYork, "2026-06-06", 1, 10)

        assertEquals(5, FirstUnlockWindow.logicalDayIso(now, 240, newYork))
        assertTrue("Friday's rule is still on at 1 AM Saturday", active(now, stamp, days = listOf(5)))
        assertFalse("Saturday's rule has not started", active(now, stamp, days = listOf(6)))
    }

    @Test
    fun `the logical day start is the configured minute`() {
        val now = at(newYork, "2026-06-06", 5, 0)

        assertEquals(
            at(newYork, "2026-06-06", 4, 0),
            FirstUnlockWindow.logicalDayStart(now, 240, newYork),
        )
    }

    @Test
    fun `before the day start the logical day is yesterday`() {
        val now = at(newYork, "2026-06-06", 3, 59)

        assertEquals(
            at(newYork, "2026-06-05", 4, 0),
            FirstUnlockWindow.logicalDayStart(now, 240, newYork),
        )
        assertEquals(5, FirstUnlockWindow.logicalDayIso(now, 240, newYork))
    }

    @Test
    fun `a stamp before the day start is stale`() {
        val stamp = at(newYork, "2026-06-05", 23, 0)
        val now = at(newYork, "2026-06-06", 5, 0)

        assertFalse(active(now, stamp))
        assertNull(end(now, stamp))
    }

    @Test
    fun `a stamp exactly at the day start is today's anchor`() {
        val dayStart = at(newYork, "2026-06-06", 4, 0)

        assertTrue(active(dayStart + 60_000L, dayStart))
    }

    @Test
    fun `the day boundary ends an open window early`() {
        // 3:50 unlock, 30 minutes, 4:00 day start: the next day has no unlock yet, so the window
        // really ends at 4:00 and "Until" must say 4:00 rather than 4:20.
        val stamp = at(newYork, "2026-06-06", 3, 50)
        val now = at(newYork, "2026-06-06", 3, 55)

        assertEquals(at(newYork, "2026-06-06", 4, 0), end(now, stamp))
        assertFalse(active(at(newYork, "2026-06-06", 4, 0), stamp))
    }

    @Test
    fun `an empty day list means every day`() {
        val stamp = at(newYork, "2026-06-06", 8, 0)

        assertTrue(active(stamp + 60_000L, stamp, days = emptyList()))
    }

    @Test
    fun `days are checked against the logical weekday, not the calendar date`() {
        // Monday 1 AM is still Sunday's logical day with a 4 AM start.
        val stamp = at(newYork, "2026-06-08", 1, 0)
        val now = at(newYork, "2026-06-08", 1, 15)

        assertEquals(7, FirstUnlockWindow.logicalDayIso(now, 240, newYork))
        assertTrue(active(now, stamp, days = listOf(7)))
        assertFalse(active(now, stamp, days = listOf(1)))
    }

    // ---- clock behaviour ----------------------------------------------------------------

    @Test
    fun `a future stamp is discarded`() {
        val now = at(newYork, "2026-06-06", 8, 0)
        val stamp = now + 60_000L

        assertFalse(active(now, stamp))
        assertNull(end(now, stamp))
    }

    @Test
    fun `a clock jump forward ends the window early rather than extending it`() {
        val stamp = at(newYork, "2026-06-06", 8, 0)
        val jumped = at(newYork, "2026-06-06", 10, 0)

        assertFalse(active(jumped, stamp, minutes = 60))
    }

    // ---- arming (owner decision 2) ------------------------------------------------------

    @Test
    fun `a rule armed after the unlock starts at the next first unlock`() {
        val stamp = at(newYork, "2026-06-06", 8, 0)
        val now = at(newYork, "2026-06-06", 8, 10)

        assertFalse(active(now, stamp, armedAt = at(newYork, "2026-06-06", 8, 5)))
    }

    @Test
    fun `a rule armed before or exactly at the unlock counts`() {
        val stamp = at(newYork, "2026-06-06", 8, 0)
        val now = at(newYork, "2026-06-06", 8, 10)

        assertTrue(active(now, stamp, armedAt = at(newYork, "2026-06-06", 7, 0)))
        assertTrue(active(now, stamp, armedAt = stamp))
    }

    @Test
    fun `armedAt zero means always armed`() {
        val stamp = at(newYork, "2026-06-06", 8, 0)

        assertTrue(active(stamp + 60_000L, stamp, armedAt = 0L))
    }

    // ---- DST, with an explicit zone ------------------------------------------------------

    @Test
    fun `a thirty minute window across the spring-forward gap is thirty real minutes`() {
        // New York, 2026-03-08: 02:00 EST becomes 03:00 EDT. A stamp at 01:50 is 30 real minutes
        // from 03:20 EDT, not 40 wall-clock minutes.
        val stamp = at(newYork, "2026-03-08", 1, 50)
        val expectedEnd = Instant.ofEpochMilli(stamp).plusSeconds(30 * 60).toEpochMilli()

        assertEquals(expectedEnd, end(stamp, stamp, minutes = 30))
        assertTrue(active(expectedEnd - 1, stamp, minutes = 30))
        assertFalse(active(expectedEnd, stamp, minutes = 30))
    }

    @Test
    fun `a long window across the spring-forward gap keeps its absolute end`() {
        // A midnight day start, so the day boundary is not the thing that ends this window.
        val stamp = at(newYork, "2026-03-08", 1, 30)
        val expectedEnd = Instant.ofEpochMilli(stamp).plusSeconds(120 * 60).toEpochMilli()

        assertEquals(expectedEnd, end(stamp, stamp, minutes = 120, dayStartMinute = 0))
        // 01:30 EST + 2 h = 04:30 EDT, and the local clock skipped an hour on the way.
        assertEquals(at(newYork, "2026-03-08", 4, 30), expectedEnd)
    }

    @Test
    fun `a window opened before the day start is capped by the day boundary across DST`() {
        // The same unlock with the default 4 AM day start: 03-08's logical day began 03-07 04:00,
        // and the next one is 04:00 EDT, so a 2-hour window really ends there.
        val stamp = at(newYork, "2026-03-08", 1, 30)

        assertEquals(at(newYork, "2026-03-08", 4, 0), end(stamp, stamp, minutes = 120))
    }

    @Test
    fun `the fall-back hour is absolute, not two hours of wall clock`() {
        // New York, 2026-11-01: 02:00 EDT becomes 01:00 EST, so 01:30 happens twice.
        val stamp = at(newYork, "2026-11-01", 1, 30) // the EDT one
        val expectedEnd = Instant.ofEpochMilli(stamp).plusSeconds(60 * 60).toEpochMilli()

        assertEquals(expectedEnd, end(stamp, stamp, minutes = 60))
        assertEquals(60 * 60_000L, expectedEnd - stamp)
        assertTrue(active(expectedEnd - 1, stamp, minutes = 60))
        assertFalse(active(expectedEnd, stamp, minutes = 60))
    }

    @Test
    fun `a day start that falls in a spring-forward gap resolves to the first real instant`() {
        // Havana shifts at midnight: 2026-03-08 00:00 becomes 01:00, so a 00:00 day start is 01:00.
        val havana = ZoneId.of("America/Havana")
        val now = at(havana, "2026-03-08", 5, 0)

        assertEquals(
            at(havana, "2026-03-08", 1, 0),
            FirstUnlockWindow.logicalDayStart(now, 0, havana),
        )
    }

    // ---- travelling mid-window -----------------------------------------------------------

    @Test
    fun `a time-zone change keeps the window's absolute end`() {
        val stamp = at(newYork, "2026-06-06", 8, 0)
        val now = stamp + 10 * 60_000L

        val endInNewYork = end(now, stamp, minutes = 30, zone = newYork)
        val endInTokyo = end(now, stamp, minutes = 30, zone = tokyo)

        assertEquals(stamp + 30 * 60_000L, endInNewYork)
        assertEquals(endInNewYork, endInTokyo)
    }

    @Test
    fun `a time-zone change that puts the stamp in yesterday's day ends the window`() {
        // Stamp 18:00 UTC on 2026-06-01, now 19:30 UTC. In New York that is 14:00 to 15:30 on the
        // same logical day; in Tokyo it is 03:00 to 04:30 on 2026-06-02, and the 04:00 day start is
        // 19:00 UTC, after the stamp. The new zone's day began after the unlock, so the stamp is
        // stale there: fail open.
        val stamp = Instant.parse("2026-06-01T18:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-06-01T19:30:00Z").toEpochMilli()

        assertTrue(active(now, stamp, minutes = 120, zone = newYork))
        assertFalse(active(now, stamp, minutes = 120, zone = tokyo))
        assertNull(end(now, stamp, minutes = 120, zone = tokyo))
    }

    // ---- the service's feeding rule ------------------------------------------------------

    @Test
    fun `shouldStamp asks for an anchor when there is none, when it is stale, or when it is future`() {
        val now = at(newYork, "2026-06-06", 8, 0)

        assertTrue(FirstUnlockWindow.shouldStamp(now, FirstUnlockWindow.NO_STAMP, 240, newYork))
        assertTrue(
            FirstUnlockWindow.shouldStamp(
                now,
                at(newYork, "2026-06-05", 22, 0),
                240,
                newYork,
            ),
        )
        assertTrue(FirstUnlockWindow.shouldStamp(now, now + 60_000L, 240, newYork))
    }

    @Test
    fun `shouldStamp leaves a fresh anchor alone`() {
        val now = at(newYork, "2026-06-06", 8, 0)

        assertFalse(
            FirstUnlockWindow.shouldStamp(
                now,
                at(newYork, "2026-06-06", 7, 55),
                240,
                newYork,
            ),
        )
    }

    @Test
    fun `a corrupt day start is clamped, not thrown`() {
        val now = at(newYork, "2026-06-06", 12, 0)

        assertEquals(
            at(newYork, "2026-06-06", 0, 0),
            FirstUnlockWindow.logicalDayStart(now, -30, newYork),
        )
        assertEquals(
            at(newYork, "2026-06-05", 23, 59),
            FirstUnlockWindow.logicalDayStart(now, 99_999, newYork),
        )
    }
}
