package com.astraedus.nudge.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard that a dashboard tile which navigates SAYS SO.
 *
 * The report: *"I had no idea I could click the Blocked and Walked Away tiles on Home and that
 * they lead to two different screens with charts."* Two whole insight screens had exactly one
 * entry point each, and that entry point gave no signal at all. Three mechanical causes, all
 * invisible to a value-level test:
 *
 *  1. `Theme.kt` disabled Material ripple APP-WIDE (one line in a bulk perf commit, 32b9348),
 *     so no `Modifier.clickable` anywhere in the app produced touch feedback.
 *  2. `StatCard` had no chevron, no action label, no `onClickLabel` — nothing.
 *  3. The same destination appeared under two different tiles with no indication either was
 *     a link, so even a user who tried tapping learned nothing about where they were going.
 *
 * What is pinned here is the SHAPE: a tile that navigates carries a label naming where it
 * goes, tiles pointing at the same destination name it the same way, and nobody reintroduces
 * the global ripple kill. A future tile cannot ship inert or unlabelled.
 */
class HomeTileAffordanceContractTest {

    /**
     * The file's CODE, with comments stripped. `Theme.kt` and `HomeScreen.kt` both document
     * the defect they were fixed for by name, so scanning raw text would make writing that
     * explanation fail the very test that protects it.
     */
    private fun source(relativePath: String): String {
        val candidates = listOf(File("src/$relativePath"), File("app/src/$relativePath"))
        val text = (candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}"))
            .readText()
        return text
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    private val homeScreen = source("main/java/com/astraedus/nudge/ui/screens/home/HomeScreen.kt")
    private val theme = source("main/java/com/astraedus/nudge/ui/theme/Theme.kt")

    /**
     * Every `StatCard(...)` CALL (not the declaration), as the text between its parentheses.
     * Paren-balanced rather than regex-terminated, because the argument lists contain
     * lambdas, nested calls and `if` expressions with parentheses of their own.
     */
    private fun statCardCallSites(): List<String> =
        Regex("""(?<!fun )\bStatCard\(""").findAll(homeScreen).map { match ->
            var depth = 0
            var i = match.range.last // the '(' itself
            val start = i + 1
            while (i < homeScreen.length) {
                when (homeScreen[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return@map homeScreen.substring(start, i)
                    }
                }
                i++
            }
            error("unbalanced parentheses in a StatCard call site")
        }.toList()

    @Test
    fun `the dashboard still has its six tiles`() {
        // If this number changes, the cases below are checking a different screen than the
        // one the report was about — deliberate or not, look at them again.
        assertEquals(6, statCardCallSites().size)
    }

    /** Cause 2: a tile that goes somewhere must have a label saying where. */
    @Test
    fun `every tile that navigates passes an action label`() {
        statCardCallSites().forEachIndexed { index, args ->
            assertTrue(
                "StatCard call #$index navigates but passes no actionLabel; a tile with no " +
                    "affordance is the bug this test exists for.\n$args",
                args.contains("onClick =") && args.contains("actionLabel =")
            )
        }
    }

    /** No inert tiles: every tile on this dashboard is a way into something. */
    @Test
    fun `no tile is left without an onClick`() {
        assertTrue(statCardCallSites().all { it.contains("onClick =") })
    }

    /**
     * Cause 3. Four of the six tiles point at only two destinations. That duplication is
     * fine — what is not fine is the same destination being described two different ways,
     * which is how a user concludes the two tiles do different things.
     */
    @Test
    fun `tiles sharing a destination share the same action label`() {
        // Scanned over the WHOLE argument list rather than by matching `onClick = <identifier>`:
        // the Screen Time tile's onClick and actionLabel are both conditional expressions, so an
        // identifier-anchored regex silently drops that call site and the test would quietly be
        // checking five of six tiles while claiming to check all of them.
        val pairs = statCardCallSites().map { args ->
            val destinations = Regex("""\bonNavigateTo\w+""").findAll(args).map { it.value }
                .distinct().toList()
            val labels = Regex("""actionLabel = ([^\n]*)""").find(args)
                ?.groupValues?.get(1)
                ?.let { expr -> Regex(""""([^"]+)"""").findAll(expr).map { it.groupValues[1] }.toList() }
                .orEmpty()
            args to (destinations to labels)
        }

        pairs.forEachIndexed { index, (args, pair) ->
            val (destinations, labels) = pair
            assertTrue(
                "StatCard call #$index names no navigation destination, so this test cannot " +
                    "check it. Route tiles through an onNavigateTo* callback.\n$args",
                destinations.isNotEmpty()
            )
            assertTrue(
                "StatCard call #$index passes no literal actionLabel.\n$args",
                labels.isNotEmpty()
            )
        }

        val labelsByDestination = pairs
            .flatMap { (_, pair) -> pair.first.flatMap { dest -> pair.second.map { dest to it } } }
            .groupBy({ it.first }, { it.second })

        labelsByDestination.forEach { (destination, labels) ->
            assertEquals(
                "$destination is described as ${labels.distinct()} on different tiles; " +
                    "one destination, one name.",
                1,
                labels.distinct().size
            )
        }

        // And the two insight screens must actually be among the destinations named, or the
        // report is unaddressed no matter how pretty the tiles are.
        assertTrue(
            "The Interventions and Willpower tiles must both carry a label.",
            labelsByDestination.containsKey("onNavigateToInterventions") &&
                labelsByDestination.containsKey("onNavigateToWillpower")
        )
    }

    /** Every navigating tile announces its action to TalkBack, not just to sighted users. */
    @Test
    fun `the tile click carries an onClickLabel for TalkBack`() {
        assertTrue(
            "StatCard must pass actionLabel through as clickable(onClickLabel = ...).",
            homeScreen.contains("clickable(onClick = onClick, onClickLabel = actionLabel)")
        )
    }

    /** Cause 1, the root cause, and the one most likely to be "helpfully" reintroduced. */
    @Test
    fun `material ripple is not disabled app-wide`() {
        assertFalse(
            "Theme.kt must not kill the ripple for the whole app. Every Modifier.clickable " +
                "in Nudge goes silent when it does, which is why nothing looked tappable. " +
                "If one surface measurably janks, scope the override to that composable.",
            theme.contains("LocalRippleConfiguration")
        )
    }

    @Test
    fun `the dashboard does not disable its own ripple either`() {
        assertFalse(homeScreen.contains("LocalRippleConfiguration"))
        assertFalse(homeScreen.contains("indication = null"))
    }

    /** Material iconography only — the standing rule against emoji as UI icons. */
    @Test
    fun `the dashboard uses no emoji as iconography`() {
        val emoji = Regex("""[\uD800-\uDBFF][\uDC00-\uDFFF]""")
        assertFalse(
            "Emoji are not icons in this app; use Icons.Outlined.*.",
            emoji.containsMatchIn(homeScreen)
        )
    }
}
