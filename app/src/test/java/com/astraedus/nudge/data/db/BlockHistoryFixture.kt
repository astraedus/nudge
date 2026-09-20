package com.astraedus.nudge.data.db

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.isShownConfrontation
import com.astraedus.nudge.domain.usecase.RecordWalkAwayUseCase

/**
 * A day of real block history, plus the numbers SQLite would answer about it.
 *
 * ## Why a fake instead of a database
 *
 * The count queries live in `UsageEventDao` and there is no SQLite on the JVM here (no
 * Robolectric, no instrumented test target - see `app/build.gradle.kts`). So the counts a screen
 * receives are stood in for by [shownCount] / [changedMindCount] / [blockedRowCount], which
 * implement each query's documented predicate over the same rows. That split is deliberate rather
 * than a gap: the SQL text itself is pinned separately by `BlockedCountSemanticsContractTest`,
 * which asserts every `COUNT` query over `wasBlocked` also constrains `userChangedMind`, and what
 * these tests then exercise is the half a fake cannot fake - which query each screen chose to read.
 * That choice was the bug.
 *
 * The walk-away rows come from [RecordWalkAwayUseCase.buildEvent], the production writer, so a
 * fixture can never drift from the row shape the app actually stores.
 */
object BlockHistoryFixture {

    const val PACKAGE = "com.instagram.android"
    const val MODE = "BREATHING"

    private const val MINUTE_MS = 60_000L

    /**
     * [shown] confrontations the user faced and sat through, [walkAways] they turned around from,
     * and [allowed] ordinary opens of an unblocked app, all inside the day starting at
     * [dayStartMs].
     *
     * A walk-away contributes TWO rows, exactly as the app writes them: the overlay-shown row plus
     * the walk-away row. That is the whole reason the raw `wasBlocked` count was wrong, so a
     * fixture that did not reproduce it would prove nothing.
     */
    fun day(
        dayStartMs: Long,
        shown: Int,
        walkAways: Int,
        allowed: Int = 0,
        packageName: String = PACKAGE
    ): List<UsageEvent> {
        var at = dayStartMs + MINUTE_MS
        val rows = mutableListOf<UsageEvent>()
        repeat(shown) {
            rows += overlayShown(packageName, at)
            at += MINUTE_MS
        }
        repeat(walkAways) {
            rows += overlayShown(packageName, at)
            rows += RecordWalkAwayUseCase.buildEvent(packageName, MODE).copy(timestamp = at + 1)
            at += MINUTE_MS
        }
        repeat(allowed) {
            rows += UsageEvent(packageName = packageName, timestamp = at, wasBlocked = false)
            at += MINUTE_MS
        }
        return rows
    }

    /** The row written when the overlay goes up. */
    fun overlayShown(packageName: String = PACKAGE, timestamp: Long): UsageEvent = UsageEvent(
        packageName = packageName,
        timestamp = timestamp,
        wasBlocked = true,
        blockMode = MODE,
        userChangedMind = false
    )

    /** `UsageEventDao.getShownCount` / `getAllTimeShownCount`. */
    fun shownCount(rows: List<UsageEvent>, from: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE): Int =
        rows.count { it.isShownConfrontation && it.timestamp >= from && it.timestamp < until }

    /** `UsageEventDao.getChangedMindCount` / `getAllTimeChangedMindCount`. */
    fun changedMindCount(rows: List<UsageEvent>, from: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE): Int =
        rows.count { it.userChangedMind && it.timestamp >= from && it.timestamp < until }

    /**
     * What the DELETED raw query returned: every row carrying `wasBlocked`, walk-away rows
     * included. Kept only so a test can state the wrong number out loud and assert no screen
     * shows it.
     */
    fun blockedRowCount(rows: List<UsageEvent>, from: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE): Int =
        rows.count { it.wasBlocked && it.timestamp >= from && it.timestamp < until }
}
