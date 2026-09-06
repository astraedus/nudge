package com.astraedus.nudge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.astraedus.nudge.MainActivity

class NudgeMonitorService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "nudge_monitor"

        /**
         * Whether this service is currently running, as the watchdog sees it.
         *
         * A static flag is the honest answer here precisely BECAUSE it dies with the process: the
         * failure being watched for is the OS killing us, and a killed process comes back with
         * this false, which is exactly the state that needs reporting. (`getRunningServices()` is
         * restricted since API 26 and returns only our own services anyway, and a heartbeat
         * timestamp would just be this flag with extra I/O.)
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Starts the service if it is not already running; starting a running service is a no-op
         * beyond one extra `onStartCommand`.
         *
         * Returns false when the platform refused the start. Android 12+ forbids starting a
         * foreground service from the background, and the watchdog calls this from a
         * `WorkManager` worker — which is a background start. Nudge normally qualifies for the
         * `SYSTEM_ALERT_WINDOW` exemption, but that permission can be missing (onboarding lets it
         * be skipped), and then `startForegroundService` throws
         * `ForegroundServiceStartNotAllowedException`, an `IllegalStateException`. Throwing out of
         * a watchdog whose entire job is noticing failure would be its own kind of silent death.
         * The refusal is returned rather than logged here so the caller, which has the injected
         * [com.astraedus.nudge.util.NudgeLogger], can report it under the app's own log gate.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NudgeMonitorService::class.java)
            )
            true
        } catch (_: IllegalStateException) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NudgeMonitorService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(com.astraedus.nudge.R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(com.astraedus.nudge.R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nudge is active")
            .setContentText("Monitoring app usage")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
