package com.astraedus.nudge

import android.app.Application
import com.astraedus.nudge.service.ProtectionWatchdogWorker
import com.astraedus.nudge.ui.widget.NudgeWidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NudgeApp : Application() {

    @Inject
    lateinit var widgetUpdater: NudgeWidgetUpdater

    override fun onCreate() {
        super.onCreate()
        // Start the widget observers here, for the same reason the watchdog is armed here: this is
        // the one callback that runs on EVERY process start. The widgets are kept fresh by
        // COLLECTING the data they display, so somebody has to hold those collectors open, and a
        // widget's own process is far too short-lived to be that somebody.
        widgetUpdater.start()
        // Arm the protection watchdog from the one callback that runs on EVERY process start —
        // a launch, a boot broadcast, an update, or WorkManager itself waking us. Scheduling it
        // anywhere narrower (only in MainActivity, say) would leave it unscheduled in exactly the
        // sessions where the app is never opened, which is when protection dies unnoticed.
        ProtectionWatchdogWorker.enqueue(this)
    }
}
