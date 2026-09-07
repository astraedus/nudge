package com.astraedus.nudge.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The recovery latch in one behaviour: a collector that starts AFTER the bind still gets told to
 * look, and every subsequent bind/teardown tells it again.
 *
 * Both matter for the bug (see [AccessibilityConnectionSignal]): the Settings screen's initial read
 * and the start of its collection are two separate frames, and a signal that only pushed future
 * changes could drop a bind that lands between them, leaving the screen latched on ✖ exactly as it
 * was before the fix.
 */
class AccessibilityConnectionSignalTest {

    @Test
    fun `a collector joining after a change is still handed a value to react to`() = runTest {
        AccessibilityConnectionSignal.onConnectionChanged()

        val seen = AccessibilityConnectionSignal.generation.first()

        AccessibilityConnectionSignal.onConnectionChanged()
        assertNotEquals(
            "A late collector must not have to wait for the NEXT bind to re-read: the current " +
                "value is delivered on collection, and it must differ once a change has happened",
            seen,
            AccessibilityConnectionSignal.generation.first()
        )
    }

    @Test
    fun `every bind and teardown is a distinct generation`() = runTest {
        val start = AccessibilityConnectionSignal.generation.first()

        // onServiceConnected, onDestroy, onServiceConnected: three transitions, three re-reads.
        repeat(3) { AccessibilityConnectionSignal.onConnectionChanged() }

        assertEquals(
            "Consecutive transitions must each produce a new value, a re-bind after a teardown " +
                "is the exact sequence a user performs to recover a crashed service, and " +
                "collapsing the pair would leave the screen showing the teardown's state",
            start + 3,
            AccessibilityConnectionSignal.generation.first()
        )
    }
}
