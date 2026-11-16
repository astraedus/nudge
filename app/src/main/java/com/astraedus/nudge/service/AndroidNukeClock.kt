package com.astraedus.nudge.service

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.astraedus.nudge.domain.nuke.NukeClock
import com.astraedus.nudge.domain.nuke.NukeClockReading
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's side of [NukeClock]: `elapsedRealtime` (monotonic, counts deep sleep, resets on
 * reboot, unaffected by any date/time/zone setting) plus `Settings.Global.BOOT_COUNT` (API 24, below
 * our minSdk) to tell a reboot apart. A phone that will not say its boot count reads null, and the
 * timer falls back to the monotonic clock alone ([com.astraedus.nudge.domain.nuke.NukeTimer]).
 */
@Singleton
class AndroidNukeClock @Inject constructor(
    @ApplicationContext private val context: Context
) : NukeClock {
    override fun now(): NukeClockReading = NukeClockReading(
        elapsedMs = SystemClock.elapsedRealtime(),
        bootCount = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            null
        }
    )
}
