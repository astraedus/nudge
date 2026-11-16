package com.astraedus.nudge.domain.nuke

import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.domain.nuke.NukeScan.ToggleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** L1: what a scan means, and the 64-character emergency code. */
class NukeScanTest {

    private val token = NukeKey.generateToken()
    private val paired = NukeState(
        active = false,
        packages = setOf("com.instagram.android"),
        keyHash = NukeKey.hashOrNull(token)
    )

    private fun resolve(payload: String?, state: NukeState = paired, now: NukeClockReading = NukeClockReading(0, 1)) =
        NukeScan.resolveToggle(payload, state, emptySet(), now)

    @Test
    fun `while a timer runs the right key does not end Nuke, and says how long is left`() {
        val start = NukeClockReading(1_000_000, 4)
        val timed = paired.copy(active = true, timer = NukeTimer.start(30, start))
        assertEquals(
            ToggleOutcome.TimerRunning(20 * 60_000L),
            resolve(token, timed, NukeClockReading(1_000_000 + 10 * 60_000L, 4))
        )
        assertEquals(ToggleOutcome.Disarm, resolve(token, timed, NukeClockReading(1_000_000 + 30 * 60_000L, 4)))
        assertEquals("a wrong code is still wrong", ToggleOutcome.WrongKey, resolve("4006381333931", timed, start))
    }

    @Test
    fun `the paired key arms Nuke when it is off`() {
        assertEquals(ToggleOutcome.Arm, resolve(token))
    }

    @Test
    fun `the paired key disarms Nuke when it is on`() {
        assertEquals(ToggleOutcome.Disarm, resolve(token, paired.copy(active = true)))
    }

    @Test
    fun `a different code changes nothing, in either direction`() {
        assertEquals(ToggleOutcome.WrongKey, resolve("4006381333931"))
        assertEquals(ToggleOutcome.WrongKey, resolve(NukeKey.generateToken(), paired.copy(active = true)))
    }

    @Test
    fun `the right key with trailing whitespace still works`() {
        assertEquals(ToggleOutcome.Disarm, resolve("$token\n", paired.copy(active = true)))
    }

    @Test
    fun `no key paired means no scan can do anything`() {
        assertEquals(ToggleOutcome.NoKey, resolve(token, paired.copy(keyHash = null)))
    }

    @Test
    fun `the right key cannot arm an empty list`() {
        assertEquals(
            ToggleOutcome.CannotArm(NukePolicy.ArmBlocker.EMPTY_LIST),
            resolve(token, paired.copy(packages = emptySet()))
        )
    }

    @Test
    fun `a cancelled or empty scan is not a wrong key`() {
        assertEquals(ToggleOutcome.Cancelled, resolve(null))
        assertEquals(ToggleOutcome.Cancelled, resolve(""))
        assertEquals(ToggleOutcome.Cancelled, resolve("  "))
    }

    // --- the emergency code ------------------------------------------------------------------

    @Test
    fun `the emergency code is 64 characters from the unambiguous charset`() {
        val code = NukeEmergencyCode.generate()
        assertEquals(64, code.length)
        assertEquals(NukeEmergencyCode.LENGTH, code.length)
        assertTrue(code.all { it in StrictModeChallenge.CHARSET })
    }

    @Test
    fun `every attempt gets a fresh code`() {
        val codes = (1..20).map { NukeEmergencyCode.generate() }.toSet()
        assertEquals(20, codes.size)
    }

    @Test
    fun `the code verifies exactly, with or without the display dashes`() {
        val code = NukeEmergencyCode.generate(Random(7))
        assertTrue(NukeEmergencyCode.verify(code, code))
        assertTrue(NukeEmergencyCode.verify(StrictModeChallenge.forDisplay(code), code))
    }

    @Test
    fun `the code is case-sensitive and every character counts`() {
        val code = NukeEmergencyCode.generate(Random(11))
        assertFalse(NukeEmergencyCode.verify(code.dropLast(1), code))
        assertFalse(NukeEmergencyCode.verify(code.uppercase().takeIf { it != code } ?: "x", code))
        assertFalse(NukeEmergencyCode.verify("", code))
    }

    @Test
    fun `the Nuke length does not raise the import bound on Strict Mode's challenge`() {
        // MAX_LENGTH bounds what a backup file may install as the Strict Mode difficulty; Nuke's
        // longer code is its own constant and must not widen that door.
        assertEquals(StrictModeChallenge.LENGTH_HARD, StrictModeChallenge.MAX_LENGTH)
        assertTrue(NukeEmergencyCode.LENGTH > StrictModeChallenge.MAX_LENGTH)
    }
}
