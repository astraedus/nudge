package com.astraedus.nudge.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard on the ONE definition of "Blocked".
 *
 * ## Why a test that reads source code
 *
 * The defect was not a wrong value anywhere: every `count { it.wasBlocked }` and every
 * `COUNT(*) … WHERE wasBlocked = 1` returned exactly what it asked for. What was wrong is that
 * five separate surfaces (home tile today, home tile all-time, the Today widget, the weekly trend
 * bars, App Detail) each asked the raw question, while the Interventions screen asked the right
 * one - so the app showed one quantity two ways and the louder one was wrong. A value-level test
 * can only ever pin the surfaces somebody remembered to fix; the sixth surface added next month
 * is the one that matters, and it is invisible to every assertion about numbers.
 *
 * This is the same tool, for the same reason, as `BlockOverlayLaunchContractTest` (one launch
 * site) and `ScreenTimeSourceContractTest` (one events pass).
 *
 * It also has a job no JVM test can otherwise do: there is no SQLite on this test target, so the
 * `@Query` strings are never executed here. Reading them as text is the only gate they have.
 */
class BlockedCountSemanticsContractTest {

    private fun read(relative: String): String {
        val candidates = listOf(File("src/$relative"), File("app/src/$relative"))
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File("").absolutePath}"))
            .readText()
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    private fun mainDir(relative: String): File {
        val candidates = listOf(File("src/$relative"), File("app/src/$relative"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("$relative not found from ${File("").absolutePath}")
    }

    private val dao: String by lazy {
        read("main/java/com/astraedus/nudge/data/db/dao/UsageEventDao.kt")
    }

    private val repository: String by lazy {
        read("main/java/com/astraedus/nudge/data/repository/UsageRepository.kt")
    }

    private val entity: String by lazy {
        read("main/java/com/astraedus/nudge/data/db/entity/UsageEvent.kt")
    }

    /** Every `@Query("…")` string in the DAO, comments stripped, concatenation joined. */
    private fun daoQueries(): List<String> =
        Regex("""@Query\(([\s\S]*?)\)\s*(?:suspend\s+)?fun""")
            .findAll(stripComments(dao))
            .map { match ->
                Regex(""""([^"]*)"""").findAll(match.groupValues[1])
                    .joinToString(" ") { it.groupValues[1] }
            }
            .toList()

    @Test
    fun `the query discovery actually finds the DAO's queries`() {
        // Without this, every assertion below passes vacuously the day the DAO is reformatted.
        val queries = daoQueries()
        assertTrue(
            "Found ${queries.size} @Query strings in UsageEventDao.kt; the discovery regex has " +
                "stopped matching the code it is meant to be reading.",
            queries.size >= 8
        )
        assertTrue(
            "no COUNT query found at all",
            queries.count { it.contains("COUNT(*)") && it.contains("wasBlocked") } >= 2
        )
    }

    /**
     * A COUNT over `wasBlocked` that does not also exclude `userChangedMind` counts every
     * walk-away twice. That is the bug, expressed in SQL.
     */
    @Test
    fun `no query counts blocked rows without excluding the walk-away row`() {
        val offenders = daoQueries().filter { query ->
            query.contains("COUNT(*)") &&
                query.contains("wasBlocked = 1") &&
                !query.contains("userChangedMind = 0")
        }

        assertEquals(
            "These queries count the walk-away row as a second block. A walk-away writes TWO " +
                "rows carrying `wasBlocked = 1`; add `AND userChangedMind = 0`, the SQL mirror " +
                "of UsageEvent.isShownConfrontation: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    /**
     * The raw readers are DELETED, not merely unused. A `getAllTimeBlockedCount()` left on the
     * repository is an invitation, and the next screen will accept it - the same reason the
     * day-scoped screen-time reads were deleted rather than deprecated in v1.15.1.
     */
    @Test
    fun `the raw blocked-count readers no longer exist to be called`() {
        val stripped = stripComments(dao) + stripComments(repository)
        for (gone in listOf(
            "fun getBlockedCount(",
            "fun getAllTimeBlockedCount(",
            "fun getBlockedCountForDay(",
            "getAllTimeBlockedCount()"
        )) {
            assertTrue(
                "`$gone` is back. Counting `wasBlocked` rows is not counting blocks: read " +
                    "getShownCount / getAllTimeShownCount instead.",
                !stripped.contains(gone)
            )
        }
    }

    @Test
    fun `the predicate is defined once, where the row shape is defined`() {
        assertTrue(
            "UsageEvent.isShownConfrontation must exist and be `wasBlocked && !userChangedMind`",
            stripComments(entity).contains("wasBlocked && !userChangedMind")
        )
    }

    /**
     * **The one that matters.** No screen, tile, chart or widget may read the raw column: the
     * question "was this row part of a block?" is not the question "was the user blocked?", and
     * every surface that confused the two showed a walk-away as two blocks.
     *
     * Discovered by walking `ui/`, never hand-listed, so a NEW screen is covered the day it is
     * written rather than the day someone remembers to add it here.
     */
    @Test
    fun `no screen counts the raw wasBlocked column`() {
        val uiFiles = mainDir("main/java/com/astraedus/nudge/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue("found ${uiFiles.size} ui sources; the walk is broken", uiFiles.size >= 20)

        val offenders = uiFiles
            .filter { stripComments(it.readText()).contains("wasBlocked") }
            .map { it.name }

        assertEquals(
            "These UI sources read `wasBlocked` directly. Use " +
                "`UsageEvent.isShownConfrontation` (in-memory) or " +
                "`UsageRepository.getShownCountForDay` / `getAllTimeShownCount` (counts), so the " +
                "number under the word Blocked means the same thing on every surface: $offenders",
            emptyList<String>(),
            offenders
        )

        assertTrue(
            "No UI source uses isShownConfrontation - the predicate is not actually reaching " +
                "the screens, so the check above is passing for the wrong reason.",
            uiFiles.count { it.readText().contains("isShownConfrontation") } >= 3
        )
    }

    /**
     * The mirror image, and the reason the sweep above must stop at `ui/`: a BACKUP carries rows,
     * not tiles. Both rows of a confrontation have to survive a round trip byte for byte, so the
     * export path reads `wasBlocked` raw and must keep doing so.
     */
    @Test
    fun `the export path still carries the raw rows`() {
        for (path in listOf(
            "main/java/com/astraedus/nudge/data/export/RuleExporter.kt",
            "main/java/com/astraedus/nudge/data/export/HistoryMerge.kt"
        )) {
            assertTrue(
                "$path no longer reads `wasBlocked`; a backup that drops the walk-away row's " +
                    "block flag cannot restore the history it saved.",
                stripComments(read(path)).contains("wasBlocked")
            )
        }
    }
}
