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
import com.astraedus.nudge.domain.health.ServiceHealthCopy
import com.astraedus.nudge.domain.health.StatusNotificationGate
import com.astraedus.nudge.domain.health.notificationCopy
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.util.NudgeLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * So the notification is now a function of [ServiceHealth], re-evaluated whenever that health can
 * have changed, and a degraded state hands off to [ProtectionCheck], which owns the one alert this
 * app raises about blocking being down. `allowBackup` is off as of the same change, which removes
 * the nightly kill that produced this window in the first place: this re-evaluation is the belt to
 * that braces.
 *
 * ## Two clocks, one decision
 * This service and [ProtectionWatchdogWorker] both reach [ProtectionCheck], and they are not
 * redundant. This one is fast but exists only while this process does, and the process dying is
 * the failure being watched for. The worker is slow (WorkManager's 15-minute floor) but survives
 * it. Neither holds any policy: the confirming cycle and the alert cooldown live in
 * `ProtectionWatchdog`, so the same fault produces the same decision from either clock.
 *
 * ## What [#63](https://github.com/astraedus/nudge/issues/63) changed, and why
 *
 * A user read their own battery with BetterBatteryStats and found Nudge taking the
 * `NotificationManagerService:post:dev.astraedus.nudge` wakelock **267 times in 10h24m**, holding
 * the CPU awake ~5 minutes overnight and repeatedly interrupting Deep Doze. They also reported the
 * tell that names the cause: swipe the permanent notification away and it is back in seconds, with
 * identical text. Three separate mistakes produced that, and all three are fixed here:
 *
 * 1. **The poll re-posted the notification on every tick**, unconditionally. There is no
 *    platform-side dedup — `NotificationManagerService` takes its post wakelock before it looks at
 *    the content — so a byte-identical re-post costs a real one. [StatusNotificationGate] now holds
 *    the fingerprint of what is on screen and a tick that changes nothing posts nothing.
 * 2. **Every [start] re-ran `onStartCommand`**, and `onStartCommand` must call `startForeground`,
 *    which is another post. `NudgeAccessibilityService` calls [sync] from its `isGlobalEnabled`
 *    collector, and that flow re-emitted on *every* DataStore write in the app — including the one
 *    `ProtectionCheck` makes on each watchdog cycle. [start] now returns early when the service is
 *    already running (which its own doc always claimed it did), and `isGlobalEnabled` is
 *    `distinctUntilChanged` at the source.
 * 3. **The poll was a 30-second timer** where almost everything it watches for actually fires an
 *    event. See below.
 *
 * ## Event first, timeout second
 *
 * The original comment here said "poll rather than observe: the system unbound our service fires no
 * callback we can receive in a process that was not running at the time". That is true, and it is
 * an argument for [ProtectionWatchdogWorker] — not for this timer. In a process that IS running, an
 * unbind fires [AccessibilityConnectionSignal] from the accessibility service's own `onDestroy`,
 * and a rebind fires it from `onServiceConnected`; the master toggle is a Flow. So health is
 * re-evaluated on those two events, and [HEALTH_POLL_INTERVAL_MS] is now only the backstop for a
 * change neither of them announced.
 *
 * That costs alert latency on a fault that emits no event at all — worst case
 * [HEALTH_POLL_INTERVAL_MS] instead of 30 seconds to the *first* sighting — and there is no known
 * reachable fault of that shape, because disabling the permission unbinds the service and a process
 * kill takes this service with it. In exchange the idle device is left alone: a
 * `kotlinx.coroutines.delay` holds no wakelock and does not wake the phone, so with the notification
 * gated, an untouched Nudge now costs Doze nothing at all.
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

        /**
         * The longest this service will go without re-checking health when nothing has announced
         * a change.
         *
         * A backstop, not a heartbeat. The two things that can change health in a live process —
         * the accessibility binding and the master toggle — both emit, and the re-check runs on
         * those emissions; see the class doc. This interval only covers a change neither announced,
         * and [ProtectionWatchdogWorker] covers the whole process being dead.
         *
         * It was 30 seconds, and at 30 seconds it was the single largest source of the wakelock
         * storm in [#63](https://github.com/astraedus/nudge/issues/63): 1200+ ticks a night, each
         * re-posting a notification whose text had not changed.
         */
        internal const val HEALTH_POLL_INTERVAL_MS = 5L * 60L * 1000L

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
         *
         * The `if (isRunning)` early return is the first sentence of this doc, finally implemented.
         * Without it every caller re-entered `onStartCommand`, and `onStartCommand` has to call
         * `startForeground`, which posts the ongoing notification again — one
         * `NotificationManagerService` wakelock per call, for a notification whose text had not
         * changed. `NudgeAccessibilityService` calls [sync] from its `isGlobalEnabled` collector,
         * and before that flow was made `distinctUntilChanged` it re-emitted on every DataStore
         * write anywhere in the app, so this fired on a schedule nobody had designed
         * ([#63](https://github.com/astraedus/nudge/issues/63)).
         */
        fun start(context: Context): Boolean {
            if (isRunning) return true
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NudgeMonitorService::class.java)
                )
                true
            } catch (_: IllegalStateException) {
                false
            }
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

    /** This service's own lines, under a literal tag: never derived from the class (#120). */
    private val log: NudgeLog by lazy { entryPoint.monitorLogger().tagged("NudgeMonitor") }

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

    /** Last health published, so the log records transitions rather than one line per tick. */
    private var lastPublishedHealth: ServiceHealth? = null

    /**
     * What is currently on the user's screen, so an unchanged re-evaluation posts nothing.
     * This is the whole of the [#63](https://github.com/astraedus/nudge/issues/63) fix.
     */
    private val notificationGate = StatusNotificationGate()

    /**
     * The inputs the last evaluation was taken from. [awaitStateChangeOrTimeout] waits for
     * something DIFFERENT from these rather than for "the next emission", which closes the gap
     * between finishing an evaluation and starting to listen again: a change landing in that gap
     * is already visible as a difference, so it wakes us immediately instead of being slept
     * through for a whole [HEALTH_POLL_INTERVAL_MS].
     */
    private var lastSeenConnectionGeneration: Int = Int.MIN_VALUE
    private var lastSeenGlobalEnabled: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground FIRST, unconditionally: the platform kills a service that has not called
        // startForeground within a few seconds of being started, and after an OS-scheduled restart
        // this runs with a null intent and no guarantee that anything below it succeeds. So this
        // one post is not optional and is not gated - what [start] does instead is make sure we
        // only get here when the service was not already running.
        //
        // ACTIVE rather than the real health, because the real health needs an accessibility read
        // and a DataStore read and this call has a hard few-second deadline. The first evaluation
        // below runs immediately and corrects it within milliseconds if it was wrong, and the gate
        // means it costs a post only when it WAS wrong.
        val startupCopy = ServiceHealth.ACTIVE.notificationCopy()
        startForeground(NOTIFICATION_ID, buildStatusNotification(startupCopy))
        notificationGate.onPosted(startupCopy)
        isRunning = true
        startHealthPoll()
        return START_STICKY
    }

    /**
     * Re-evaluate health on every change that can affect it, and at most [HEALTH_POLL_INTERVAL_MS]
     * apart when nothing has.
     *
     * The try/catch wraps the ITERATION, not the loop: a single throwing tick must not end the poll
     * for the life of the process, which is the exact shape that silently killed both auto-kick
     * clocks (`tasks/lessons.md`, 2026-09-01). The exit reason is logged for the same reason. The
     * wait is inside the `try` and repeated in the `catch` so a throwing tick still waits its turn
     * — a bare `continue` here would spin the loop as fast as the exception can be thrown.
     */
    private fun startHealthPoll() {
        if (pollJobStarted) return
        pollJobStarted = true
        serviceScope.launch {
            log.i("monitor health poll started")
            while (isActive) {
                try {
                    if (!publishHealth()) return@launch
                    awaitStateChangeOrTimeout()
                } catch (e: Exception) {
                    log.w("monitor health tick failed", e)
                    delay(HEALTH_POLL_INTERVAL_MS)
                }
            }
            log.i("monitor health poll ended reason=scope_cancelled")
        }
    }

    private var pollJobStarted = false

    /**
     * Suspends until something that can change health changes, or [HEALTH_POLL_INTERVAL_MS] passes.
     *
     * Both sources are filtered against what [publishHealth] last SAW rather than dropped by
     * position, so a change that landed while the previous evaluation was still running is not
     * silently slept through. `withTimeoutOrNull` is a plain coroutine `delay` underneath: it holds
     * no wakelock and schedules no alarm, so an idle phone in Doze is not woken by any of this.
     */
    private suspend fun awaitStateChangeOrTimeout() {
        withTimeoutOrNull(HEALTH_POLL_INTERVAL_MS) {
            merge(
                AccessibilityConnectionSignal.generation
                    .filter { it != lastSeenConnectionGeneration }
                    .map { },
                entryPoint.nudgePreferences().isGlobalEnabled
                    .filter { it != lastSeenGlobalEnabled }
                    .map { }
            ).first()
        }
    }

    /**
     * Recompute and publish the current health.
     *
     * @return false when the service has stopped itself (Nudge disabled) and the poll must end.
     */
    private suspend fun publishHealth(): Boolean {
        // Read the connection generation BEFORE the status reads below, not after. A teardown
        // landing between the two then leaves a generation we have not recorded, so the wait that
        // follows returns immediately instead of sleeping on a reading that is already stale.
        lastSeenConnectionGeneration = AccessibilityConnectionSignal.generation.value
        val globalEnabled = entryPoint.nudgePreferences().isGlobalEnabled.first()
        lastSeenGlobalEnabled = globalEnabled
        // Read each of these ONCE. They were read a second time for the log line below, which is
        // two more framework calls per evaluation and, worse, lets the log describe a different
        // moment than the verdict it is explaining.
        val permissionGranted = statusProvider.isPermissionGranted()
        val serviceConnected = statusProvider.isServiceConnected()
        val health = ServiceHealth.evaluate(
            globalEnabled = globalEnabled,
            permissionGranted = permissionGranted,
            serviceConnected = serviceConnected
        )

        val previouslyDegraded = lastPublishedHealth?.isDegraded == true
        if (health != lastPublishedHealth) {
            log.i(
                "monitor health $lastPublishedHealth -> $health " +
                    "(enabled=$globalEnabled granted=$permissionGranted " +
                    "connected=$serviceConnected)"
            )
            lastPublishedHealth = health
        }

        if (health == ServiceHealth.DISABLED) {
            // A disabled Nudge must behave as if uninstalled, notification included. The controller
            // in [sync] brings the service back when the toggle flips on.
            stopSelf()
            return false
        }

        // Only when the words would actually change. `NotificationManagerService` takes its post
        // wakelock before it looks at the content, so an identical re-post is indistinguishable
        // from a real one to the battery: it is the whole of issue #63, at 267 of them a night.
        //
        // The user swiping this away on Android 14+ deliberately gets SILENCE, not a re-post. The
        // service keeps running (a dismissal does not stop a foreground service) and blocking is
        // unaffected, so re-posting would buy nothing and would be exactly the every-few-seconds
        // resurrection the reporter complained about. It comes back on the next real state change.
        val copy = health.notificationCopy()
        if (notificationGate.shouldPost(copy)) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildStatusNotification(copy))
                // After the post, never before: a notify that throws must not leave the gate
                // believing something is on screen that is not.
                notificationGate.onPosted(copy)
            }
        }

        // The ALERT is not posted here. Two lanes built a "blocking is down" notification in
        // parallel and both claimed notification id 2: this poll's, and ProtectionAlertNotifier's.
        // Shipping both would have been two notifications for one condition, each cancelling the
        // other's id - and this service's onDestroy cancelling the watchdog's alert at the exact
        // moment the alert became true.
        //
        // So there is one alert, and ProtectionCheck decides it. That keeps the policy the pure,
        // tested one for every caller: the confirming cycle that stops us crying wolf over a crash
        // the system heals in under three seconds (measured on the Pixel 3: 150ms-3s), the 12-hour
        // cooldown, and copy that names the right recovery per fault. What this path adds is
        // LATENCY: reaching the same decision the instant the accessibility binding changes, while
        // the process is alive, instead of waiting up to 15 minutes for WorkManager. The worker
        // remains the path that still runs when this service does not - which is the failure it
        // was built for.
        //
        // Only when something is, or just was, wrong. A healthy check that stays healthy has
        // nothing to decide and must not write to DataStore on every evaluation for the life of
        // the process - that write is itself a DataStore emission, and #63 is what happens when
        // one of those turns into a notification post.
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
     *
     * Takes the [ServiceHealthCopy] rather than the [ServiceHealth] so that the words this posts
     * and the words [StatusNotificationGate] fingerprints are provably the same object. Mapping
     * health to copy twice, once here and once at the gate, is how a "nothing changed" decision
     * quietly stops matching what is actually drawn.
     */
    private fun buildStatusNotification(copy: ServiceHealthCopy): Notification {
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
            // A status line, never an event. Belt to setSilent's braces on the rare OEM build that
            // re-alerts an IMPORTANCE_LOW channel when a notification is updated in place.
            .setOnlyAlertOnce(true)
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
