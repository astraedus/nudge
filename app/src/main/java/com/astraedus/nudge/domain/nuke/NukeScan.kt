package com.astraedus.nudge.domain.nuke

import com.astraedus.nudge.domain.lock.StrictModeChallenge
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * What a scan means, and the emergency code. Pure.
 */
object NukeScan {

    /** The outcome of scanning a code on the "scan to start / end Nuke" action. */
    sealed interface ToggleOutcome {
        /** Nuke was off, this was the paired key, and the list is armable: turn it ON. */
        data object Arm : ToggleOutcome

        /** Nuke was on and this was the paired key: turn it OFF. */
        data object Disarm : ToggleOutcome

        /** A code was read, but it is not the paired key. Nothing changes. */
        data object WrongKey : ToggleOutcome

        /** No key is paired yet, so no scan can mean anything. */
        data object NoKey : ToggleOutcome

        /**
         * It was the right key, Nuke is on, and its timer has [remainingMs] left: refused, nothing
         * changes. The emergency code still works.
         */
        data class TimerRunning(val remainingMs: Long) : ToggleOutcome

        /** It was the right key but Nuke cannot be armed (the list is empty). */
        data class CannotArm(val reason: NukePolicy.ArmBlocker) : ToggleOutcome

        /** The scanner was cancelled, denied the camera, or read nothing. */
        data object Cancelled : ToggleOutcome
    }

    /**
     * Resolve a toggle scan. The key is the only thing consulted to decide Arm vs Disarm; the
     * direction comes from the current state, so one code both starts and ends Nuke, which is the
     * whole interaction the owner asked for. While a timer runs ([NukePolicy.keyWaitMs]) the key is
     * refused; [now] is the timer's clock, never wall time.
     */
    fun resolveToggle(
        payload: String?,
        state: NukeState,
        deviceFloor: Set<String>,
        now: NukeClockReading
    ): ToggleOutcome {
        if (payload.isNullOrBlank()) return ToggleOutcome.Cancelled
        if (!state.hasKey) return ToggleOutcome.NoKey
        if (!NukeKey.matches(payload, state.keyHash)) return ToggleOutcome.WrongKey
        if (state.active) {
            val wait = NukePolicy.keyWaitMs(state, now)
            return if (wait > 0) ToggleOutcome.TimerRunning(wait) else ToggleOutcome.Disarm
        }
        return NukePolicy.armBlocker(state, deviceFloor)
            ?.let { ToggleOutcome.CannotArm(it) }
            ?: ToggleOutcome.Arm
    }
}

/**
 * The emergency way out: a fresh random code, typed by hand.
 *
 * Reuses `StrictModeChallenge` wholesale (charset, display grouping, normalisation, verify) rather
 * than a second implementation, but at its own length. It deliberately does NOT raise
 * `StrictModeChallenge.MAX_LENGTH`: that constant bounds a value an IMPORTED file may install as the
 * Strict Mode difficulty, and a 64-character Strict Mode challenge set from a hand-edited backup is
 * exactly what it exists to refuse.
 */
object NukeEmergencyCode {
    /** Characters to type. Long enough to be annoying, short enough to always be finishable. */
    const val LENGTH = 64

    /**
     * A FRESH code per attempt. Drawn from [SecureRandom] by default: unlike the Strict Mode
     * challenge (a speed bump the user can already see), this code ends Nuke, so it must not be
     * predictable from anything else the app displays.
     */
    fun generate(random: Random = SecureRandom().asKotlinRandom()): String =
        StrictModeChallenge.generate(LENGTH, random)

    /** Same rule the dialog's counter uses, so "64/64" and a pass can never disagree. */
    fun verify(input: String, target: String): Boolean = StrictModeChallenge.verify(input, target)
}
