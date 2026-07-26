package com.astraedus.nudge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.astraedus.nudge.MainActivity
import com.astraedus.nudge.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent "the lights are off" status notification.
 *
 * This exists because the single most-repeated complaint across every competitor in this category —
 * including iOS Downtime, with a multi-year, thousands-of-reports bug — is not a UX gripe but
 * **persistence**: the lockdown silently resets and users stop believing it will still be there
 * tomorrow. Nudge is zero-telemetry, so there is no server status page to reassure with; the answer is
 * a local, always-visible truth: *"Lights Off · active until 7:00 · 3 apps allowed."*
 *
 * It is an ordinary (non-foreground) ongoing notification, separate from
 * [NudgeMonitorService]'s foreground-service notification, so showing/hiding it can never disturb the
 * service lifecycle. Every platform call is wrapped: if the user denied POST_NOTIFICATIONS (Android
 * 13+) we silently do nothing rather than crash the accessibility service.
 */
@Singleton
class LightsOffStatusNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Volatile
    private var channelReady = false

    /** Last rendered content, so an unchanged 30s tick doesn't re-post the notification. */
    @Volatile
    private var lastRendered: String? = null

    /**
     * Show/refresh the status notification.
     *
     * @param untilLabel end of the active window, e.g. `"7:00"`; null when unknown.
     * @param allowedCount number of user-allow-listed apps (the safety floor is not counted — it is
     *   not something the user configured).
     */
    fun show(untilLabel: String?, allowedCount: Int) {
        val title = if (untilLabel.isNullOrBlank()) {
            "Lights Off · active"
        } else {
            "Lights Off · active until $untilLabel"
        }
        val text = when (allowedCount) {
            0 -> "Only essentials are awake"
            1 -> "1 allowed app is awake"
            else -> "$allowedCount allowed apps are awake"
        }
        val rendered = "$title|$text"
        if (rendered == lastRendered) return

        try {
            ensureChannel()
            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                PENDING_INTENT_REQUEST_CODE,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build()
            notificationManager()?.notify(NOTIFICATION_ID, notification)
            lastRendered = rendered
        } catch (_: Exception) {
            // Notifications are a trust affordance, never a dependency of enforcement.
        }
    }

    /** Remove the status notification (window ended, or Lights Off turned off). */
    fun hide() {
        if (lastRendered == null) return
        try {
            notificationManager()?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // ignore
        }
        lastRendered = null
    }

    private fun notificationManager(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        if (channelReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            channelReady = true
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.lights_off_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.lights_off_channel_description)
            setShowBadge(false)
        }
        notificationManager()?.createNotificationChannel(channel)
        channelReady = true
    }

    private companion object {
        const val CHANNEL_ID = "nudge_lights_off"

        /** 1 belongs to [NudgeMonitorService]'s foreground notification. */
        const val NOTIFICATION_ID = 2
        const val PENDING_INTENT_REQUEST_CODE = 2
    }
}
