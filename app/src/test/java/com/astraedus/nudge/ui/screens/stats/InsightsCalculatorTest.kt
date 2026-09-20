package com.astraedus.nudge.ui.screens.stats

import com.astraedus.nudge.data.db.entity.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Contract tests for the aggregation behind the Willpower and Interventions screens.
 *
 * Every case pins a real timezone and a real "now" rather than relying on the machine's
 * clock, because the whole point of threading [ZoneId] + `nowMs` through the calculator is
 * that midnight, DST and week boundaries become testable instead of hopeful.
 */
class InsightsCalculatorTest {

    private val calculator = InsightsCalculator()

    /** No DST — the baseline zone for arithmetic that should not care about it. */
    private val brisbane: ZoneId = ZoneId.of("Australia/Brisbane")

    /** Has DST — used to prove the local-time bucketing survives a 23-hour day. */
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    // ------------------------------------------------------------------ empty

    @Test
    fun `willpower over no events is all zeros and never NaN`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val result = calculator.willpower(emptyList(), now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(0, result.attempts)
        assertEquals(0, result.walkAways)
        assertEquals(0, result.gaveIn)
        assertEquals(0f, result.walkAwayRate, 0f)
        assertFalse(result.walkAwayRate.isNaN())
        assertEquals(24, result.hours.size)
        assertTrue(result.hours.all { it.rate == 0f && !it.rate.isNaN() })
        assertNull(result.strongestHour)
        assertNull(result.weakestHour)
        assertTrue(result.apps.isEmpty())
        assertEquals(4, result.weeks.size)
        assertTrue(result.weeks.all { it.attempts == 0 && it.rate == 0f })
    }

    @Test
    fun `interventions over no events is all zeros with a full-shape heatmap`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val result = calculator.interventions(emptyList(), now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(0, result.todayTotal)
        assertEquals(0, result.weekTotal)
        assertEquals(0, result.monthTotal)
        assertEquals(0, result.rangeTotal)
        assertNull(result.peakHour)
        assertNull(result.peakWeekday)
        assertEquals(24, result.hourly.size)
        assertEquals(7, result.weekday.size)
        assertEquals(7, result.heatmap.size)
        assertTrue(result.heatmap.all { it.size == 24 })
        assertEquals(InsightsCalculator.SPARKLINE_DAYS, result.dailySeries.size)
        assertTrue(result.apps.isEmpty())
    }

    // ------------------------------------------------- the orphan walk-away

    @Test
    fun `a walk-away with no paired show event clamps at 100 percent and never goes negative`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        // Three walk-aways whose show events have aged out of the window entirely.
        val events = listOf(
            walkAway(at(brisbane, 2026, 8, 20, 9)),
            walkAway(at(brisbane, 2026, 8, 20, 10)),
            walkAway(at(brisbane, 2026, 8, 20, 11))
        )

        val result = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(3, result.attempts)
        assertEquals(3, result.walkAways)
        assertEquals(0, result.gaveIn)
        assertEquals(1f, result.walkAwayRate, 0f)
        assertTrue(result.walkAwayRate <= 1f)
        assertTrue(result.gaveIn >= 0)
        assertEquals(1, result.apps.size)
        assertEquals(3, result.apps.first().attempts)
        assertEquals(0, result.apps.first().gaveIn)
    }

    @Test
    fun `more walk-aways than shows in one hour still yields a sane hourly rate`() {
        val now = at(brisbane, 2026, 8, 20, 23)
        val events = listOf(
            shown(at(brisbane, 2026, 8, 20, 9)),
            walkAway(at(brisbane, 2026, 8, 20, 9, 5)),
            walkAway(at(brisbane, 2026, 8, 20, 9, 10)),
            walkAway(at(brisbane, 2026, 8, 20, 9, 15))
        )

        val hour = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)
            .hours[9]

        assertEquals(3, hour.walkAways)
        assertEquals(3, hour.attempts)
        assertEquals(1f, hour.rate, 0f)
    }

    @Test
    fun `an allow event is neither a block nor a walk-away`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val events = listOf(
            allow(at(brisbane, 2026, 8, 20, 9)),
            allow(at(brisbane, 2026, 8, 20, 10)),
            shown(at(brisbane, 2026, 8, 20, 11))
        )

        val willpower = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)
        val interventions = calculator.interventions(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(1, willpower.attempts)
        assertEquals(1, interventions.rangeTotal)
        assertEquals(1, interventions.todayTotal)
    }

    @Test
    fun `a walk-away is not counted a second time as an intervention`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        // One confrontation: the overlay was shown, then the user turned around.
        val events = listOf(
            shown(at(brisbane, 2026, 8, 20, 9)),
            walkAway(at(brisbane, 2026, 8, 20, 9, 1))
        )

        val interventions = calculator.interventions(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(1, interventions.rangeTotal)
        assertEquals(1, interventions.hourly[9])
        assertEquals(1, interventions.apps.single().total)

        val willpower = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)
        assertEquals(1, willpower.attempts)
        assertEquals(1, willpower.walkAways)
        assertEquals(0, willpower.gaveIn)
    }

    // ------------------------------------------------ hour / day boundaries

    @Test
    fun `all events in a single hour land in that local hour only`() {
        val now = at(brisbane, 2026, 8, 20, 23, 59)
        val events = (0 until 10).map { shown(at(brisbane, 2026, 8, 20, 14, it)) }

        val result = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(10, result.hours[14].attempts)
        assertTrue(result.hours.filterIndexed { i, _ -> i != 14 }.all { it.attempts == 0 })
        // Ten blocks and zero walk-aways is volume, not a resistance pattern: naming 14:00
        // "strongest" here is the exact contradiction device QA caught (an empty rate chart
        // captioned "Strongest at 4pm"). This assertion used to expect 14.
        assertNull(result.strongestHour)
        assertNull(result.weakestHour)
    }

    @Test
    fun `an event one millisecond before local midnight belongs to the previous day`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val justBeforeMidnight = at(brisbane, 2026, 8, 20, 0) - 1L

        val result = calculator.interventions(
            listOf(shown(justBeforeMidnight)),
            now,
            brisbane,
            InsightsRange.THIRTY_DAYS
        )

        assertEquals("yesterday's block must not count as today", 0, result.todayTotal)
        assertEquals(1, result.rangeTotal)
        assertEquals(23, result.hourly.indexOfFirst { it > 0 })
        // Sparkline: index 13 is today, so yesterday is index 12.
        assertEquals(1, result.dailySeries[InsightsCalculator.SPARKLINE_DAYS - 2].count)
        assertEquals(0, result.dailySeries.last().count)
    }

    @Test
    fun `an event exactly at local midnight belongs to the new day`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val midnight = at(brisbane, 2026, 8, 20, 0)

        val result = calculator.interventions(
            listOf(shown(midnight)),
            now,
            brisbane,
            InsightsRange.THIRTY_DAYS
        )

        assertEquals(1, result.todayTotal)
        assertEquals(0, result.hourly.indexOfFirst { it > 0 })
        assertEquals(1, result.dailySeries.last().count)
    }

    @Test
    fun `range start is local midnight, so the same instant falls in or out by timezone`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val startBrisbane = calculator.rangeStartMs(now, brisbane, InsightsRange.SEVEN_DAYS)

        assertEquals(at(brisbane, 2026, 8, 14, 0), startBrisbane)

        // One millisecond earlier is the 7th day back and must be excluded.
        val outside = listOf(shown(startBrisbane - 1L))
        val inside = listOf(shown(startBrisbane))
        assertEquals(
            0,
            calculator.interventions(outside, now, brisbane, InsightsRange.SEVEN_DAYS).rangeTotal
        )
        assertEquals(
            1,
            calculator.interventions(inside, now, brisbane, InsightsRange.SEVEN_DAYS).rangeTotal
        )
    }

    @Test
    fun `the same instant buckets to different local hours in different zones`() {
        // 2026-08-20 09:30 Brisbane == 2026-08-19 19:30 New York.
        val instant = at(brisbane, 2026, 8, 20, 9, 30)
        val nowBrisbane = at(brisbane, 2026, 8, 20, 23)
        val nowNewYork = at(newYork, 2026, 8, 20, 23)

        val brisbaneHour = calculator.interventions(
            listOf(shown(instant)), nowBrisbane, brisbane, InsightsRange.THIRTY_DAYS
        ).hourly.indexOfFirst { it > 0 }
        val newYorkHour = calculator.interventions(
            listOf(shown(instant)), nowNewYork, newYork, InsightsRange.THIRTY_DAYS
        ).hourly.indexOfFirst { it > 0 }

        assertEquals(9, brisbaneHour)
        assertEquals(19, newYorkHour)
    }

    @Test
    fun `a DST spring-forward day still buckets by local hour`() {
        // US DST 2026 begins Sunday 2026-03-08: 02:00 local jumps to 03:00 (a 23-hour day).
        val zone = newYork
        val now = at(zone, 2026, 3, 8, 23)
        val beforeJump = at(zone, 2026, 3, 8, 1, 30)
        val afterJump = at(zone, 2026, 3, 8, 4, 30)

        val result = calculator.interventions(
            listOf(shown(beforeJump), shown(afterJump)),
            now,
            zone,
            InsightsRange.SEVEN_DAYS
        )

        assertEquals(1, result.hourly[1])
        assertEquals(1, result.hourly[4])
        assertEquals(2, result.todayTotal)
        // The shortened day is still exactly one day for the sparkline.
        assertEquals(2, result.dailySeries.last().count)
    }

    // ------------------------------------------------------ week bucketing

    @Test
    fun `a 30-day range produces four week buckets, oldest first`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val result = calculator.willpower(emptyList(), now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(4, result.weeks.size)
        assertEquals(listOf("3w ago", "2w ago", "1w ago", "This wk"), result.weeks.map { it.label })
        assertTrue("buckets must ascend in time", result.weeks.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
        assertEquals(
            "the oldest bucket starts where the range starts",
            calculator.rangeStartMs(now, brisbane, InsightsRange.THIRTY_DAYS),
            result.weeks.first().startMs
        )
    }

    @Test
    fun `a 7-day range produces a single week bucket`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val result = calculator.willpower(
            listOf(shown(at(brisbane, 2026, 8, 18, 10))),
            now,
            brisbane,
            InsightsRange.SEVEN_DAYS
        )

        assertEquals(1, result.weeks.size)
        assertEquals("This wk", result.weeks.single().label)
        assertEquals(1, result.weeks.single().attempts)
    }

    @Test
    fun `events land in the week bucket matching how many days ago they happened`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val events = listOf(
            shown(at(brisbane, 2026, 8, 20, 10)),   // 0 days ago  -> this week
            shown(at(brisbane, 2026, 8, 12, 10)),   // 8 days ago  -> 1w ago
            shown(at(brisbane, 2026, 8, 4, 10)),    // 16 days ago -> 2w ago
            shown(at(brisbane, 2026, 7, 28, 10))    // 23 days ago -> 3w ago
        )

        val weeks = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS).weeks

        assertEquals(listOf(1, 1, 1, 1), weeks.map { it.attempts })
    }

    @Test
    fun `the partial trailing days of a 30-day range fold into the oldest bucket, never dropped`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        // 30 days back from 2026-08-20 is 2026-07-22 (day 29), which is week 4 by division.
        val dayTwentyNine = at(brisbane, 2026, 7, 22, 10)
        val events = listOf(shown(dayTwentyNine))

        val result = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals("the event is inside the range", 1, result.attempts)
        assertEquals(
            "and it must appear in the weekly trend, not vanish",
            1,
            result.weeks.sumOf { it.attempts }
        )
        assertEquals(1, result.weeks.first().attempts)
    }

    // ------------------------------------------------------- strongest hour

    @Test
    fun `hours with a tiny sample cannot be named strongest or weakest`() {
        val now = at(brisbane, 2026, 8, 20, 23)
        // 09:00 is a single perfect walk-away — one data point is not a pattern.
        val events = listOf(walkAway(at(brisbane, 2026, 8, 20, 9)))

        val result = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(1, result.hours[9].attempts)
        assertNull(result.strongestHour)
        assertNull(result.weakestHour)
    }

    @Test
    fun `strongest and weakest hour are picked from well-sampled hours only`() {
        val now = at(brisbane, 2026, 8, 20, 23, 59)
        val events = buildList {
            // 09:00 -> 3 of 4 walked away (75%)
            repeat(4) { add(shown(at(brisbane, 2026, 8, 20, 9, it))) }
            repeat(3) { add(walkAway(at(brisbane, 2026, 8, 20, 9, 10 + it))) }
            // 23:00 -> 0 of 4 walked away (0%)
            repeat(4) { add(shown(at(brisbane, 2026, 8, 20, 23, it))) }
            // 15:00 -> a single event, must be ignored despite being 100%
            add(walkAway(at(brisbane, 2026, 8, 20, 15)))
        }

        val result = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(9, result.strongestHour)
        assertEquals(23, result.weakestHour)
    }

    @Test
    fun `zero walk-aways yields no strongest or weakest hour, however well-sampled`() {
        // v1.13.0 device QA: 11 blocks, 0 walk-aways rendered the clock's "no data" empty
        // state WITH a "Strongest at 4pm" caption underneath it. An all-zero rate curve has
        // no resistance pattern to name, no matter how many attempts back it.
        val now = at(brisbane, 2026, 8, 20, 23, 59)
        val events = buildList {
            repeat(5) { add(shown(at(brisbane, 2026, 8, 20, 9, it))) }
            repeat(6) { add(shown(at(brisbane, 2026, 8, 20, 16, it))) }
        }

        val result = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(5, result.hours[9].attempts)
        assertEquals(6, result.hours[16].attempts)
        assertNull(result.strongestHour)
        assertNull(result.weakestHour)
    }

    @Test
    fun `weakest hour is suppressed when it is not strictly worse than the strongest`() {
        val now = at(brisbane, 2026, 8, 20, 23)
        val events = buildList {
            repeat(2) { add(shown(at(brisbane, 2026, 8, 20, 9, it))) }
            repeat(2) { add(walkAway(at(brisbane, 2026, 8, 20, 9, 10 + it))) }
            repeat(2) { add(shown(at(brisbane, 2026, 8, 20, 14, it))) }
            repeat(2) { add(walkAway(at(brisbane, 2026, 8, 20, 14, 10 + it))) }
        }

        val result = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertNotNull(result.strongestHour)
        assertNull("both hours sit at 100%, so there is no weakest", result.weakestHour)
    }

    // ------------------------------------------------------- per-app rollup

    @Test
    fun `apps are ranked by confrontation volume and stay internally consistent`() {
        val now = at(brisbane, 2026, 8, 20, 23)
        val events = buildList {
            repeat(5) { add(shown(at(brisbane, 2026, 8, 20, 10, it), pkg = "app.busy")) }
            repeat(2) { add(walkAway(at(brisbane, 2026, 8, 20, 11, it), pkg = "app.busy")) }
            repeat(2) { add(shown(at(brisbane, 2026, 8, 20, 12, it), pkg = "app.quiet")) }
        }

        val result = calculator.willpower(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(listOf("app.busy", "app.quiet"), result.apps.map { it.packageName })
        val busy = result.apps.first()
        assertEquals(5, busy.attempts)
        assertEquals(2, busy.walkAways)
        assertEquals(3, busy.gaveIn)
        assertEquals(0.4f, busy.rate, 0.0001f)
        // The hero number is the sum of the rows the user can see.
        assertEquals(result.apps.sumOf { it.attempts }, result.attempts)
        assertEquals(result.apps.sumOf { it.walkAways }, result.walkAways)
    }

    // -------------------------------------------------- interventions detail

    @Test
    fun `hero totals are absolute and unaffected by the selected range`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val events = listOf(
            shown(at(brisbane, 2026, 8, 20, 9)),    // today
            shown(at(brisbane, 2026, 8, 17, 9)),    // 3 days ago
            shown(at(brisbane, 2026, 8, 1, 9))      // 19 days ago
        )

        val week = calculator.interventions(events, now, brisbane, InsightsRange.SEVEN_DAYS)
        val month = calculator.interventions(events, now, brisbane, InsightsRange.THIRTY_DAYS)

        assertEquals(1, week.todayTotal)
        assertEquals(2, week.weekTotal)
        assertEquals(3, week.monthTotal)
        assertEquals(week.todayTotal, month.todayTotal)
        assertEquals(week.weekTotal, month.weekTotal)
        assertEquals(week.monthTotal, month.monthTotal)
        // Only the range-scoped total moves.
        assertEquals(2, week.rangeTotal)
        assertEquals(3, month.rangeTotal)
    }

    @Test
    fun `weekday buckets start on Monday and the peak is the busiest day`() {
        val now = at(brisbane, 2026, 8, 20, 23) // a Thursday
        val events = listOf(
            shown(at(brisbane, 2026, 8, 17, 9)),    // Monday
            shown(at(brisbane, 2026, 8, 18, 9)),    // Tuesday
            shown(at(brisbane, 2026, 8, 18, 10)),   // Tuesday
            shown(at(brisbane, 2026, 8, 16, 9))     // Sunday
        )

        val result = calculator.interventions(events, now, brisbane, InsightsRange.SEVEN_DAYS)

        assertEquals(1, result.weekday[0])  // Mon
        assertEquals(2, result.weekday[1])  // Tue
        assertEquals(1, result.weekday[6])  // Sun
        assertEquals(1, result.peakWeekday)
        assertEquals("Mon", calculator.weekdayLabel(0))
        assertEquals("Sun", calculator.weekdayLabel(6))
    }

    @Test
    fun `the heatmap places a block at its weekday and hour`() {
        val now = at(brisbane, 2026, 8, 20, 23)
        val tuesday9pm = at(brisbane, 2026, 8, 18, 21)

        val heatmap = calculator.interventions(
            listOf(shown(tuesday9pm)), now, brisbane, InsightsRange.SEVEN_DAYS
        ).heatmap

        assertEquals(1, heatmap[1][21])
        assertEquals(1, heatmap.sumOf { row -> row.sum() })
    }

    @Test
    fun `block modes are bucketed per app and unknown modes collapse to OTHER`() {
        val now = at(brisbane, 2026, 8, 20, 23)
        val events = listOf(
            shown(at(brisbane, 2026, 8, 20, 9), mode = "DELAY"),
            shown(at(brisbane, 2026, 8, 20, 10), mode = "DELAY"),
            shown(at(brisbane, 2026, 8, 20, 11), mode = "BREATHING"),
            shown(at(brisbane, 2026, 8, 20, 12), mode = null),
            shown(at(brisbane, 2026, 8, 20, 13), mode = "SOMETHING_NEW")
        )

        val app = calculator.interventions(events, now, brisbane, InsightsRange.SEVEN_DAYS)
            .apps
            .single()

        assertEquals(5, app.total)
        assertEquals(2, app.byMode["DELAY"])
        assertEquals(1, app.byMode["BREATHING"])
        assertEquals(2, app.byMode[InsightsCalculator.OTHER_MODE])
        assertEquals("byMode must always account for every block", app.total, app.byMode.values.sum())
    }

    @Test
    fun `the sparkline always spans fourteen days ending today`() {
        val now = at(brisbane, 2026, 8, 20, 12)
        val result = calculator.interventions(
            listOf(shown(at(brisbane, 2026, 8, 20, 9))),
            now,
            brisbane,
            InsightsRange.SEVEN_DAYS
        )

        assertEquals(14, result.dailySeries.size)
        assertEquals(at(brisbane, 2026, 8, 7, 0), result.dailySeries.first().startMs)
        assertEquals(at(brisbane, 2026, 8, 20, 0), result.dailySeries.last().startMs)
        assertTrue(result.dailySeries.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
    }

    // All-time counts moved OUT of this class: the correction is no longer arithmetic over two
    // DAO reads but the query's own predicate (`UsageEventDao.getAllTimeShownCount`), so it is
    // pinned by BlockedCountSemanticsTest and BlockedCountSemanticsContractTest instead.

    // ------------------------------------------------------ time reclaimed

    @Test
    fun `time reclaimed multiplies walk-aways by measured average session length`() {
        val apps = listOf(
            AppResistance("app.a", attempts = 10, walkAways = 4, gaveIn = 6, rate = 0.4f),
            AppResistance("app.b", attempts = 5, walkAways = 2, gaveIn = 3, rate = 0.4f)
        )
        val averages = mapOf("app.a" to 10L * 60_000L, "app.b" to 3L * 60_000L)

        val result = calculator.estimateTimeReclaimed(apps, averages)

        assertEquals(4 * 10L * 60_000L + 2 * 3L * 60_000L, result.totalMs)
        assertEquals(2, result.appsMeasured)
        assertEquals(0, result.appsEstimatedFromDefault)
    }

    @Test
    fun `an app with no measurement falls back to the labelled default`() {
        val apps = listOf(AppResistance("app.unknown", attempts = 3, walkAways = 3, gaveIn = 0, rate = 1f))

        val result = calculator.estimateTimeReclaimed(apps, emptyMap())

        assertEquals(3 * InsightsCalculator.DEFAULT_SESSION_MS, result.totalMs)
        assertEquals(0, result.appsMeasured)
        assertEquals(1, result.appsEstimatedFromDefault)
    }

    @Test
    fun `absurd measured session lengths are clamped in both directions`() {
        val apps = listOf(
            AppResistance("app.glance", attempts = 1, walkAways = 1, gaveIn = 0, rate = 1f),
            AppResistance("app.marathon", attempts = 1, walkAways = 1, gaveIn = 0, rate = 1f)
        )
        val averages = mapOf(
            "app.glance" to 2_000L,               // 2 seconds
            "app.marathon" to 6L * 3_600_000L     // 6 hours
        )

        val result = calculator.estimateTimeReclaimed(apps, averages)

        assertEquals(
            InsightsCalculator.MIN_SESSION_MS + InsightsCalculator.MAX_SESSION_MS,
            result.totalMs
        )
    }

    @Test
    fun `apps the user never walked away from contribute nothing`() {
        val apps = listOf(AppResistance("app.a", attempts = 9, walkAways = 0, gaveIn = 9, rate = 0f))

        val result = calculator.estimateTimeReclaimed(apps, mapOf("app.a" to 5L * 60_000L))

        assertEquals(0L, result.totalMs)
        assertEquals(0, result.appsMeasured)
        assertEquals(0, result.appsEstimatedFromDefault)
    }

    // --------------------------------------------------------- label helpers

    @Test
    fun `an uninstalled app falls back to a readable package tail`() {
        // resolveAppName returns the package name itself when PackageManager has no entry.
        assertEquals(
            "Musically",
            calculator.appDisplayLabel("com.zhiliaoapp.musically", "com.zhiliaoapp.musically")
        )
        assertEquals("Youtube", calculator.appDisplayLabel("com.google.android.youtube", null))
        assertEquals("Some App", calculator.appDisplayLabel("com.vendor.some_app", null))
        assertEquals("Single", calculator.appDisplayLabel("single", null))
    }

    @Test
    fun `a resolved app name always wins over the fallback`() {
        assertEquals("YouTube", calculator.appDisplayLabel("com.google.android.youtube", "YouTube"))
        assertEquals(
            "a blank resolution is not a name",
            "Youtube",
            calculator.appDisplayLabel("com.google.android.youtube", "  ")
        )
    }

    @Test
    fun `the web pseudo-package reads as Websites, never as a package id`() {
        assertEquals("Websites", calculator.appDisplayLabel(InsightsCalculator.WEB_PSEUDO_PACKAGE, null))
        assertEquals("Websites", calculator.appDisplayLabel("web", "web"))
    }

    @Test
    fun `hour labels are 12-hour and out-of-range hours degrade instead of crashing`() {
        assertEquals("12am", calculator.hourLabel(0))
        assertEquals("9am", calculator.hourLabel(9))
        assertEquals("12pm", calculator.hourLabel(12))
        assertEquals("11pm", calculator.hourLabel(23))
        assertEquals("24", calculator.hourLabel(24))
        assertEquals("-1", calculator.hourLabel(-1))
    }

    @Test
    fun `percent formatting rounds and clamps`() {
        assertEquals("0%", calculator.formatPercent(0f))
        assertEquals("38%", calculator.formatPercent(0.375f))
        assertEquals("100%", calculator.formatPercent(1f))
        assertEquals("100%", calculator.formatPercent(4f))
        assertEquals("0%", calculator.formatPercent(-1f))
        assertEquals("0%", calculator.formatPercent(Float.NaN))
    }

    @Test
    fun `mode labels cover every stored mode plus the catch-all`() {
        assertEquals("Hard block", calculator.modeLabel("HARD_BLOCK"))
        assertEquals("Delay", calculator.modeLabel("DELAY"))
        assertEquals("Breathing", calculator.modeLabel("BREATHING"))
        assertEquals("Other", calculator.modeLabel(InsightsCalculator.OTHER_MODE))
        assertEquals("Other", calculator.modeLabel("WHATEVER"))
    }

    // ------------------------------------------------------------ invariants

    /**
     * The class-level guard: whatever the data looks like — orphan walk-aways, allow rows,
     * events on the range edges, several apps at once — no rate may be NaN or exceed 100%,
     * no "gave in" count may go negative, and every bucket must balance.
     */
    @Test
    fun `no random event corpus can produce a NaN, a rate above 100 percent or a negative gave-in`() {
        val random = Random(20260820)
        val zones = listOf(brisbane, newYork, ZoneId.of("UTC"), ZoneId.of("Europe/Berlin"))
        val packages = listOf("app.a", "app.b", "web", "com.example.gone")
        val modes = listOf("DELAY", "BREATHING", "HARD_BLOCK", null, "MYSTERY")

        repeat(60) {
            val zone = zones[random.nextInt(zones.size)]
            val now = at(zone, 2026, 8, 20, 12) + random.nextLong(0, DAY_MS)
            val events = (0 until random.nextInt(0, 80)).map {
                val ts = now - random.nextLong(0, 40L * DAY_MS)
                val roll = random.nextInt(3)
                UsageEvent(
                    packageName = packages[random.nextInt(packages.size)],
                    timestamp = ts,
                    wasBlocked = roll != 2,
                    blockMode = modes[random.nextInt(modes.size)],
                    // Deliberately allow orphan walk-aways: a changed-mind row whose show
                    // event is not in the corpus at all.
                    userChangedMind = roll == 1
                )
            }
            val range = if (random.nextBoolean()) InsightsRange.SEVEN_DAYS else InsightsRange.THIRTY_DAYS

            val willpower = calculator.willpower(events, now, zone, range)
            assertSaneRate(willpower.walkAwayRate)
            assertTrue(willpower.gaveIn >= 0)
            assertEquals(willpower.attempts, willpower.walkAways + willpower.gaveIn)
            willpower.hours.forEach { hour ->
                assertSaneRate(hour.rate)
                assertTrue(hour.walkAways <= hour.attempts)
            }
            willpower.apps.forEach { app ->
                assertSaneRate(app.rate)
                assertTrue(app.gaveIn >= 0)
                assertEquals(app.attempts, app.walkAways + app.gaveIn)
            }
            willpower.weeks.forEach { week ->
                assertSaneRate(week.rate)
                assertTrue(week.walkAways <= week.attempts)
            }
            willpower.strongestHour?.let { assertTrue(it in 0..23) }
            willpower.weakestHour?.let { assertTrue(it in 0..23) }

            val interventions = calculator.interventions(events, now, zone, range)
            assertEquals(24, interventions.hourly.size)
            assertEquals(7, interventions.weekday.size)
            assertEquals(7, interventions.heatmap.size)
            assertTrue(interventions.heatmap.all { row -> row.size == 24 })
            assertTrue(interventions.hourly.all { it >= 0 })
            assertEquals(interventions.rangeTotal, interventions.hourly.sum())
            assertEquals(interventions.rangeTotal, interventions.weekday.sum())
            assertEquals(interventions.rangeTotal, interventions.heatmap.sumOf { row -> row.sum() })
            assertEquals(interventions.rangeTotal, interventions.apps.sumOf { it.total })
            interventions.apps.forEach { app ->
                assertEquals(app.total, app.byMode.values.sum())
            }
            assertTrue(interventions.todayTotal <= interventions.weekTotal)
            assertTrue(interventions.weekTotal <= interventions.monthTotal)
            assertEquals(InsightsCalculator.SPARKLINE_DAYS, interventions.dailySeries.size)
        }
    }

    private fun assertSaneRate(rate: Float) {
        assertFalse("rate must never be NaN", rate.isNaN())
        assertTrue("rate must never exceed 100%", rate <= 1f)
        assertTrue("rate must never be negative", rate >= 0f)
    }

    // ----------------------------------------------------------- test fixture

    private fun at(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun shown(timestamp: Long, pkg: String = "app.a", mode: String? = "DELAY") =
        UsageEvent(
            packageName = pkg,
            timestamp = timestamp,
            wasBlocked = true,
            blockMode = mode,
            userChangedMind = false
        )

    private fun walkAway(timestamp: Long, pkg: String = "app.a", mode: String? = "DELAY") =
        UsageEvent(
            packageName = pkg,
            timestamp = timestamp,
            wasBlocked = true,
            blockMode = mode,
            userChangedMind = true
        )

    private fun allow(timestamp: Long, pkg: String = "app.a") =
        UsageEvent(packageName = pkg, timestamp = timestamp)

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
