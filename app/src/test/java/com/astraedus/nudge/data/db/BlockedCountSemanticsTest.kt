package com.astraedus.nudge.data.db

import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.db.entity.isShownConfrontation
import com.astraedus.nudge.domain.usecase.RecordWalkAwayUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the number under the word "Blocked" means: confrontations the user was shown, each
 * counted once.
 *
 * Device QA on 1.17.2 found the home tile rising by TWO for every walk-away, because a walk-away
 * writes a second row carrying `wasBlocked = true` and every "Blocked" surface counted that column
 * raw. This pins the predicate that replaced it, over rows built by the production writer.
 */
class BlockedCountSemanticsTest {

    private val dayStart = 1_700_000_000_000L

    @Test
    fun `turning around at the overlay is one confrontation, not two`() {
        val history = BlockHistoryFixture.day(dayStart, shown = 12, walkAways = 5)

        assertEquals("12 sat through + 5 turned around = 17 confrontations", 17, BlockHistoryFixture.shownCount(history))
        assertEquals(5, BlockHistoryFixture.changedMindCount(history))
    }

    @Test
    fun `the number a walk-away used to add to the Blocked tile was two`() {
        val history = BlockHistoryFixture.day(dayStart, shown = 0, walkAways = 1)

        // The old raw query counted both rows of one confrontation.
        assertEquals(2, BlockHistoryFixture.blockedRowCount(history))
        // The corrected one counts the confrontation.
        assertEquals(1, BlockHistoryFixture.shownCount(history))
    }

    /**
     * The bench Pixel's own numbers on the night this was reported: the tile read 210 with 29
     * walk-aways recorded, and the user had actually been confronted 181 times.
     */
    @Test
    fun `the bench device reads 181 confrontations, not 210`() {
        val history = BlockHistoryFixture.day(dayStart, shown = 152, walkAways = 29)

        assertEquals(210, BlockHistoryFixture.blockedRowCount(history))
        assertEquals(181, BlockHistoryFixture.shownCount(history))
        assertEquals(29, BlockHistoryFixture.changedMindCount(history))
    }

    @Test
    fun `an allowed open is not a confrontation at all`() {
        val history = BlockHistoryFixture.day(dayStart, shown = 3, walkAways = 0, allowed = 40)

        assertEquals(3, BlockHistoryFixture.shownCount(history))
    }

    @Test
    fun `only the day's own rows count toward the day's number`() {
        val yesterday = BlockHistoryFixture.day(dayStart - 86_400_000L, shown = 9, walkAways = 4)
        val today = BlockHistoryFixture.day(dayStart, shown = 2, walkAways = 1)
        val dayEnd = dayStart + 86_400_000L

        // 2 sat through + 1 turned around today; the 13 confrontations from the other day are
        // in the all-time number and nowhere near this one.
        assertEquals(3, BlockHistoryFixture.shownCount(yesterday + today, from = dayStart, until = dayEnd))
        assertEquals(16, BlockHistoryFixture.shownCount(yesterday + today))
        assertEquals(1, BlockHistoryFixture.changedMindCount(yesterday + today, from = dayStart, until = dayEnd))
    }

    @Test
    fun `the predicate answers each of the three row shapes the app writes`() {
        assertTrue(
            "the overlay-shown row IS the confrontation",
            BlockHistoryFixture.overlayShown(timestamp = dayStart).isShownConfrontation
        )
        assertFalse(
            "the walk-away row is the SAME confrontation seen again",
            RecordWalkAwayUseCase.buildEvent(BlockHistoryFixture.PACKAGE, "DELAY").isShownConfrontation
        )
        assertFalse(
            "an allowed open was never blocked",
            UsageEvent(packageName = BlockHistoryFixture.PACKAGE, timestamp = dayStart).isShownConfrontation
        )
    }
}
