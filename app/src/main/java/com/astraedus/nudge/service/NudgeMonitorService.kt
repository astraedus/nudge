package com.astraedus.nudge.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class NudgeMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: start foreground with notification
        return START_STICKY
    }
}
