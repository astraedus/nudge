package com.astraedus.nudge

import android.app.Application
import com.astraedus.nudge.service.ProtectionWatchdogWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NudgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Arm the protection watchdog from the one callback that runs on EVERY process start —
        // a launch, a boot broadcast, an update, or WorkManager itself waking us. Scheduling it
        // anywhere narrower (only in MainActivity, say) would leave it unscheduled in exactly the
        // sessions where the app is never opened, which is when protection dies unnoticed.
        ProtectionWatchdogWorker.enqueue(this)
    }
}
