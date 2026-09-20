package com.astraedus.nudge.ui.screens.stats

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.isShownConfrontation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Pure aggregation behind the two insight screens (Willpower and Interventions).
 *
 * No Android dependencies, and — deliberately — no ambient state: the caller passes
 * [nowMs] and the [ZoneId] into every entry point, so day/hour/week bucketing is
 * exercised over real timezones and real midnight boundaries in JVM tests instead of
 * whatever the machine happens to be set to.
 *
 * ## What the rows in `usage_events` actually mean
 *
 * Every block decision writes ONE event when the overlay is shown
 * (`wasBlocked=true, userChangedMind=false`). If the user then taps "I changed my mind",
 * a SECOND event is written for the same confrontation
 * (`wasBlocked=true, userChangedMind=true`). ALLOW decisions write `wasBlocked=false`.
 *
 * So `wasBlocked` alone double-counts a walk-away, and nothing in the app may use it as
 * a count. `UsageEvent.isShownConfrontation` is the one predicate that says so, and the
 * `UsageEventDao` count queries are its SQL mirror. Here:
 *
 * ```
 * shown     = wasBlocked && !userChangedMind      (the overlay went up)
 * walkAways = userChangedMind                     (the user turned around)
 * attempts  = max(shown, walkAways)               (confrontations faced)
 * gaveIn    = attempts - walkAways                (>= 0 by construction)
 * rate      = walkAways / attempts                (0 when attempts == 0)
 * ```
 *
 * `attempts` is a max rather than just `shown` because a walk-away can outlive its
 * paired show event (a retention sweep that cut the window mid-confrontation, or a show
 * event that never made it to disk). Taking the max means a rate can never exceed 100%,
 * `gaveIn` can never go negative, and `walkAways + gaveIn == attempts` holds in every
 * section of both screens. Totals are summed from per-app `attempts` so the hero number
 * always agrees with the leaderboard beneath it.
 */
class InsightsCalculator @Inject constructor() {

    // ---------------------------------------------------------------- ranges

    /**
     * Inclusive start of [range] as epoch millis: local midnight of the day
     * `range.days - 1` days before the local day containing [nowMs]. A 7-day range
     * therefore means "today plus the six days before it", not "the last 168 hours".
     */
    fun rangeStartMs(nowMs: Long, zone: ZoneId, range: InsightsRange): Long =
        startOfDayMs(localDate(nowMs, zone).minusDays((range.days - 1).toLong()), zone)

    // ------------------------------------------------------------- willpower

    fun willpower(
        events: List<UsageEvent>,
        nowMs: Long,
        zone: ZoneId,
        range: InsightsRange
    ): WillpowerInsights {
        val rangeStart = rangeStartMs(nowMs, zone, range)
        val today = localDate(nowMs, zone)
        val weekBuckets = range.days / DAYS_PER_WEEK

        val hourly = List(HOURS_PER_DAY) { Tally() }
        val perApp = LinkedHashMap<String, Tally>()
        val weekly = List(weekBuckets) { Tally() }

        for (event in events) {
            val kind = classify(event) ?: continue
            if (!inWindow(event.timestamp, rangeStart, nowMs)) continue
            val at = Instant.ofEpochMilli(event.timestamp).atZone(zone)

            hourly[at.hour].add(kind)
            perApp.getOrPut(event.packageName) { Tally() }.add(kind)

            // Bucket 0 is the OLDEST week so the chart reads left-to-right in time. A range
            // that is not a whole number of weeks (30 days = 4 weeks + 2) folds its extra
            // days into the oldest bucket rather than dropping them — the buckets are
            // compared as RATES, so a slightly wider oldest bucket stays meaningful while
            // silently discarding two days of history would not.
            val weeksAgo = (daysBetween(at.toLocalDate(), today) / DAYS_PER_WEEK)
                .coerceAtMost(weekBuckets - 1)
            val index = weekBuckets - 1 - weeksAgo
            if (index in 0 until weekBuckets) weekly[index].add(kind)
        }

        val hours = hourly.mapIndexed { hour, tally ->
            HourResistance(hour, tally.attempts, tally.walkAways, tally.rate)
        }
        val apps = perApp.entries
            .map { (pkg, tally) ->
                AppResistance(pkg, tally.attempts, tally.walkAways, tally.gaveIn, tally.rate)
            }
            .sortedWith(
                compareByDescending<AppResistance> { it.attempts }
                    .thenByDescending { it.walkAways }
                    .thenBy { it.packageName }
            )
        val weeks = weekly.mapIndexed { index, tally ->
            val weeksAgo = weekBuckets - 1 - index
            // The oldest bucket starts where the range starts (it absorbed the remainder days).
            val startMs = if (index == 0) {
                rangeStart
            } else {
                startOfDayMs(
                    today.minusDays((weeksAgo * DAYS_PER_WEEK + (DAYS_PER_WEEK - 1)).toLong()),
                    zone
                )
            }
            WeekResistance(
                label = weekLabel(weeksAgo),
                startMs = startMs,
                attempts = tally.attempts,
                walkAways = tally.walkAways,
                rate = tally.rate
            )
        }

        // Summing per-app attempts (rather than max-ing the grand totals) keeps the hero
        // count identical to the sum of the leaderboard rows the user can see.
        val attempts = apps.sumOf { it.attempts }
        val walkAways = apps.sumOf { it.walkAways }

        val (strongest, weakest) = extremeHours(hours)

        return WillpowerInsights(
            attempts = attempts,
            walkAways = walkAways,
            gaveIn = (attempts - walkAways).coerceAtLeast(0),
            walkAwayRate = ratio(walkAways, attempts),
            hours = hours,
            strongestHour = strongest,
            weakestHour = weakest,
            apps = apps,
            weeks = weeks
        )
    }

    /**
     * Strongest / weakest hour, ignoring hours with fewer than [MIN_HOUR_SAMPLE]
     * confrontations — a single 100%-or-0% hour is noise, not a pattern.
     *
     * The weakest hour is suppressed unless it is strictly worse than the strongest, so a
     * user whose only qualifying hours all sit at the same rate is never told their best
     * hour is also their worst.
     */
    private fun extremeHours(hours: List<HourResistance>): Pair<Int?, Int?> {
        val qualifying = hours.filter { it.attempts >= MIN_HOUR_SAMPLE }
        // No walk-away anywhere in the range means there is no hourly resistance pattern to
        // report — the chart renders its empty state in that case, and a "Strongest at 4pm"
        // caption under an empty chart reads as a contradiction (found in v1.13.0 device QA).
        if (qualifying.isEmpty() || qualifying.none { it.rate > 0f }) return null to null

        val strongest = qualifying.maxWith(
            compareBy<HourResistance> { it.rate }.thenBy { it.attempts }.thenByDescending { it.hour }
        )
        val weakest = qualifying.minWith(
            compareBy<HourResistance> { it.rate }.thenByDescending { it.attempts }.thenBy { it.hour }
        )
        return strongest.hour to if (weakest.rate < strongest.rate) weakest.hour else null
    }

    /**
     * Time the user did NOT spend in an app because they walked away, estimated as
     * `walk-aways x that app's average session length`.
     *
     * This is an estimate and is always labelled as one. Average session length comes
     * from `UsageStatsManager` (total foreground time / session count over the same
     * range) and is clamped to [MIN_SESSION_MS]..[MAX_SESSION_MS]: an app whose measured
     * average is four seconds (a glance) or four hours (a forgotten video) would
     * otherwise turn one walk-away into a laughable headline number. Apps with no usable
     * measurement fall back to [defaultSessionMs] and are counted in
     * [TimeReclaimed.appsEstimatedFromDefault] so the UI can say so.
     */
    fun estimateTimeReclaimed(
        apps: List<AppResistance>,
        avgSessionMsByPackage: Map<String, Long>,
        defaultSessionMs: Long = DEFAULT_SESSION_MS
    ): TimeReclaimed {
        var totalMs = 0L
        var measured = 0
        var defaulted = 0

        for (app in apps) {
            if (app.walkAways <= 0) continue
            val measuredMs = avgSessionMsByPackage[app.packageName]?.takeIf { it > 0L }
            val sessionMs = if (measuredMs != null) {
                measured++
                measuredMs.coerceIn(MIN_SESSION_MS, MAX_SESSION_MS)
            } else {
                defaulted++
                defaultSessionMs.coerceAtLeast(0L)
            }
            totalMs += app.walkAways.toLong() * sessionMs
        }

        return TimeReclaimed(
            totalMs = totalMs,
            appsMeasured = measured,
            appsEstimatedFromDefault = defaulted
        )
    }

    // --------------------------------------------------------- interventions

    /**
     * Per-app intervention totals inside `[sinceMs, nowMs]`, strongest pull first.
     *
     * The ONE answer to "which apps pull hardest" in this app. The Interventions screen's
     * leaderboard and the home dashboard's "Blocked most this week" card are the same
     * question asked over two different windows, and the recurring defect class in this
     * package is two computations of one number sitting on two screens, so there is one
     * loop, and [interventions] delegates to it rather than keeping a copy.
     *
     * Counts only [EventKind.SHOWN]: the walk-away row is the SAME confrontation written a
     * second time and would double every app's total.
     *
     * [interventions] calls this, which means it walks its window TWICE - once for its own hourly
     * and weekday buckets, once here. That is deliberate. The alternative is inlining a second
     * per-app loop back into [interventions], which is precisely the duplication this function was
     * extracted to remove, and the cost is one extra pass over a list already in memory. Do not
     * "optimise" it back into two rankings that can disagree. Both bounds are inclusive, so an event
     * landing exactly on the window start counts; an event in the future (a clock that moved
     * backwards) does not. [limit] is applied AFTER the full sort, so the top N is the top N
     * of the window rather than of whatever happened to be accumulated first.
     */
    fun topBlockedApps(
        events: List<UsageEvent>,
        sinceMs: Long,
        nowMs: Long,
        limit: Int
    ): List<AppInterventionStat> {
        if (limit <= 0) return emptyList()
        val perApp = LinkedHashMap<String, MutableMap<String, Int>>()
        for (event in events) {
            if (classify(event) != EventKind.SHOWN) continue
            val ts = event.timestamp
            if (ts > nowMs || ts < sinceMs) continue
            val modes = perApp.getOrPut(event.packageName) { linkedMapOf() }
            val mode = normalizeMode(event.blockMode)
            modes[mode] = (modes[mode] ?: 0) + 1
        }
        val ranked = perApp.entries
            .map { (pkg, modes) -> AppInterventionStat(pkg, modes.values.sum(), modes.toMap()) }
            .sortedWith(
                compareByDescending<AppInterventionStat> { it.total }.thenBy { it.packageName }
            )
        return ranked.take(limit)
    }

    fun interventions(
        events: List<UsageEvent>,
        nowMs: Long,
        zone: ZoneId,
        range: InsightsRange
    ): InterventionInsights {
        val today = localDate(nowMs, zone)
        val rangeStart = rangeStartMs(nowMs, zone, range)
        val todayStart = startOfDayMs(today, zone)
        val weekStart = rangeStartMs(nowMs, zone, InsightsRange.SEVEN_DAYS)
        val monthStart = rangeStartMs(nowMs, zone, InsightsRange.THIRTY_DAYS)

        var todayTotal = 0
        var weekTotal = 0
        var monthTotal = 0
        var rangeTotal = 0
        val hourly = MutableList(HOURS_PER_DAY) { 0 }
        val weekday = MutableList(DAYS_PER_WEEK) { 0 }
        val heatmap = List(DAYS_PER_WEEK) { MutableList(HOURS_PER_DAY) { 0 } }
        val daily = MutableList(SPARKLINE_DAYS) { 0 }

        for (event in events) {
            // Only the overlay-show event counts as an intervention; the walk-away row is
            // the SAME confrontation seen a second time and would double every bar here.
            if (classify(event) != EventKind.SHOWN) continue
            val at = Instant.ofEpochMilli(event.timestamp).atZone(zone)
            val ts = event.timestamp
            if (ts > nowMs) continue

            if (ts >= todayStart) todayTotal++
            if (ts >= weekStart) weekTotal++
            if (ts >= monthStart) monthTotal++

            val daysAgo = daysBetween(at.toLocalDate(), today)
            if (daysAgo in 0 until SPARKLINE_DAYS) {
                daily[SPARKLINE_DAYS - 1 - daysAgo]++
            }

            if (ts < rangeStart) continue
            rangeTotal++
            val dayIndex = at.dayOfWeek.value - 1 // Monday = 0
            hourly[at.hour]++
            weekday[dayIndex]++
            heatmap[dayIndex][at.hour]++
        }

        val apps = topBlockedApps(events, sinceMs = rangeStart, nowMs = nowMs, limit = NO_LIMIT)

        val dailySeries = daily.mapIndexed { index, count ->
            val date = today.minusDays((SPARKLINE_DAYS - 1 - index).toLong())
            DailyCount(startMs = startOfDayMs(date, zone), count = count)
        }

        return InterventionInsights(
            todayTotal = todayTotal,
            weekTotal = weekTotal,
            monthTotal = monthTotal,
            rangeTotal = rangeTotal,
            hourly = hourly,
            peakHour = peakIndex(hourly),
            weekday = weekday,
            peakWeekday = peakIndex(weekday),
            dailySeries = dailySeries,
            apps = apps,
            heatmap = heatmap.map { it.toList() }
        )
    }

    // -------------------------------------------------------------- helpers

    /**
     * Human label for a package. [resolvedName] is what PackageManager returned, which is
     * the package name itself when the app is no longer installed — in that case fall back
     * to a readable last segment ("com.zhiliaoapp.musically" -> "Musically") rather than
     * showing a raw package id in a leaderboard.
     */
    fun appDisplayLabel(packageName: String, resolvedName: String?): String {
        if (packageName == WEB_PSEUDO_PACKAGE) return "Websites"
        if (!resolvedName.isNullOrBlank() && resolvedName != packageName) return resolvedName
        val tail = packageName.substringAfterLast('.').replace('_', ' ').replace('-', ' ').trim()
        if (tail.isEmpty()) return packageName
        return tail.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    /** "38%" — one shared formatter so the ring, the callouts and the rows never disagree. */
    fun formatPercent(rate: Float): String = "${percentOf(rate)}%"

    /** NaN is coerced to 0 rather than thrown: `roundToInt()` raises on NaN, and a stats
     *  screen must never be the thing that crashes the app. */
    fun percentOf(rate: Float): Int =
        if (rate.isNaN()) 0 else (rate.coerceIn(0f, 1f) * 100f).roundToInt()

    /** 0 -> "12am", 13 -> "1pm". Out-of-range hours degrade to the raw number. */
    fun hourLabel(hour: Int): String = when {
        hour !in 0 until HOURS_PER_DAY -> hour.toString()
        hour == 0 -> "12am"
        hour < 12 -> "${hour}am"
        hour == 12 -> "12pm"
        else -> "${hour - 12}pm"
    }

    /** Short axis label for a weekday index. Index 0 is Monday — see [interventions]. */
    fun weekdayLabel(index: Int): String = WEEKDAY_LABELS.getOrElse(index) { "" }

    /**
     * Full weekday name for the same index. Lives here beside [weekdayLabel] so the
     * Monday-first ordering is asserted in exactly one place; a screen keeping its own
     * name list would silently mislabel every day if that ordering ever changed.
     */
    fun weekdayFullLabel(index: Int): String = WEEKDAY_FULL_LABELS.getOrElse(index) { "" }

    fun modeLabel(mode: String): String = when (mode) {
        "HARD_BLOCK" -> "Hard block"
        "DELAY" -> "Delay"
        "BREATHING" -> "Breathing"
        else -> "Other"
    }

    private fun normalizeMode(mode: String?): String =
        if (mode != null && mode in KNOWN_MODES) mode else OTHER_MODE

    private fun peakIndex(counts: List<Int>): Int? {
        val max = counts.maxOrNull() ?: 0
        if (max <= 0) return null
        return counts.indexOfFirst { it == max }
    }

    private fun inWindow(timestamp: Long, startMs: Long, nowMs: Long): Boolean =
        timestamp in startMs..nowMs

    private fun localDate(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private fun startOfDayMs(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun daysBetween(from: LocalDate, to: LocalDate): Int =
        ChronoUnit.DAYS.between(from, to).toInt()

    private fun weekLabel(weeksAgo: Int): String = when (weeksAgo) {
        0 -> "This wk"
        1 -> "1w ago"
        else -> "${weeksAgo}w ago"
    }

    /** Mutable accumulator; [attempts]/[rate] apply the orphan-walk-away rule per bucket. */
    private class Tally {
        var shown: Int = 0
        var walkAways: Int = 0

        fun add(kind: EventKind) {
            when (kind) {
                EventKind.SHOWN -> shown++
                EventKind.WALK_AWAY -> walkAways++
            }
        }

        val attempts: Int get() = maxOf(shown, walkAways)
        val gaveIn: Int get() = attempts - walkAways
        val rate: Float get() = ratio(walkAways, attempts)
    }

    internal enum class EventKind { SHOWN, WALK_AWAY }

    companion object {
        const val MIN_HOUR_SAMPLE = 2
        const val SPARKLINE_DAYS = 14
        const val DEFAULT_SESSION_MS = 5L * 60_000L
        const val MIN_SESSION_MS = 60_000L
        const val MAX_SESSION_MS = 30L * 60_000L
        const val WEB_PSEUDO_PACKAGE = "web"
        const val OTHER_MODE = "OTHER"

        /** "every app in the window" for [InsightsCalculator.topBlockedApps]. */
        const val NO_LIMIT = Int.MAX_VALUE

        private const val HOURS_PER_DAY = 24
        private const val DAYS_PER_WEEK = 7
        private val KNOWN_MODES = setOf("HARD_BLOCK", "DELAY", "BREATHING")
        private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        private val WEEKDAY_FULL_LABELS = listOf(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
        )

        /**
         * An ALLOW row is neither; it is logged for every allowed app launch and would
         * swamp both screens if it were counted as a confrontation.
         */
        internal fun classify(event: UsageEvent): EventKind? = when {
            event.userChangedMind -> EventKind.WALK_AWAY
            // The one predicate, shared with the DAO's count queries so a number on this screen
            // and the same number on a tile cannot come from two different definitions.
            event.isShownConfrontation -> EventKind.SHOWN
            else -> null
        }

        /** Safe rate: no NaN on an empty bucket, never above 1f. */
        internal fun ratio(part: Int, whole: Int): Float =
            if (whole <= 0) 0f else (part.toFloat() / whole.toFloat()).coerceIn(0f, 1f)
    }
}

/** The two windows offered by the range toggle on both insight screens. */
enum class InsightsRange(val days: Int, val label: String) {
    SEVEN_DAYS(7, "7 days"),
    THIRTY_DAYS(30, "30 days")
}

data class WillpowerInsights(
    val attempts: Int = 0,
    val walkAways: Int = 0,
    val gaveIn: Int = 0,
    val walkAwayRate: Float = 0f,
    val hours: List<HourResistance> = emptyList(),
    val strongestHour: Int? = null,
    val weakestHour: Int? = null,
    val apps: List<AppResistance> = emptyList(),
    val weeks: List<WeekResistance> = emptyList()
)

data class HourResistance(
    val hour: Int,
    val attempts: Int,
    val walkAways: Int,
    val rate: Float
)

data class AppResistance(
    val packageName: String,
    val attempts: Int,
    val walkAways: Int,
    val gaveIn: Int,
    val rate: Float
)

data class WeekResistance(
    val label: String,
    val startMs: Long,
    val attempts: Int,
    val walkAways: Int,
    val rate: Float
)

data class TimeReclaimed(
    val totalMs: Long = 0L,
    val appsMeasured: Int = 0,
    val appsEstimatedFromDefault: Int = 0
)

data class InterventionInsights(
    val todayTotal: Int = 0,
    val weekTotal: Int = 0,
    val monthTotal: Int = 0,
    val rangeTotal: Int = 0,
    val hourly: List<Int> = List(24) { 0 },
    val peakHour: Int? = null,
    val weekday: List<Int> = List(7) { 0 },
    val peakWeekday: Int? = null,
    val dailySeries: List<DailyCount> = emptyList(),
    val apps: List<AppInterventionStat> = emptyList(),
    val heatmap: List<List<Int>> = List(7) { List(24) { 0 } }
)

data class DailyCount(val startMs: Long, val count: Int)

data class AppInterventionStat(
    val packageName: String,
    val total: Int,
    val byMode: Map<String, Int>
)
