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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.astraedus.nudge.MainActivity
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.health.ServiceHealth
import com.astraedus.nudge.domain.health.notificationCopy
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps Nudge's process alive — and, since 2026-09-07, the only thing
 * in the app that can notice Nudge has stopped working and say so.
 *
 * ## What this service is NOT
 * It does not poll foreground apps, read rules, or make block decisions. Every enforcement decision
 * in Nudge is made by [NudgeAccessibilityService] from an accessibility event and gated on a rule
 * that matches the foreground package. **Nothing here may ever start an Activity.** The only
 * `MainActivity` reference in this file is a `PendingIntent` — a destination for a tap the user
 * makes, not a launch this service performs. `MonitorServiceContractTest` pins that distinction,
 * because a service that can put its own UI over another app is indistinguishable from a bug.
 *
 * ## The failure it now reports
 * At 01:59 on 2026-09-07 the Pixel's nightly `full_backup_package dev.astraedus.nudge` killed the
 * process. The OS rescheduled both services and Android's Settings screen said "Enabled, but your
 * phone stopped it, turn it off and back on to restart blocking". Nudge's own notification said
 * "Nudge is active / Monitoring app usage" the whole time, because that text was a constant. An app
 * blocker that has stopped blocking and still claims it is blocking is worse than one that crashes:
 * nothing prompts the user to fix it.
 *
 * So the notification is now a function of [ServiceHealth], refreshed on a slow poll, and a
 * degraded state also raises a separate DEFAULT-importance alert that deep-links to accessibility
 * settings. `allowBackup` is off as of the same change, which removes the nightly kill that
 * produced this window in the first place — the poll is the belt to that braces.
 *
 * Poll rather than observe: "the system unbound our service" fires no callback we can receive in a
 * process that was not running at the time. 30s is the same interval as the service's own clocks and
 * costs one settings read plus one string comparison.
 */
class NudgeMonitorService : Service() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MonitorEntryPoint {
        fun nudgePreferences(): NudgePreferences
        fun monitorLogger(): NudgeLogger
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val HEALTH_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "nudge_monitor"
        private const val HEALTH_CHANNEL_ID = "nudge_service_health"

        /** How often the health of the accessibility service is re-checked. */
        internal const val HEALTH_POLL_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, NudgeMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NudgeMonitorService::class.java))
        }

        /**
         * Start the service if Nudge is enabled, stop it if not.
         *
         * Called from every place the answer can change — boot, app launch, the master toggle — so
         * the service's existence tracks the master toggle instead of tracking whether the phone has
         * been rebooted since install. Before this, `start` had exactly one caller ([BootReceiver])
         * and `stop` had none: a fresh install never ran the service at all until the next reboot,
         * and once running it never stopped, so its "Nudge is active" notification outlived the
         * toggle being switched off.
         */
        fun sync(context: Context, globalEnabled: Boolean) {
            if (globalEnabled) start(context) else stop(context)
        }
    }

    private val entryPoint: MonitorEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, MonitorEntryPoint::class.java)
    }

    private val statusProvider: AccessibilityStatusProvider by lazy {
        AndroidAccessibilityStatusProvider(applicationContext)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last health published, so the log records transitions rather than one line every 30s. */
    private var lastPublishedHealth: ServiceHealth? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground FIRST, unconditionally: the platform kills a service that has not called
        // startForeground within a few seconds of being started, and after an OS-scheduled restart
        // this runs with a null intent and no guarantee that anything below it succeeds.
        startForeground(NOTIFICATION_ID, buildStatusNotification(ServiceHealth.ACTIVE))
        startHealthPoll()
        return START_STICKY
    }

    /**
     * Re-evaluate health every [HEALTH_POLL_INTERVAL_MS] and publish it.
     *
     * The try/catch wraps the ITERATION, not the loop: a single throwing tick must not end the poll
     * for the life of the process, which is the exact shape that silently killed both auto-kick
     * clocks (`tasks/lessons.md`, 2026-09-01). The exit reason is logged for the same reason.
     */
    private fun startHealthPoll() {
        if (pollJobStarted) return
        pollJobStarted = true
        serviceScope.launch {
            entryPoint.monitorLogger().i("monitor health poll started")
            while (isActive) {
                try {
                    if (!publishHealth()) return@launch
                } catch (e: Exception) {
                    entryPoint.monitorLogger().w("monitor health tick failed", e)
                }
                kotlinx.coroutines.delay(HEALTH_POLL_INTERVAL_MS)
            }
            entryPoint.monitorLogger().i("monitor health poll ended reason=scope_cancelled")
        }
    }

    private var pollJobStarted = false

    /**
     * Recompute and publish the current health.
     *
     * @return false when the service has stopped itself (Nudge disabled) and the poll must end.
     */
    private suspend fun publishHealth(): Boolean {
        val globalEnabled = entryPoint.nudgePreferences().isGlobalEnabled.first()
        val health = ServiceHealth.evaluate(
            globalEnabled = globalEnabled,
            permissionGranted = statusProvider.isPermissionGranted(),
            serviceConnected = statusProvider.isServiceConnected()
        )

        if (health != lastPublishedHealth) {
            entryPoint.monitorLogger().i(
                "monitor health $lastPublishedHealth -> $health " +
                    "(enabled=$globalEnabled granted=${statusProvider.isPermissionGranted()} " +
                    "connected=${statusProvider.isServiceConnected()})"
            )
            lastPublishedHealth = health
        }

        if (health == ServiceHealth.DISABLED) {
            // A disabled Nudge must behave as if uninstalled, notification included. The controller
            // in [sync] brings the service back when the toggle flips on.
            stopSelf()
            return false
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildStatusNotification(health))
        if (health.isDegraded) {
            manager?.notify(HEALTH_NOTIFICATION_ID, buildHealthAlert(health))
        } else {
            manager?.cancel(HEALTH_NOTIFICATION_ID)
        }
        return true
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(com.astraedus.nudge.R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    getString(com.astraedus.nudge.R.string.notification_channel_description)
                setShowBadge(false)
            }
        )

        // Separate channel because the importance is the point: the ongoing status notification is
        // deliberately IMPORTANCE_LOW (it is always there), and a silent low-importance line is
        // exactly how "blocking is down" went unnoticed for hours. Importance cannot be raised on an
        // existing channel, so the alert needs its own — and its own, so the user can silence the
        // permanent one without silencing this.
        manager.createNotificationChannel(
            NotificationChannel(
                HEALTH_CHANNEL_ID,
                getString(com.astraedus.nudge.R.string.health_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(com.astraedus.nudge.R.string.health_channel_description)
            }
        )
    }

    /**
     * The permanent status notification. Tapping it opens Nudge — a destination for the user's tap,
     * never a launch this service performs.
     */
    private fun buildStatusNotification(health: ServiceHealth): Notification {
        val copy = health.notificationCopy()
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(copy.title)
            .setContentText(copy.body)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * The recovery prompt for a degraded state.
     *
     * **A notification, never an Activity.** Recovery needs the user to go and flip a toggle in
     * Settings; the tempting shortcut is for the service to open that screen (or Nudge's own) by
     * itself, which is how an app blocker turns into an app that throws its UI over whatever the
     * user is doing, at a moment it cannot know is convenient. The deep link is the notification's
     * tap target, so the launch is the user's.
     */
    private fun buildHealthAlert(health: ServiceHealth): Notification {
        val copy = health.notificationCopy()
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, HEALTH_CHANNEL_ID)
            .setContentTitle(copy.title)
            .setContentText(copy.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.body))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(NotificationManager::class.java)?.cancel(HEALTH_NOTIFICATION_ID)
        serviceScope.cancel()
    }
}
