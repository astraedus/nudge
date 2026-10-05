package com.astraedus.nudge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.astraedus.nudge.MainActivity
import com.astraedus.nudge.R
import com.astraedus.nudge.domain.bounce.BounceAlert

/**
 * Posts the "Bro. wtf." check-in (see `docs/architecture/bounce-check-in.md`).
 *
 * Its own channel, at DEFAULT importance: it should actually pop (sound, shade) the way a friend's
 * text would, but it is not an alarm, so it does not take the heads-up slot the protection alert
 * uses. A user who finds it annoying can mute this one channel without muting "blocking has
 * stopped".
 *
 * Tapping it opens Nudge's home screen. That is the user CHOOSING to look at their numbers, through
 * a `PendingIntent`, which is the only way a component under `service/` may reach `MainActivity`
 * (`MonitorServiceContractTest`): the accessibility service never puts UI in front of the user by
 * itself. Swiping it away is the other answer, and it is a fine one.
 */
object BounceCheckInNotifier {

    private const val BOUNCE_CHANNEL_ID = "nudge_bounce_checkins"

    /** 1 is the monitor's, 2 the protection alert's. Uniqueness is pinned by SharedNamespaceUniquenessTest. */
    private const val BOUNCE_NOTIFICATION_ID = 3

    fun notify(context: Context, alert: BounceAlert) {
        createChannel(context)

        val notification = NotificationCompat.Builder(context, BOUNCE_CHANNEL_ID)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(homeIntent(context))
            .build()

        // Same reasoning as ProtectionAlertNotifier: POST_NOTIFICATIONS is a runtime grant on
        // Android 13+, some OEM builds throw rather than drop when it is refused, and this runs on
        // the accessibility service's thread, which must never die for a notification. The
        // Settings row tells the user when notifications are off.
        try {
            NotificationManagerCompat.from(context).notify(BOUNCE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Not granted. Nothing else to do.
        }
    }

    /**
     * True when a check-in posted now could actually reach the user: notifications allowed for
     * Nudge AND this channel not switched off. Read by the Settings row so it can say why nothing
     * arrives, rather than leaving a switch that is on and silent.
     */
    fun canPost(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(BOUNCE_CHANNEL_ID)
            ?: return true // not created yet, so not blocked yet
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun homeIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            /* requestCode = */ 3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            BOUNCE_CHANNEL_ID,
            context.getString(R.string.bounce_checkin_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.bounce_checkin_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
