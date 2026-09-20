package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.isShownConfrontation
import com.astraedus.nudge.ui.screens.stats.InsightsCalculator
import com.astraedus.nudge.ui.screens.stats.InsightsRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The end the user actually cares about: after a restore, the insight pages read the same as they
 * did on the old phone.
 *
 * Everything in between -- the exporter, the parser, the merge -- can be individually correct while
 * the SCREENS still differ, because the screens are computed by [InsightsCalculator] over the rows.
 * So this test runs the real calculator over the source corpus and over the restored one and
 * demands identical output, which is a stronger claim than "the same rows came back".
 */
class ImportedHistoryInsightsTest {

    private val exporter = RuleExporter()
    private val calculator = InsightsCalculator()
    private val zone: ZoneId = ZoneId.of("Australia/Brisbane")

    /** Fixed "now" so day/hour bucketing is deterministic: 2026-08-20T21:00 Brisbane. */
    private val nowMs = 1_755_688_800_000L

    private val dayMs = 24L * 60L * 60L * 1000L

    /**
     * A corpus shaped like the real device: several apps, blocks spread over hours and days, and
     * walk-aways written as the SECOND row of a confrontation (`wasBlocked` AND `userChangedMind`),
     * which is the double-count `InsightsCalculator` exists to correct.
     */
    private fun sourceCorpus(): List<UsageEvent> = buildList {
        var id = 1L
        val apps = listOf("com.instagram.android", "com.google.android.youtube", "com.reddit.frontpage")
        for (daysAgo in 0..20) {
            for ((index, app) in apps.withIndex()) {
                val hour = (7 + index * 5 + daysAgo) % 24
                val ts = nowMs - daysAgo * dayMs - (21 - hour) * 60L * 60L * 1000L
                val mode = if (index == 0) "DELAY" else if (index == 1) "BREATHING" else "HARD_BLOCK"
                add(
                    UsageEvent(
                        id = id++,
                        packageName = app,
                        timestamp = ts,
                        wasBlocked = true,
                        blockMode = mode,
                        userChangedMind = false
                    )
                )
                // Every third confrontation ends in a walk-away: a second row, same moment.
                if ((daysAgo + index) % 3 == 0) {
                    add(
                        UsageEvent(
                            id = id++,
                            packageName = app,
                            timestamp = ts + 1,
                            wasBlocked = true,
                            blockMode = mode,
                            userChangedMind = true
                        )
                    )
                }
            }
        }
        // An ALLOW row and a web pseudo-package row, both of which the screens must keep handling.
        add(UsageEvent(id = id++, packageName = "com.app.allowed", timestamp = nowMs - 3600_000, wasBlocked = false))
        add(
            UsageEvent(
                id = id,
                packageName = "web",
                timestamp = nowMs - 7200_000,
                wasBlocked = true,
                blockMode = "HARD_BLOCK",
                userChangedMind = true
            )
        )
    }

    private fun restore(source: List<UsageEvent>): List<UsageEvent> {
        val json = exporter.exportRules(
            rules = emptyList(),
            groups = emptyList(),
            groupMembers = emptyMap(),
            history = source
        )
        val result = exporter.importRules(json)
        assertNull(result.error)
        assertEquals(0, result.invalidHistoryCount)
        return result.history.map(HistoryMerge::toEntity)
    }

    @Test
    fun `both insight pages render identically from a restored corpus`() {
        val source = sourceCorpus()
        val restored = restore(source)

        for (range in InsightsRange.entries) {
            assertEquals(
                "willpower differs after restore for $range",
                calculator.willpower(source, nowMs, zone, range),
                calculator.willpower(restored, nowMs, zone, range)
            )
            assertEquals(
                "interventions differ after restore for $range",
                calculator.interventions(source, nowMs, zone, range),
                calculator.interventions(restored, nowMs, zone, range)
            )
        }
    }

    @Test
    fun `the restored corpus is not trivially empty`() {
        val restored = restore(sourceCorpus())
        val willpower = calculator.willpower(restored, nowMs, zone, InsightsRange.THIRTY_DAYS)

        assertTrue("the fixture must actually exercise the calculator", willpower.attempts > 20)
        assertTrue(willpower.walkAways > 0)
        assertTrue(willpower.apps.size >= 3)
        assertTrue(willpower.walkAwayRate > 0f)
    }

    /**
     * The home tiles are DAO counts, not calculator output, so they are checked with the same
     * predicates the DAO queries use. A restored device must show the same two big numbers -- and
     * the raw rows must survive too, because the tile predicate is only correct if BOTH rows of a
     * walk-away came back.
     */
    @Test
    fun `the home tile counts match the source device`() {
        val source = sourceCorpus()
        val restored = restore(source)

        assertEquals(source.count { it.wasBlocked }, restored.count { it.wasBlocked })
        assertEquals(source.count { it.userChangedMind }, restored.count { it.userChangedMind })
        // `UsageEventDao.getAllTimeShownCount` / `getAllTimeChangedMindCount`, as the tiles read them.
        assertEquals(
            source.count { it.isShownConfrontation },
            restored.count { it.isShownConfrontation }
        )
    }

    /**
     * The merge is what protects those numbers from a second restore. Running the dedup pass with
     * the first import's own keys must leave nothing to add -- if it did not, every tile would
     * double.
     */
    @Test
    fun `restoring the same corpus twice does not move a single number`() {
        val source = sourceCorpus()
        val first = restore(source)

        val secondPass = HistoryMerge.selectNew(
            source.map(HistoryMerge::toExported),
            first.map(HistoryMerge::keyOf).toSet()
        )

        assertTrue("a second restore must add nothing", secondPass.isEmpty())
        assertEquals(
            calculator.willpower(source, nowMs, zone, InsightsRange.THIRTY_DAYS),
            calculator.willpower(first, nowMs, zone, InsightsRange.THIRTY_DAYS)
        )
    }
}
