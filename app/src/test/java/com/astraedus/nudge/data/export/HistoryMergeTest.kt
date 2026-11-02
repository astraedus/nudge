package com.astraedus.nudge.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.astraedus.nudge.testing.aUsageEvent

/**
 * The merge policy for imported history, in isolation.
 *
 * The whole promise of this feature is "restoring a backup adds what is missing and touches nothing
 * else". That promise is decided entirely by these three pure functions, so it is decided here
 * rather than by watching numbers on a phone.
 */
class HistoryMergeTest {

    private fun exported(
        pkg: String = "com.app1",
        ts: Long,
        blocked: Boolean = true,
        mode: String? = "DELAY",
        changedMind: Boolean = false
    ) = ExportedHistoryEvent(pkg, ts, blocked, mode, changedMind)

    // --- Keys ------------------------------------------------------------------------------

    @Test
    fun `an exported event and the row it came from share a key`() {
        val row = aUsageEvent(
            id = 4242,
            packageName = "com.app1",
            timestamp = 1_700_000_000_000,
            wasBlocked = true,
            blockMode = "BREATHING",
            userChangedMind = true
        )

        assertEquals(HistoryMerge.keyOf(row), HistoryMerge.keyOf(HistoryMerge.toExported(row)))
    }

    /**
     * The row id is the exporting device's private business. If it were part of the key, importing
     * the same backup onto a phone with any history of its own would duplicate everything.
     */
    @Test
    fun `the row id is not part of the key`() {
        val a = aUsageEvent(id = 1, packageName = "com.app1", timestamp = 100, wasBlocked = true)
        val b = a.copy(id = 999)

        assertEquals(HistoryMerge.keyOf(a), HistoryMerge.keyOf(b))
    }

    /**
     * blockMode describes a confrontation, it does not identify one -- two events for the same app
     * in the same millisecond with the same flags are the same event whatever mode string is
     * attached. Keying on it would let a mode rename duplicate a user's entire history.
     */
    @Test
    fun `blockMode is not part of the key`() {
        val a = exported(ts = 100, mode = "DELAY")
        val b = exported(ts = 100, mode = "HARD_BLOCK")

        assertEquals(HistoryMerge.keyOf(a), HistoryMerge.keyOf(b))
    }

    @Test
    fun `package, timestamp and both flags all distinguish events`() {
        val base = exported(ts = 100)
        val keys = setOf(
            HistoryMerge.keyOf(base),
            HistoryMerge.keyOf(base.copy(packageName = "com.other")),
            HistoryMerge.keyOf(base.copy(timestamp = 101)),
            HistoryMerge.keyOf(base.copy(wasBlocked = false)),
            HistoryMerge.keyOf(base.copy(userChangedMind = true))
        )

        assertEquals("every axis of the key must matter", 5, keys.size)
    }

    // --- Range -----------------------------------------------------------------------------

    @Test
    fun `the timestamp range spans the whole file regardless of order`() {
        val range = HistoryMerge.timestampRange(
            listOf(exported(ts = 500), exported(ts = 100), exported(ts = 900), exported(ts = 300))
        )

        assertEquals(100L..900L, range)
    }

    @Test
    fun `a single event yields a single-point range`() {
        assertEquals(42L..42L, HistoryMerge.timestampRange(listOf(exported(ts = 42))))
    }

    /** No events means no query at all -- not a query over `0..0`, which would scan the epoch. */
    @Test
    fun `an empty file has no range`() {
        assertNull(HistoryMerge.timestampRange(emptyList()))
    }

    // --- Selection -------------------------------------------------------------------------

    @Test
    fun `nothing is new when every event is already stored`() {
        val events = listOf(exported(ts = 100), exported(ts = 200))
        val existing = events.map(HistoryMerge::keyOf).toSet()

        assertTrue(HistoryMerge.selectNew(events, existing).isEmpty())
    }

    @Test
    fun `everything is new against an empty table`() {
        val events = listOf(exported(ts = 100), exported(ts = 200))

        assertEquals(events, HistoryMerge.selectNew(events, emptySet()))
    }

    @Test
    fun `a partial overlap keeps exactly the missing events, in file order`() {
        val stored = listOf(exported(ts = 100), exported(ts = 300))
        val incoming = listOf(
            exported(ts = 100),
            exported(ts = 200),
            exported(ts = 300),
            exported(ts = 400)
        )

        val fresh = HistoryMerge.selectNew(incoming, stored.map(HistoryMerge::keyOf).toSet())

        assertEquals(listOf(200L, 400L), fresh.map { it.timestamp })
    }

    /**
     * A show event and its walk-away partner share a package and can share a millisecond; they
     * differ only in `userChangedMind`, and BOTH must survive or the "Blocked" and "Walked Away"
     * tiles stop agreeing.
     */
    @Test
    fun `a walk-away is not mistaken for its own show event`() {
        val show = exported(ts = 100, changedMind = false)
        val walkAway = exported(ts = 100, changedMind = true)

        val fresh = HistoryMerge.selectNew(listOf(show, walkAway), setOf(HistoryMerge.keyOf(show)))

        assertEquals(listOf(walkAway), fresh)
    }

    /**
     * Duplicates WITHIN one file are kept: the source device really had two such rows. Idempotency
     * does not depend on collapsing them -- on the second import both are already stored, so both
     * are recognised. (Pinned because "dedup" invites collapsing them by reflex.)
     */
    @Test
    fun `identical events within one file are both kept`() {
        val twice = listOf(exported(ts = 100), exported(ts = 100))

        val first = HistoryMerge.selectNew(twice, emptySet())
        val second = HistoryMerge.selectNew(twice, first.map(HistoryMerge::keyOf).toSet())

        assertEquals(2, first.size)
        assertTrue("a second import of the same file adds nothing", second.isEmpty())
    }

    // --- Mapping ---------------------------------------------------------------------------

    @Test
    fun `an imported event becomes a row with a fresh local id and every field intact`() {
        val row = HistoryMerge.toEntity(
            exported(pkg = "com.instagram.android", ts = 1_700_000_000_000, mode = null, changedMind = true)
        )

        assertEquals(0L, row.id)
        assertEquals("com.instagram.android", row.packageName)
        assertEquals(1_700_000_000_000, row.timestamp)
        assertEquals(true, row.wasBlocked)
        assertNull(row.blockMode)
        assertEquals(true, row.userChangedMind)
    }

    @Test
    fun `a row survives a round trip through the export shape`() {
        val row = aUsageEvent(
            id = 7,
            packageName = "com.app1",
            timestamp = 12345,
            wasBlocked = false,
            blockMode = "HARD_BLOCK",
            userChangedMind = false
        )

        assertEquals(row.copy(id = 0), HistoryMerge.toEntity(HistoryMerge.toExported(row)))
    }
}
