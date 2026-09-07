package com.astraedus.nudge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.astraedus.nudge.data.preferences.NudgePreferences
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Brings monitoring back after the two events that end our process without the user doing
 * anything: a reboot, and an app update.
 *
 * The update half is the one that was missing. Play auto-updates overnight, and an in-place
 * update replaces our process, killing the foreground service. Android rebinds the
 * accessibility service itself, so blocking resumes, but our foreground service is the
 * process-priority protection that stops that binding being reaped once the screen goes off,
 * and nothing brought it back until the next reboot. So after every update the app quietly
 * lost its overnight protection. We shipped six releases in twelve days once.
 *
 * Corrected 2026-09-06: this comment previously claimed Android disables the accessibility
 * service on an in-place update. It does not, `onPackageUpdateFinished` clears the crashed
 * set and rebinds (AOSP 13/14/15/master). That false claim originated in a backlog note where
 * QA misread our own stale Settings UI, and it went on to mislead two investigations.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun nudgePreferences(): NudgePreferences
    }

    override fun onReceive(context: Context, intent: Intent) {
        // A membership test, not `!= ACTION_BOOT_COMPLETED`. The old inequality guard was why
        // adding a second action to the manifest would have silently done nothing.
        if (intent.action !in HANDLED_ACTIONS) return

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootReceiverEntryPoint::class.java
        )

        // Check if monitoring was enabled before reboot.
        // runBlocking is acceptable here -- BroadcastReceiver.onReceive runs on main thread
        // and must complete quickly, but DataStore read is fast (cached on disk).
        val globalEnabled = runBlocking {
            entryPoint.nudgePreferences().isGlobalEnabled.first()
        }

        // sync(), not start(): the service's existence tracks the master toggle everywhere it can
        // change, so a reboot with Nudge switched off does not resurrect a "Nudge is active"
        // notification for an app that is enforcing nothing.
        NudgeMonitorService.sync(context, globalEnabled)

        // Re-arm the watchdog even when monitoring is off: the check no-ops on a disabled master
        // toggle, and it is what will notice if the user turns protection back on while the
        // foreground service is somehow not running.
        ProtectionWatchdogWorker.enqueue(context)
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
