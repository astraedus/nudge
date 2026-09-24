package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard that the tab cover COUNTS NOTHING.
 *
 * ## The invariant
 *
 * A tap the cover eats is **not a confrontation**. No block screen was shown, the user was told
 * nothing, and nothing was refused to their face — they pressed a spot on a nav bar where an icon used
 * to be and the press went nowhere. Writing a `UsageEvent`, a `wasBlocked` row, or an arrival claim for
 * that would inflate the Blocked count exactly as issue #36 did (LESSONS 2026-09-20,
 * `BlockedCountSemanticsContractTest`).
 *
 * It would inflate it WORSE than #36, because the cover sits under a thumb: every stray tap at the
 * bottom of the screen while scrolling would land on it, so the number would drift upward for users
 * who never once tried to open Reels. A statistic that counts accidents is not a statistic.
 *
 * ## Why this test reads source code
 *
 * There is nothing to assert against. The correct behaviour is the ABSENCE of a call, and an absence
 * has no return value: a value-level test of "the cover did not count" is a test that passes whether
 * or not the manager was ever wired to a repository, because the manager has no repository to inject
 * in the first place. The regression this pins is somebody later reaching for one — "we should know how
 * often this fires" — which is a one-line edit that no existing test would notice.
 *
 * That is the same argument `AwarenessOverlayContractTest`, `BlockOverlayLaunchContractTest` and
 * `MonitorServiceContractTest` already make in this repo: when the defect lives in what a file is
 * allowed to reach, the file is what gets read. Per `docs/testing-strategy.md` this is a deliberate
 * source-level contract and NOT a substitute for a behavioural test — there is no behaviour here to
 * test, which is the whole point.
 */
class TabCoverCountingContractTest {

    private val coverManager = "main/java/com/astraedus/nudge/service/TabCoverOverlayManager.kt"

    /**
     * Names that would mean the cover had started writing history. `logEvent` and `UsageEvent` are the
     * usage-log entry points, `claimConfrontation` is the arrival claim from the #36 fix, and
     * `usageRepository` is how any of them would be reached.
     */
    private val forbidden = listOf(
        "UsageEvent",
        "usageRepository",
        "UsageRepository",
        "logEvent",
        "claimConfrontation"
    )

    private fun read(relative: String): String {
        val candidates = listOf(File("src/$relative"), File("app/src/$relative"))
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File("").absolutePath}"))
            .readText()
    }

    /**
     * Comments are stripped first, so the KDoc on the manager is free to EXPLAIN the rule by name —
     * which it must, or the next person deletes the rule without knowing it was one. Same
     * strip-then-scan shape as `AwarenessOverlayContractTest`.
     */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    @Test
    fun `the tab cover manager never reaches usage logging`() {
        val code = stripComments(read(coverManager))

        forbidden.forEach { name ->
            assertTrue(
                "TabCoverOverlayManager references '$name'. A tap the cover eats is not a " +
                    "confrontation: the user never saw a block screen, so counting it inflates the " +
                    "Blocked count the way issue #36 did — and here it would count stray taps at a " +
                    "nav bar, for users who never tried to open the blocked feature at all.",
                !code.contains(name)
            )
        }
    }

    /**
     * The rule has to be STATED where it can be read, not only enforced. An enforced-but-unexplained
     * rule gets deleted by the next person who needs a number, and the test that fails then looks like
     * an obstacle rather than a reason.
     */
    @Test
    fun `the manager documents why it counts nothing`() {
        val source = read(coverManager)

        assertTrue(
            "the counting invariant must be written in TabCoverOverlayManager's KDoc, naming the " +
                "issue it comes from, so a future reader knows the omission is deliberate",
            source.contains("#36") && source.contains("claimConfrontation")
        )
    }
}
