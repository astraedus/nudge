package com.astraedus.nudge.domain.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L1: [BlockInForce], the one "is this block still in force" answer the pass and the screen's end share (#170). */
class BlockInForceTest {

    private val end = 1_800_000_000_000L

    @Test
    fun `before the end it is in force, at and after the end it is not`() {
        assertTrue(BlockInForce.isInForce(end, end - 1))
        assertFalse(BlockInForce.isInForce(end, end))
        assertFalse(BlockInForce.isInForce(end, end + 60_000L))
    }

    @Test
    fun `a block with no end is always in force`() {
        assertTrue(BlockInForce.isInForce(null, end))
        assertTrue(BlockInForce.isInForce(null, Long.MAX_VALUE))
    }

    @Test
    fun `the wait is the time left, floored at zero, and null with no end`() {
        assertEquals(90_000L, BlockInForce.msUntil(end, end - 90_000L))
        assertEquals(0L, BlockInForce.msUntil(end, end))
        assertEquals(0L, BlockInForce.msUntil(end, end + 5_000L))
        assertNull(BlockInForce.msUntil(null, end))
    }

    @Test
    fun `the wait and the answer agree at every instant around the end`() {
        for (now in (end - 3)..(end + 3)) {
            assertEquals(BlockInForce.isInForce(end, now), BlockInForce.msUntil(end, now)!! > 0L)
        }
    }
}
