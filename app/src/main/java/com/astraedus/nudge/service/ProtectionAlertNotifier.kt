package com.astraedus.nudge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.astraedus.nudge.MainActivity
import com.astraedus.nudge.R
import com.astraedus.nudge.domain.health.ProtectionFault

/**
 * The one channel that can reach a user whose blocking has silently stopped.
 *
 * It has to be a PUSH notification, not an in-app banner. The failure this exists for is a phone
 * turning Nudge off overnight, and an app blocker's whole point is to be invisible until it is
 * needed — the user has no organic reason to open Nudge and see a banner, so the entire next day
 * runs unblocked. The app already holds `POST_NOTIFICATIONS`; this spends it.
 *
 * Separate channel from the ongoing "Nudge is active" monitor notification on purpose: a user who
 * mutes the silent, permanent one in their shade must not thereby mute the one that says
 * protection has stopped.
 */
object ProtectionAlertNotifier {

    private const val CHANNEL_ID = "nudge_protection_alerts"

    /** 1 belongs to [NudgeMonitorService]. */
    private const val NOTIFICATION_ID = 2

    fun notify(context: Context, fault: ProtectionFault) {
        createChannel(context)

        val (title, body) = when (fault) {
            ProtectionFault.ACCESSIBILITY_DISABLED ->
                context.getString(R.string.protection_alert_accessibility_title) to
                    context.getString(R.string.protection_alert_accessibility_body)

            // Different recovery, so different copy. This user's Accessibility toggle still reads
            // "on" — telling them to turn it on would read as nonsense and they would give up.
            ProtectionFault.ACCESSIBILITY_CRASHED ->
                context.getString(R.string.protection_alert_crashed_title) to
                    context.getString(R.string.protection_alert_crashed_body)

            ProtectionFault.MONITOR_SERVICE_DEAD ->
                context.getString(R.string.protection_alert_service_title) to
                    context.getString(R.string.protection_alert_service_body)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(settingsIntent(context))
            .build()

        // POST_NOTIFICATIONS is declared, but on Android 13+ it is a runtime grant the user can
        // refuse. Posting without it throws a SecurityException on some OEM builds rather than
        // silently dropping, and the watchdog must survive that: an alert we cannot deliver is
        // still better than a crash loop in the one component that notices failure.
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Nothing to do — the user has not granted notifications. MainActivity asks for the
            // grant on launch, and the Settings screen shows the same truth live.
        }
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Tapping the alert lands in Nudge's own Settings screen, not straight in the system
     * accessibility list. That screen is where the permission row and the Play-mandated prominent
     * disclosure dialog live (`docs/play-store.md` — this app has been rejected once on that
     * gate), so routing through it keeps the re-grant flow the one Google reviewed.
     */
    private fun settingsIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            /* requestCode = */ 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.protection_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.protection_alert_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
