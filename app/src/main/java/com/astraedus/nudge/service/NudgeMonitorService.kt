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
 * degraded state hands off to [ProtectionCheck], which owns the one alert this app raises about
 * blocking being down. `allowBackup` is off as of the same change, which removes the nightly kill
 * that produced this window in the first place: the poll is the belt to that braces.
 *
 * ## Two clocks, one decision
 * This poll and [ProtectionWatchdogWorker] both reach [ProtectionCheck], and they are not
 * redundant. This one is fast (30s) but exists only while this process does, and the process dying
 * is the failure being watched for. The worker is slow (WorkManager's 15-minute floor) but survives
 * it. Neither holds any policy: the confirming cycle and the alert cooldown live in
 * `ProtectionWatchdog`, so the same fault produces the same decision from either clock.
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
        private const val CHANNEL_ID = "nudge_monitor"

        /** How often the health of the accessibility service is re-checked. */
        internal const val HEALTH_POLL_INTERVAL_MS = 30_000L

        /**
         * Whether this service is currently running, as the watchdog sees it.
         *
         * A static flag is the honest answer here precisely BECAUSE it dies with the process: the
         * failure being watched for is the OS killing us, and a killed process comes back with
         * this false, which is exactly the state that needs reporting. (`getRunningServices()` is
         * restricted since API 26 and returns only our own services anyway, and a heartbeat
         * timestamp would just be this flag with extra I/O.)
         *
         * It answers "is the FOREGROUND SERVICE alive", never "is Nudge enforcing" - that second
         * question is [ProtectionStatus]'s, because an in-process boolean cannot tell a process
         * that was killed from one that has only just started.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Starts the service if it is not already running.
         *
         * Returns false when the platform refused the start. Android 12+ forbids starting a
         * foreground service from the background, and [ProtectionCheck] calls this from a
         * `WorkManager` worker - which is a background start. Nudge normally qualifies for the
         * `SYSTEM_ALERT_WINDOW` exemption, but that permission can be missing (onboarding lets it
         * be skipped), and then `startForegroundService` throws
         * `ForegroundServiceStartNotAllowedException`, an `IllegalStateException`. Throwing out of
         * the one component whose job is noticing failure would be its own kind of silent death.
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

    // ONE status reader for the whole app. See AndroidAccessibilityStatusProvider: it delegates
    // to ProtectionStatus, which the Settings screen and the watchdog also read, so a health
    // poll and a permission tick can never disagree about whether Nudge is enforcing.
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
        isRunning = true
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

        val previouslyDegraded = lastPublishedHealth?.isDegraded == true
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

        // The ALERT is not posted here. Two lanes built a "blocking is down" notification in
        // parallel and both claimed notification id 2: this poll's, and ProtectionAlertNotifier's.
        // Shipping both would have been two notifications for one condition, each cancelling the
        // other's id - and this service's onDestroy cancelling the watchdog's alert at the exact
        // moment the alert became true.
        //
        // So there is one alert, and ProtectionCheck decides it. That keeps the policy the pure,
        // tested one for every caller: the confirming cycle that stops us crying wolf over a crash
        // the system heals in under three seconds (measured on the Pixel 3: 150ms-3s), the 12-hour
        // cooldown, and copy that names the right recovery per fault. What this poll adds is
        // LATENCY: reaching the same decision every 30s while the process is alive, instead of
        // waiting up to 15 minutes for WorkManager. The worker remains the path that still runs
        // when this service does not - which is the failure it was built for.
        //
        // Only when something is, or just was, wrong. A healthy check that stays healthy has
        // nothing to decide and must not write to DataStore every 30 seconds for the life of the
        // process.
        if (health.isDegraded || previouslyDegraded) {
            ProtectionCheck.run(applicationContext)
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

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        // The alert is ProtectionCheck's to post and to dismiss. This service must not cancel
        // it on the way out: the watchdog outlives this process, and a degraded state that is
        // still degraded must survive the very death that caused it.
        serviceScope.cancel()
    }
}
