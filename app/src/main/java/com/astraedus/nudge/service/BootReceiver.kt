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

class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun nudgePreferences(): NudgePreferences
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

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

        if (globalEnabled) {
            NudgeMonitorService.start(context)
        }
    }
}
