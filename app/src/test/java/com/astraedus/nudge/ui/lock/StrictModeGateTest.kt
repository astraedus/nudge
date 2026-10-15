package com.astraedus.nudge.ui.lock

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.lock.LockedToggle
import com.astraedus.nudge.domain.lock.SettingsWeakening
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
 *
 * The last block pins the Settings-screen composition added in v1.10.0 — [SettingsWeakening] decides
 * WHICH flips are gated, this gate defers them — because that is where the escape-hatch toggle's new
 * asymmetric behaviour lives now that Strict Mode no longer freezes it.
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

    /**
     * Mirrors how `SettingsScreen` flips a lockable toggle: [SettingsWeakening] decides whether the
     * flip is gated, and only then does the flip go through the challenge. Returns whether the flip
     * was applied straight away, plus the pending challenge (null = none was raised).
     */
    private suspend fun flipToggle(
        toggle: LockedToggle,
        enable: Boolean,
        strictOn: Boolean
    ): Pair<Boolean, ChallengeState?> {
        val gate = gate(strictOn = strictOn)
        var applied = false
        if (SettingsWeakening.requiresUnlock(toggle, enable, strictOn)) {
            gate.run(prompt = "unlock") { applied = true }
        } else {
            applied = true
        }
        return applied to gate.challenge.value
    }

    @Test
    fun `enabling the escape hatch under strict mode is challenged, disabling is free`() = runTest {
        val (enabledApplied, enableChallenge) =
            flipToggle(LockedToggle.EMERGENCY_PASS, enable = true, strictOn = true)
        assertFalse("re-opening a one-tap bypass must not apply until unlocked", enabledApplied)
        assertNotNull("a challenge should be raised", enableChallenge)

        val (disabledApplied, disableChallenge) =
            flipToggle(LockedToggle.EMERGENCY_PASS, enable = false, strictOn = true)
        assertTrue("giving up the bypass strengthens protection — apply immediately", disabledApplied)
        assertNull("no challenge for a strengthening flip", disableChallenge)
    }

    @Test
    fun `with strict mode off the escape hatch flips freely both ways`() = runTest {
        for (enable in listOf(true, false)) {
            val (applied, challenge) =
                flipToggle(LockedToggle.EMERGENCY_PASS, enable = enable, strictOn = false)
            assertTrue("enable=$enable should apply immediately", applied)
            assertNull("enable=$enable should raise no challenge", challenge)
        }
    }

    @Test
    fun `turning strict mode off still routes through the challenge`() = runTest {
        val (applied, challenge) =
            flipToggle(LockedToggle.STRICT_MODE, enable = false, strictOn = true)

        assertFalse("the lock must not release without the unlock", applied)
        assertNotNull(challenge)
    }

    @Test
    fun `switching a content filter toggle off under strict mode is challenged, on and non-strict are free`() = runTest {
        for (toggle in listOf(LockedToggle.CONTENT_FILTER, LockedToggle.CONTENT_FILTER_STRICT_KEYWORDS)) {
            val (offApplied, offChallenge) = flipToggle(toggle, enable = false, strictOn = true)
            assertFalse("$toggle off must not apply until unlocked", offApplied)
            assertNotNull("$toggle off should raise a challenge", offChallenge)

            val (onApplied, onChallenge) = flipToggle(toggle, enable = true, strictOn = true)
            assertTrue("$toggle on is free", onApplied)
            assertNull(onChallenge)

            val (freeApplied, freeChallenge) = flipToggle(toggle, enable = false, strictOn = false)
            assertTrue("$toggle off without strict mode is free", freeApplied)
            assertNull(freeChallenge)
        }
    }
}
