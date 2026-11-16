package com.astraedus.nudge.testing

import com.astraedus.nudge.domain.nuke.NukeClock
import com.astraedus.nudge.domain.nuke.NukeClockReading

/**
 * A hand-driven [NukeClock]: [advance] is phone-on time passing, [reboot] is a new boot whose uptime
 * starts again near zero. There is deliberately no wall clock here to move: the timer never reads
 * one, which the wall-clock tests pin by moving the JVM's clock around a real timer.
 */
class FakeNukeClock(
    var elapsedMs: Long = 5_000_000L,
    var bootCount: Int? = 7
) : NukeClock {
    override fun now(): NukeClockReading = NukeClockReading(elapsedMs, bootCount)

    fun advance(ms: Long) {
        elapsedMs += ms
    }

    /** A reboot: a new boot count (when the phone reports one) and [uptimeMs] since that boot. */
    fun reboot(uptimeMs: Long = 30_000L) {
        bootCount = bootCount?.plus(1)
        elapsedMs = uptimeMs
    }
}
