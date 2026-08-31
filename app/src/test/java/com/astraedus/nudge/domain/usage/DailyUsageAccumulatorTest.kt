package com.astraedus.nudge.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The pairing/splitting contract behind every screen-time bar in the app.
 *
 * The bug this covers: the weekly series came from `queryUsageStats(INTERVAL_DAILY)` while the
 * day drill-down summed `queryEvents` spans, so a bar and the numbers under it were two
 * different computations of one calendar day — and on device they disagreed completely (tall
 * bar, "0s" drill-down). Everything day-scoped now comes from this accumulator, so the rules it
 * follows ARE the rules the user sees.
 */
class DailyUsageAccumulatorTest {

    private val zone = ZoneId.of("Australia/Brisbane") // no DST, so plain cases stay readable
    private val firstDay = LocalDate.of(2026, 8, 17)

    private val chrome = "com.android.chrome"
    private val youtube = "com.google.android.youtube"

    /** Local midnight [dayIndex] days after [firstDay]. */
    private fun day(dayIndex: Int, zoneId: ZoneId = zone, from: LocalDate = firstDay): Long =
        from.plusDays(dayIndex.toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli()

    /** Boundaries for a [days]-day window: one more entry than there are buckets. */
    private fun boundaries(days: Int, zoneId: ZoneId = zone, from: LocalDate = firstDay): List<Long> =
        (0..days).map { day(it, zoneId, from) }

    private fun hours(h: Long) = h * 60L * 60L * 1000L
    private fun minutes(m: Long) = m * 60L * 1000L

    /** A day's total across every app — what a bar draws. */
    private fun List<Map<String, Long>>.totalOn(dayIndex: Int) = this[dayIndex].values.sum()

    // --- spans within a day ---

    @Test
    fun `a span inside one day lands entirely in that day`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(chrome, day(2) + hours(9))
        accumulator.onPaused(chrome, day(2) + hours(10))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(hours(1), buckets[2][chrome])
        assertEquals(0L, buckets.totalOn(1))
        assertEquals(0L, buckets.totalOn(3))
    }

    @Test
    fun `several spans on one day accumulate`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(chrome, day(0) + hours(1))
        accumulator.onPaused(chrome, day(0) + hours(1) + minutes(20))
        accumulator.onResumed(chrome, day(0) + hours(5))
        accumulator.onPaused(chrome, day(0) + hours(5) + minutes(10))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(minutes(30), buckets[0][chrome])
    }

    @Test
    fun `packages are kept apart`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(chrome, day(3) + hours(9))
        accumulator.onPaused(chrome, day(3) + hours(10))
        accumulator.onResumed(youtube, day(3) + hours(10))
        accumulator.onPaused(youtube, day(3) + hours(12))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(hours(1), buckets[3][chrome])
        assertEquals(hours(2), buckets[3][youtube])
        assertEquals(hours(3), buckets.totalOn(3))
    }

    // --- one app at a time (the 17-hour bug) ---

    /**
     * This test used to assert the opposite: two apps RESUMED at 09:00, paused an hour apart, and
     * three hours of "screen time" out of two wall-clock hours. That reading is what a
     * `package -> startTime` map buys you, and on a real phone it produced **~17 hours of screen
     * time before lunchtime** — several apps holding open spans at once, each billed through to
     * now. Only one app is on screen at a time; the next app's RESUMED is where the last one ended.
     */
    @Test
    fun `two apps are never credited with the same hour`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(chrome, day(3) + hours(9))
        accumulator.onResumed(youtube, day(3) + hours(10)) // chrome's PAUSED never arrived
        accumulator.onPaused(youtube, day(3) + hours(11))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(hours(1), buckets[3][chrome])
        assertEquals(hours(1), buckets[3][youtube])
        assertEquals(
            "two hours of wall clock cannot become three hours of screen time",
            hours(2),
            buckets.totalOn(3)
        )
    }

    @Test
    fun `a day can never hold more time than the day is long`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        // Every app resumes and none of them ever pauses — the worst case the platform can hand us.
        listOf(chrome, youtube, "com.instagram.android", "com.whatsapp", "com.reddit.frontpage")
            .forEachIndexed { index, pkg ->
                accumulator.onResumed(pkg, day(2) + hours(index.toLong()))
            }

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        buckets.forEachIndexed { dayIndex, _ ->
            assertTrue(
                "day $dayIndex holds ${buckets.totalOn(dayIndex)}ms, more than a 24-hour day",
                buckets.totalOn(dayIndex) <= hours(24)
            )
        }
    }

    @Test
    fun `today can never hold more time than has passed since midnight`() {
        val now = day(6) + hours(11) // 11am, the hour the 17-hour reading was reported at
        val accumulator = DailyUsageAccumulator(boundaries(7))
        listOf(chrome, youtube, "com.instagram.android", "com.whatsapp")
            .forEachIndexed { index, pkg ->
                accumulator.onResumed(pkg, day(6) + hours(index.toLong()))
            }

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = now)

        assertTrue(
            "eleven hours into the day, today reads ${buckets.totalOn(6)}ms",
            buckets.totalOn(6) <= now - day(6)
        )
    }

    @Test
    fun `a stale RESUMED cannot fill the bars of the days after it`() {
        // One lost close event three days ago used to be worth three days of screen time, spread
        // across every bar in between. An open span is inferred, so it is capped.
        val now = day(6) + hours(12)
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(youtube, day(3) + hours(9))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = now)

        assertEquals(
            "an unterminated span is capped, not billed to the present",
            ForegroundSpanTracker.DEFAULT_OPEN_TAIL_LIMIT_MS,
            buckets.sumOf { it.values.sum() }
        )
        assertEquals(0L, buckets.totalOn(4))
        assertEquals(0L, buckets.totalOn(5))
        assertEquals(0L, buckets.totalOn(6))
    }

    // --- the screen going off ---

    @Test
    fun `the screen going off ends the session, even across a bar boundary`() {
        val now = day(6) + hours(9)
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(youtube, day(5) + hours(22))
        accumulator.onForegroundEnded(day(5) + hours(23)) // phone put down for the night

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = now)

        assertEquals(hours(1), buckets[5][youtube])
        assertEquals("a sleeping phone is not screen time", 0L, buckets.totalOn(6))
    }

    @Test
    fun `the STOPPED of the open activity ends its span`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(chrome, day(2) + hours(9), className = "BrowserActivity")
        accumulator.onStopped(chrome, day(2) + hours(10), className = "BrowserActivity")

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(hours(1), buckets[2][chrome])
    }

    // --- spans that cross a boundary ---

    @Test
    fun `a span crossing midnight is split at the boundary`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        // 23:30 on day 2 through 00:20 on day 3.
        accumulator.onResumed(youtube, day(2) + hours(23) + minutes(30))
        accumulator.onPaused(youtube, day(3) + minutes(20))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(minutes(30), buckets[2][youtube])
        assertEquals(minutes(20), buckets[3][youtube])
    }

    @Test
    fun `a span crossing two midnights fills the day in between`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        // 22:00 on day 1 straight through to 02:00 on day 3 — day 2 is covered end to end.
        accumulator.onResumed(youtube, day(1) + hours(22))
        accumulator.onPaused(youtube, day(3) + hours(2))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(hours(2), buckets[1][youtube])
        assertEquals(hours(24), buckets[2][youtube])
        assertEquals(hours(2), buckets[3][youtube])
    }

    @Test
    fun `a whole span is preserved when it is split`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        val start = day(0) + hours(20)
        val end = day(2) + hours(6)
        accumulator.onResumed(chrome, start)
        accumulator.onPaused(chrome, end)

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(end - start, buckets.sumOf { it.values.sum() })
    }

    // --- the present ---

    @Test
    fun `a span still open counts up to now when the window reaches the present`() {
        val now = day(6) + hours(10) + minutes(15)
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(youtube, day(6) + hours(10))

        // The window ends at tomorrow's midnight, i.e. it includes now.
        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = now)

        assertEquals(minutes(15), buckets[6][youtube])
    }

    @Test
    fun `an open span still in an EARLIER day counts through to now`() {
        val now = day(6) + hours(1)
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(youtube, day(5) + hours(23))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = now)

        assertEquals(hours(1), buckets[5][youtube])
        assertEquals(hours(1), buckets[6][youtube])
    }

    @Test
    fun `an open span is dropped for a window that already ended`() {
        // Scrolled back to a past week: the closing PAUSED fell outside the query, and inventing
        // an end for it would credit a past day with time we cannot actually see.
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(youtube, day(6) + hours(10))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(20))

        assertNull(buckets[6][youtube])
        assertEquals(0L, buckets.sumOf { it.values.sum() })
    }

    @Test
    fun `finish is idempotent — a second call cannot double-count an open span`() {
        val now = day(6) + hours(10) + minutes(15)
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(youtube, day(6) + hours(10))

        accumulator.finish(windowEndMs = day(7), nowMs = now)
        val second = accumulator.finish(windowEndMs = day(7), nowMs = now)

        assertEquals(minutes(15), second[6][youtube])
    }

    // --- events outside the window, and malformed sequences ---

    @Test
    fun `time before the window is clamped away`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        // Started two hours before the window opened; only the hour inside it counts.
        accumulator.onResumed(chrome, day(0) - hours(2))
        accumulator.onPaused(chrome, day(0) + hours(1))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(hours(1), buckets[0][chrome])
    }

    @Test
    fun `time after the window is clamped away`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(chrome, day(6) + hours(23))
        accumulator.onPaused(chrome, day(7) + hours(5))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(8))

        assertEquals(hours(1), buckets[6][chrome])
        assertEquals(hours(1), buckets.sumOf { it.values.sum() })
    }

    @Test
    fun `a span entirely outside the window contributes nothing`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(chrome, day(0) - hours(5))
        accumulator.onPaused(chrome, day(0) - hours(4))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(0L, buckets.sumOf { it.values.sum() })
    }

    @Test
    fun `a PAUSED with no RESUMED is ignored`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onPaused(chrome, day(2) + hours(9))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(0L, buckets.sumOf { it.values.sum() })
    }

    @Test
    fun `a second RESUMED replaces the first`() {
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(chrome, day(2) + hours(9))
        accumulator.onResumed(chrome, day(2) + hours(11))
        accumulator.onPaused(chrome, day(2) + hours(12))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(hours(1), buckets[2][chrome])
    }

    @Test
    fun `a backwards pair contributes nothing rather than a negative total`() {
        // A bare `paused - resumed` would subtract an hour from the day and could drive a bar
        // below zero. A corrupt sequence must be worth zero, never negative.
        val accumulator = DailyUsageAccumulator(boundaries(7))
        accumulator.onResumed(chrome, day(2) + hours(10))
        accumulator.onPaused(chrome, day(2) + hours(9))

        val buckets = accumulator.finish(windowEndMs = day(7), nowMs = day(7))

        assertEquals(0L, buckets.totalOn(2))
        assertTrue(buckets.all { day -> day.values.all { it >= 0L } })
    }

    // --- DST ---

    @Test
    fun `a 25-hour day gets all 25 hours`() {
        val newYork = ZoneId.of("America/New_York")
        val dstWeekStart = LocalDate.of(2026, 10, 29) // DST ends Sun 2026-11-01
        val bounds = boundaries(7, newYork, dstWeekStart)
        val longDayIndex = 3 // 2026-11-01

        assertEquals(
            "the clocks-back day really is 25 hours wide",
            hours(25),
            bounds[longDayIndex + 1] - bounds[longDayIndex]
        )

        val accumulator = DailyUsageAccumulator(bounds)
        accumulator.onResumed(chrome, bounds[longDayIndex])
        accumulator.onPaused(chrome, bounds[longDayIndex + 1])

        val buckets = accumulator.finish(windowEndMs = bounds.last(), nowMs = bounds.last())

        assertEquals(hours(25), buckets[longDayIndex][chrome])
        assertEquals(0L, buckets.totalOn(longDayIndex + 1))
    }

    @Test
    fun `a 23-hour day is bucketed at true local midnight, not at 24-hour offsets`() {
        val newYork = ZoneId.of("America/New_York")
        val dstWeekStart = LocalDate.of(2026, 3, 5) // DST starts Sun 2026-03-08
        val bounds = boundaries(7, newYork, dstWeekStart)
        val shortDayIndex = 3 // 2026-03-08

        assertEquals(hours(23), bounds[shortDayIndex + 1] - bounds[shortDayIndex])

        val accumulator = DailyUsageAccumulator(bounds)
        // The last hour of the short day. Raw `dayStart + 24h` arithmetic would place this in
        // the NEXT day and the bar would move to the wrong date.
        accumulator.onResumed(chrome, bounds[shortDayIndex + 1] - hours(1))
        accumulator.onPaused(chrome, bounds[shortDayIndex + 1])

        val buckets = accumulator.finish(windowEndMs = bounds.last(), nowMs = bounds.last())

        assertEquals(hours(1), buckets[shortDayIndex][chrome])
        assertEquals(0L, buckets.totalOn(shortDayIndex + 1))
    }

    // --- the agreement invariant ---

    /**
     * The property that makes it safe for the drill-down to read a column straight out of the
     * weekly value: bucketing PARTITIONS the spans, so one day's column is exactly what the same
     * events produce when only that day is asked about.
     *
     * (The drill-down no longer runs its own computation at all — that is pinned at source level
     * by `ScreenTimeSourceContractTest`. This pins the arithmetic underneath it.)
     */
    @Test
    fun `a day's column equals a single-day run over the same events`() {
        val events = listOf(
            Triple(chrome, day(1) + hours(9), true),
            Triple(chrome, day(1) + hours(10), false),
            Triple(youtube, day(1) + hours(23), true),
            Triple(youtube, day(2) + hours(1), false), // crosses into day 2
            Triple(chrome, day(2) + hours(8), true),
            Triple(chrome, day(2) + hours(8) + minutes(45), false),
            Triple(youtube, day(4) + hours(20), true),
            Triple(youtube, day(4) + hours(21), false)
        )

        fun run(bounds: List<Long>): List<Map<String, Long>> {
            val accumulator = DailyUsageAccumulator(bounds)
            events.forEach { (pkg, timestamp, resumed) ->
                if (resumed) accumulator.onResumed(pkg, timestamp) else accumulator.onPaused(pkg, timestamp)
            }
            return accumulator.finish(windowEndMs = bounds.last(), nowMs = bounds.last())
        }

        val week = run(boundaries(7))

        for (dayIndex in 0 until 7) {
            val singleDay = run(listOf(day(dayIndex), day(dayIndex + 1)))
            assertEquals(
                "day $dayIndex: the bar and the drill-down must be the same numbers",
                singleDay[0],
                week[dayIndex]
            )
        }

        // And nothing is invented or lost across the week.
        assertEquals(hours(1) + hours(2) + minutes(45) + hours(1), week.sumOf { it.values.sum() })
    }

    // --- construction ---

    @Test(expected = IllegalArgumentException::class)
    fun `a boundary list with no bucket is rejected`() {
        DailyUsageAccumulator(listOf(day(0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out-of-order boundaries are rejected`() {
        DailyUsageAccumulator(listOf(day(2), day(1), day(3)))
    }
}
