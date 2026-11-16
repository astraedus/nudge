package com.astraedus.nudge.domain.nuke

/**
 * One reading of the clock a Nuke timer runs on.
 *
 * @property elapsedMs milliseconds since this boot, counting deep sleep (`SystemClock.elapsedRealtime`
 *   on a phone). Never wall-clock time: Settings is reachable during Nuke, and a timer that read the
 *   wall clock could be ended early by moving the date forward.
 * @property bootCount which boot this is (`Settings.Global.BOOT_COUNT`), or null when the phone would
 *   not say. Only ever compared for equality.
 */
data class NukeClockReading(val elapsedMs: Long, val bootCount: Int?)

/**
 * The clock a Nuke timer reads. An interface so every case (a reboot, a wall-clock jump, a clock that
 * went backwards) is a JVM test; the Android reading lives in `service/AndroidNukeClock`.
 */
fun interface NukeClock {
    fun now(): NukeClockReading
}

/**
 * A timed Nuke (#184): until [durationMs] of phone-on time has been counted, the paired key does not
 * end Nuke (or anything else the key unlocks). The 64-character emergency code is not affected.
 *
 * ## The clock model
 * Time is counted on the monotonic clock only, in SEGMENTS: one per boot.
 * - [consumedMs] is the time counted before the current segment began.
 * - The current segment started at [segmentStartElapsedMs] on boot [bootCount].
 * - Counted = consumed + (now - segmentStart) on the same boot. On a NEW boot, the old segment's
 *   uncheckpointed tail is unknown, so only [consumedMs] carries over, and the new boot's whole
 *   uptime counts (the phone has been on since it booted).
 *
 * [checkpoint] folds the running segment into [consumedMs]. The app checkpoints whenever it can (the
 * screen going off, a shutdown, the Nuke screen opening), so a hard power-off or a crash loses at most
 * the time since the last checkpoint: the timer can only ever run LONGER than asked, never shorter,
 * and after a boot never longer than the full duration from that boot. Time with the phone switched
 * off does not count. The wall clock is never read, so changing the date, time or time zone moves
 * nothing.
 */
data class NukeTimer(
    val durationMs: Long,
    val consumedMs: Long,
    val bootCount: Int?,
    val segmentStartElapsedMs: Long
) {

    /**
     * Whether [now] is on the boot this segment started on. A boot count that changed means a reboot.
     * Without a boot count on either side, a clock that is now BEHIND the segment start can only mean
     * a reboot; one that is ahead is treated as the same boot, which can only under-count (a reboot
     * whose uptime already passed the old start counts from that old start, a later point).
     */
    private fun sameBoot(now: NukeClockReading): Boolean =
        if (bootCount != null && now.bootCount != null) {
            bootCount == now.bootCount
        } else {
            now.elapsedMs >= segmentStartElapsedMs
        }

    /** Phone-on time counted so far, never more than [durationMs]. */
    fun countedMs(now: NukeClockReading): Long {
        val segment = if (sameBoot(now)) {
            // Same boot, so the monotonic clock cannot have gone backwards; if it somehow did, count
            // nothing rather than something.
            (now.elapsedMs - segmentStartElapsedMs).coerceAtLeast(0)
        } else {
            now.elapsedMs.coerceAtLeast(0)
        }
        return (consumedMs.coerceIn(0, durationMs) + segment).coerceIn(0, durationMs)
    }

    /** Time left before the key works again: between 0 and [durationMs]. */
    fun remainingMs(now: NukeClockReading): Long = durationMs - countedMs(now)

    /** Fold the running segment into [consumedMs] and start a new segment at [now]. */
    fun checkpoint(now: NukeClockReading): NukeTimer = copy(
        consumedMs = countedMs(now),
        bootCount = now.bootCount,
        segmentStartElapsedMs = now.elapsedMs.coerceAtLeast(0)
    )

    companion object {
        /** The picker's choices, in minutes. 0 is "no timer". */
        val CHOICES_MINUTES: List<Int> = listOf(0, 15, 30, 45, 60, 120)

        /**
         * The longest timer a stored value may hold. Above every choice; anything longer (a corrupt
         * or hand-edited value) is read as no timer, so a bad value can never trap the key for days.
         */
        const val MAX_DURATION_MS: Long = 24L * 60 * 60 * 1000

        /** A new timer of [minutes], starting [now]; null for 0 ("no timer") or anything off the list. */
        fun start(minutes: Int, now: NukeClockReading): NukeTimer? {
            if (minutes <= 0 || minutes !in CHOICES_MINUTES) return null
            return NukeTimer(
                durationMs = minutes * 60_000L,
                consumedMs = 0,
                bootCount = now.bootCount,
                segmentStartElapsedMs = now.elapsedMs.coerceAtLeast(0)
            )
        }

        /**
         * A stored timer, validated. Fails soft: a missing or impossible duration or segment start is
         * no timer (Nuke itself is untouched; only the key's wait is dropped), and a consumed value
         * out of range is clamped.
         */
        fun fromStored(durationMs: Long?, consumedMs: Long?, bootCount: Int?, segmentStartElapsedMs: Long?): NukeTimer? {
            if (durationMs == null || durationMs <= 0 || durationMs > MAX_DURATION_MS) return null
            if (segmentStartElapsedMs == null || segmentStartElapsedMs < 0) return null
            return NukeTimer(
                durationMs = durationMs,
                consumedMs = (consumedMs ?: 0).coerceIn(0, durationMs),
                bootCount = bootCount,
                segmentStartElapsedMs = segmentStartElapsedMs
            )
        }

        /** A stored picker choice, validated: anything off the list is "no timer". */
        fun choiceOrNone(minutes: Int?): Int = minutes?.takeIf { it in CHOICES_MINUTES } ?: 0
    }
}
