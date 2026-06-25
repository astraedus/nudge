package com.astraedus.nudge.ui.lock

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the Strict Mode gating contract that every weakening-aware ViewModel delegates to:
 * when Strict Mode is OFF the action runs immediately; when ON the action is deferred (pending
 * challenge state set) and only runs after a successful verify. [StrictModeGate] is exercised
 * directly so the test has no Dispatchers.Main / viewModelScope dependency.
 */
class StrictModeGateTest {

    private fun gate(strictOn: Boolean, length: Int = StrictModeChallenge.DEFAULT_LENGTH): StrictModeGate {
        val prefs = mockk<NudgePreferences>()
        every { prefs.isStrictModeEnabled } returns flowOf(strictOn)
        every { prefs.strictModeChallengeLength } returns flowOf(length)
        return StrictModeGate(prefs)
    }

    @Test
    fun `strict mode off runs action immediately with no challenge`() = runTest {
        val gate = gate(strictOn = false)
        var ran = false

        gate.run(prompt = "weaken") { ran = true }

        assertTrue("action should run immediately when strict mode is off", ran)
        assertNull("no challenge should be shown", gate.challenge.value)
    }

    @Test
    fun `strict mode on defers action and sets pending challenge`() = runTest {
        val gate = gate(strictOn = true)
        var ran = false

        gate.run(prompt = "Turn off blocking") { ran = true }

        assertFalse("action must NOT run until the challenge passes", ran)
        val challenge = gate.challenge.value
        assertNotNull("a challenge should be pending", challenge)
        assertEquals("Turn off blocking", challenge!!.prompt)
        assertEquals(StrictModeChallenge.DEFAULT_LENGTH, challenge.target.length)
    }

    @Test
    fun `successful verify runs the pending action and clears the challenge`() = runTest {
        val gate = gate(strictOn = true)
        var ran = false

        gate.run(prompt = "weaken") { ran = true }
        val target = gate.challenge.value!!.target

        val ok = gate.verifyAndRun(target)

        assertTrue("verify should succeed on exact match", ok)
        assertTrue("pending action should run after a successful verify", ran)
        assertNull("challenge should clear after success", gate.challenge.value)
    }

    @Test
    fun `failed verify does not run the action and keeps the challenge up`() = runTest {
        val gate = gate(strictOn = true)
        var ran = false

        gate.run(prompt = "weaken") { ran = true }

        val ok = gate.verifyAndRun("definitely-wrong-input")

        assertFalse("verify should fail on mismatch", ok)
        assertFalse("action must not run on a failed verify", ran)
        assertNotNull("challenge should remain so the user can retry", gate.challenge.value)
    }

    @Test
    fun `cancel discards the pending action and clears the challenge`() = runTest {
        val gate = gate(strictOn = true)
        var ran = false

        gate.run(prompt = "weaken") { ran = true }
        gate.cancel()

        assertNull("challenge should clear on cancel", gate.challenge.value)
        assertFalse("cancelled action must never run", ran)

        // And a subsequent verify with the (now stale) input does nothing.
        assertFalse(gate.verifyAndRun("anything"))
        assertFalse(ran)
    }

    @Test
    fun `challenge length honors the configured difficulty`() = runTest {
        val gate = gate(strictOn = true, length = StrictModeChallenge.LENGTH_HARD)

        gate.run(prompt = "weaken") {}

        assertEquals(StrictModeChallenge.LENGTH_HARD, gate.challenge.value!!.target.length)
    }
}
