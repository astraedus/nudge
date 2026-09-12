package com.astraedus.nudge.ui.screens.stats

import com.astraedus.nudge.data.db.entity.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The ONE per-app "which apps pull hardest" aggregation, shared by the Interventions
 * leaderboard and the home dashboard's "Blocked most this week" card.
 *
 * It was lifted out of [InsightsCalculator.interventions] so the two surfaces cannot compute
 * the same question two ways — the defect class `docs/architecture/stats-and-charts.md`
 * documents twice. These cases pin the rules that make the two agree: only the overlay-show
 * row counts, both window bounds are inclusive, and the ordering is total desc then package
 * asc, so a tie renders the same way on both screens.
 *
 * Timestamps here are plain longs: unlike the rest of the calculator this function does no
 * calendar bucketing at all, so there is no timezone for it to get wrong.
 */
class TopBlockedAppsTest {

    private val calculator = InsightsCalculator()

    private val since = 1_000_000L
    private val now = 2_000_000L

    private fun shown(pkg: String, ts: Long, mode: String? = "HARD_BLOCK") =
        UsageEvent(packageName = pkg, timestamp = ts, wasBlocked = true, blockMode = mode)

    private fun walkAway(pkg: String, ts: Long, mode: String? = "HARD_BLOCK") =
        UsageEvent(
            packageName = pkg,
            timestamp = ts,
            wasBlocked = true,
            blockMode = mode,
            userChangedMind = true
        )

    private fun allowed(pkg: String, ts: Long) =
        UsageEvent(packageName = pkg, timestamp = ts, wasBlocked = false)

    private fun top(events: List<UsageEvent>, limit: Int = 10) =
        calculator.topBlockedApps(events, sinceMs = since, nowMs = now, limit = limit)

    @Test
    fun `no events yields an empty list`() {
        assertTrue(top(emptyList()).isEmpty())
    }

    @Test
    fun `apps are ordered by total descending`() {
        val events = buildList {
            repeat(2) { add(shown("com.b", since + 10)) }
            repeat(5) { add(shown("com.a", since + 10)) }
            repeat(3) { add(shown("com.c", since + 10)) }
        }

        assertEquals(listOf("com.a", "com.c", "com.b"), top(events).map { it.packageName })
        assertEquals(listOf(5, 3, 2), top(events).map { it.total })
    }

    /** A tie must render identically on the home card and the leaderboard, so it breaks on name. */
    @Test
    fun `ties break on package name ascending, deterministically`() {
        val events = listOf(
            shown("com.zeta", since + 1),
            shown("com.alpha", since + 2),
            shown("com.mid", since + 3)
        )

        assertEquals(listOf("com.alpha", "com.mid", "com.zeta"), top(events).map { it.packageName })
        // Insertion order is deliberately the reverse here, so this cannot pass by accident.
        assertEquals(
            top(events).map { it.packageName },
            top(events.reversed()).map { it.packageName }
        )
    }

    /**
     * One confrontation the user walked away from writes TWO rows. Counting `wasBlocked`
     * would score it 2 and put that app above an app the user actually opened twice.
     */
    @Test
    fun `a walk-away is not counted twice`() {
        val events = listOf(
            shown("com.insta", since + 10),
            walkAway("com.insta", since + 11)
        )

        val result = top(events)
        assertEquals(1, result.size)
        assertEquals(1, result.single().total)
    }

    @Test
    fun `an orphan walk-away with no show row counts nothing`() {
        assertTrue(top(listOf(walkAway("com.insta", since + 10))).isEmpty())
    }

    @Test
    fun `allowed launches are not interventions`() {
        val events = listOf(
            allowed("com.insta", since + 10),
            allowed("com.insta", since + 20),
            shown("com.tiktok", since + 30)
        )

        assertEquals(listOf("com.tiktok"), top(events).map { it.packageName })
    }

    /** Inclusive lower bound: the window's own first millisecond belongs to the window. */
    @Test
    fun `an event exactly on sinceMs counts and one millisecond earlier does not`() {
        assertEquals(1, top(listOf(shown("com.a", since))).single().total)
        assertTrue(top(listOf(shown("com.a", since - 1))).isEmpty())
    }

    /** Inclusive upper bound, and a clock that jumped backwards cannot inflate the card. */
    @Test
    fun `an event exactly on nowMs counts and one millisecond later does not`() {
        assertEquals(1, top(listOf(shown("com.a", now))).single().total)
        assertTrue(top(listOf(shown("com.a", now + 1))).isEmpty())
    }

    @Test
    fun `limit truncates after the sort, not before`() {
        val events = buildList {
            // Insertion order is weakest-first, so a limit applied during accumulation would
            // return the wrong apps entirely rather than merely the wrong order.
            repeat(1) { add(shown("com.weak", since + 1)) }
            repeat(9) { add(shown("com.strong", since + 2)) }
            repeat(5) { add(shown("com.mid", since + 3)) }
        }

        assertEquals(listOf("com.strong", "com.mid"), top(events, limit = 2).map { it.packageName })
    }

    @Test
    fun `a limit at or above the number of apps returns them all`() {
        val events = listOf(shown("com.a", since + 1), shown("com.b", since + 2))

        assertEquals(2, top(events, limit = 2).size)
        assertEquals(2, top(events, limit = 99).size)
    }

    @Test
    fun `a non-positive limit returns nothing rather than everything`() {
        val events = listOf(shown("com.a", since + 1))

        assertTrue(top(events, limit = 0).isEmpty())
        assertTrue(top(events, limit = -1).isEmpty())
    }

    @Test
    fun `block modes are tallied per app and unknown modes collapse to OTHER`() {
        val events = listOf(
            shown("com.a", since + 1, mode = "HARD_BLOCK"),
            shown("com.a", since + 2, mode = "BREATHING"),
            shown("com.a", since + 3, mode = "BREATHING"),
            shown("com.a", since + 4, mode = "TELEPORT"),
            shown("com.a", since + 5, mode = null)
        )

        val stat = top(events).single()
        assertEquals(5, stat.total)
        assertEquals(2, stat.byMode["BREATHING"])
        assertEquals(1, stat.byMode["HARD_BLOCK"])
        assertEquals(2, stat.byMode[InsightsCalculator.OTHER_MODE])
        assertEquals(stat.total, stat.byMode.values.sum())
    }

    /**
     * The delegation this extraction exists for: asking the shared function for the same
     * window the Interventions screen is drawing must return that screen's own leaderboard.
     * If these two ever diverge, the home card and the insight page are lying to each other.
     */
    @Test
    fun `the shared function reproduces the Interventions leaderboard exactly`() {
        val zone = ZoneId.of("Australia/Brisbane")
        val nowMs = LocalDateTime.of(2026, 9, 11, 20, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val dayMs = 86_400_000L
        val events = buildList {
            repeat(4) { add(shown("com.insta", nowMs - dayMs)) }
            repeat(7) { add(shown("com.tiktok", nowMs - 2 * dayMs)) }
            add(walkAway("com.tiktok", nowMs - 2 * dayMs + 1))
            repeat(7) { add(shown("com.reddit", nowMs - 3 * dayMs)) }
            // Outside the 7-day range: must reach neither surface.
            repeat(50) { add(shown("com.ancient", nowMs - 40 * dayMs)) }
        }

        val screen = calculator.interventions(events, nowMs, zone, InsightsRange.SEVEN_DAYS).apps
        val shared = calculator.topBlockedApps(
            events,
            sinceMs = calculator.rangeStartMs(nowMs, zone, InsightsRange.SEVEN_DAYS),
            nowMs = nowMs,
            limit = InsightsCalculator.NO_LIMIT
        )

        assertEquals(screen, shared)
        assertEquals(listOf("com.reddit", "com.tiktok", "com.insta"), shared.map { it.packageName })
    }
}
