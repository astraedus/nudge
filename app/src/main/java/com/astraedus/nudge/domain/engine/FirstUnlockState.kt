package com.astraedus.nudge.domain.engine

import java.time.ZoneId

/**
 * The first-unlock anchor's inputs, as the engine needs them: the wall-clock ms of the first unlock
 * of the current logical day, the hour the logical day starts, and the zone to read both in.
 *
 * A port rather than parameters because the answer is global ("the first unlock of the day" is one
 * fact, not a rule's property), it is needed deep inside [ScheduleEvaluator] where no call site has
 * a context to thread, and the hottest reader is the accessibility event path, which must not touch
 * DataStore. Production binds `FirstUnlockStateCache`, which keeps all three in memory and updates
 * them from `NudgePreferences`. `NONE` is for callers with no anchor at all (a pure test that only
 * exercises days-and-times), and it means "no first-unlock window is ever active", the fail-open
 * reading. Nothing in the app uses it: Hilt always supplies the cache.
 */
interface FirstUnlockState {

    /** Wall-clock ms of the first unlock of the logical day, or [FirstUnlockWindow.NO_STAMP]. */
    fun firstUnlockAt(): Long

    /** The hour the logical day starts, minutes from midnight. */
    fun dayStartMinute(): Int

    /** The zone the logical day is read in. Wall-clock stamps are absolute; this only moves days. */
    fun zoneId(): ZoneId

    /** No anchor: every first-unlock window reads as inactive. */
    object NONE : FirstUnlockState {
        override fun firstUnlockAt(): Long = FirstUnlockWindow.NO_STAMP
        override fun dayStartMinute(): Int = FirstUnlockWindow.DEFAULT_DAY_START_MINUTE
        override fun zoneId(): ZoneId = ZoneId.systemDefault()
    }
}
