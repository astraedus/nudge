package com.astraedus.nudge.ui.screens.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard that "which apps pull hardest" is computed in exactly ONE place.
 *
 * [TopBlockedAppsTest] proves the shared function is correct and that `interventions()`
 * currently agrees with it — but a value test cannot see the shape that lets them drift.
 * The failure mode this pins is the cheap one: a future edit re-inlines a per-app loop into
 * `interventions()` "because it already has the events in hand", both surfaces stay green,
 * and the home card and the leaderboard start disagreeing the next time a counting rule
 * changes on one side only. That is the recurring defect class in this package
 * (`docs/architecture/stats-and-charts.md`: two computations of one number).
 *
 * Asserts the invariant (one ranking of [AppInterventionStat] exists in the file), not the
 * spelling, so a faithful refactor re-pins cleanly.
 */
class InterventionsDelegationContractTest {

    /**
     * The file's CODE, with comments stripped — the KDoc on `topBlockedApps` deliberately
     * explains the duplication it replaced, and scanning raw text would make documenting the
     * defect fail the test that prevents it.
     */
    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/$relativePath"), File("app/src/$relativePath"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}")
        return codeOf(file)
    }

    /** The same stripping, for a file found by walking rather than by name. */
    private fun codeOf(file: File): String = file.readText()
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    /** Every Kotlin file in `main`, so an invariant can be asserted about the APP, not a file. */
    private fun mainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("main sources not found from working dir ${File("").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val calculator =
        source("main/java/com/astraedus/nudge/ui/screens/stats/InsightsCalculator.kt")

    @Test
    fun `there is exactly one per-app intervention ranking in the calculator`() {
        val rankings = Regex("""compareByDescending<AppInterventionStat>""")
            .findAll(calculator).count()

        assertEquals(
            "AppInterventionStat must be ranked in exactly one place (topBlockedApps); " +
                "found $rankings rankings. Delegate instead of re-deriving.",
            1,
            rankings
        )
    }

    @Test
    fun `there is exactly one per-app accumulation of block modes`() {
        // The loop body that builds `package -> mode -> count`. Two of these means two
        // answers to one question, which is the whole reason this function was extracted.
        // (Willpower's per-app tally is a DIFFERENT question, resistance, not block modes, 
        // so it is deliberately not matched here; the mode normalisation is the signature.)
        val accumulations = Regex("""normalizeMode\(event\.blockMode\)""").findAll(calculator).count()

        assertEquals(
            "Per-app mode accumulation belongs only in topBlockedApps; found $accumulations.",
            1,
            accumulations
        )
    }

    @Test
    fun `interventions delegates to the shared function`() {
        assertTrue(
            "interventions() must call topBlockedApps() rather than keeping its own copy.",
            calculator.contains("topBlockedApps(events, sinceMs = rangeStart")
        )
    }

    /** The home card is the second caller; if it stops calling in, the extraction was pointless. */
    @Test
    fun `the home dashboard reads the same shared function`() {
        val homeViewModel =
            source("main/java/com/astraedus/nudge/ui/screens/home/HomeViewModel.kt")

        assertTrue(
            "HomeViewModel must source its top-blocked list from InsightsCalculator.topBlockedApps.",
            homeViewModel.contains("topBlockedApps(")
        )
    }

    /**
     * The home card and the week of rows it aggregates must be one emission, not two.
     *
     * `weekEventsFlow` and `screenTimeFlow` are independent `flatMapLatest` chains off a single
     * `dayStartFlow`, so at a midnight rollover they restart independently and for one frame a
     * consumer can hold a new day's boundary beside the previous day's rows. Re-deriving "a week
     * ago" at the card would therefore describe a window its own data was not selected with.
     *
     * So the boundary is computed ONCE, in the query, and travels with the rows. This used to be
     * pinned as "exactly one `startOfDayDaysBefore(..., WEEK_DAYS - 1)` exists in HomeViewModel",
     * which was the right invariant expressed as a local spelling: the same expression then got
     * written a second time elsewhere and HomeViewModel stayed green. It is now pinned where it
     * belongs — app-wide — by [the trailing-week boundary has exactly one definition in the app].
     * What remains here is the half that is genuinely about this screen: the card reads its
     * window off the snapshot its rows arrived in, and asks for the boundary once.
     */
    @Test
    fun `the home dashboard asks for the shared boundary instead of re-deriving it`() {
        val homeViewModel =
            source("main/java/com/astraedus/nudge/ui/screens/home/HomeViewModel.kt")
        val calls = Regex("""startOfTrailingWeek\(""").findAll(homeViewModel).count()

        assertEquals(
            "The dashboard's trailing-week boundary must be asked for exactly once and then " +
                "carried with its rows; found $calls calls to startOfTrailingWeek.",
            1,
            calls
        )
        assertTrue(
            "The top-blocked window must come from the snapshot the events arrived in, not " +
                "from another flow's day start.",
            Regex("""sinceMs\s*=\s*\w+\.weekStartMs""").containsMatchIn(homeViewModel)
        )
    }

    /**
     * "A week ago" is one definition, `TimeTracker.startOfTrailingWeek`, and nowhere else.
     *
     * `WEEK_DAYS - 1` (today plus the six days before it) is the kind of off-by-one every
     * caller re-derives slightly differently, and a caller that gets it wrong produces a card
     * whose heading and whose rows describe different weeks — which is exactly the defect class
     * this file exists for, one question with two answers. Enumerating the offenders rather
     * than counting them means the failure message names the file to fix.
     *
     * `ScreenTimeProvider`'s `(WEEK_DAYS - 1 downTo 0)` is deliberately NOT an offender: it
     * enumerates the days of the window, it does not compute the window's start.
     */
    @Test
    fun `the trailing-week boundary has exactly one definition in the app`() {
        val boundary = Regex("""startOfDayDaysBefore\([^)]*WEEK_DAYS\s*-\s*1\s*\)""")
        val sites = mainSources()
            .map { file -> file.name to boundary.findAll(codeOf(file)).count() }
            .filter { (_, count) -> count > 0 }
            .sortedBy { (name, _) -> name }
            .map { (name, count) -> "$name x$count" }

        assertEquals(
            "The trailing-week boundary must be spelled out only in TimeTracker (every other " +
                "caller goes through startOfTrailingWeek); found $sites.",
            listOf("TimeTracker.kt x1"),
            sites
        )
    }
}
