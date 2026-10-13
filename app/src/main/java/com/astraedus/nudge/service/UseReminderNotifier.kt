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
import com.astraedus.nudge.domain.autokick.UseReminder

/**
 * Posts the usage reminder ([#77](https://github.com/astraedus/nudge/issues/77)): "You've been on
 * Instagram for 20 min". The decision of WHEN lives in `AutoKickTimeHandler.tick` /
 * `UseReminderEvaluator`; this only delivers.
 *
 * Its own channel, "Usage reminders", at DEFAULT importance, the same reasoning as the bounce
 * check-in's: it should actually reach someone mid-scroll (sound, shade), it is not an alarm, so it
 * does not take the heads-up slot the protection alert uses, and a user who wants it quieter can
 * lower or mute this ONE channel without touching "blocking has stopped" or the check-ins.
 *
 * One notification id for every app: a newer reminder replaces an older one rather than stacking a
 * pile of them in the shade. Tapping opens Nudge's home screen through a `PendingIntent` (the only
 * way a component under `service/` may reach `MainActivity`, `MonitorServiceContractTest`).
 */
object UseReminderNotifier {

    private const val USE_REMINDER_CHANNEL_ID = "nudge_usage_reminders"

    /** 1 monitor, 2 protection alert, 3 bounce check-in. Pinned by SharedNamespaceUniquenessTest. */
    private const val USE_REMINDER_NOTIFICATION_ID = 4

    fun notify(context: Context, reminder: UseReminder) {
        createChannel(context)

        val notification = NotificationCompat.Builder(context, USE_REMINDER_CHANNEL_ID)
            .setContentTitle(reminder.title)
            .setContentText(reminder.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.body))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(homeIntent(context))
            .build()

        // POST_NOTIFICATIONS is a runtime grant on Android 13+, and some OEM builds throw rather than
        // drop when it is refused. This runs on the accessibility service's clock, which must never
        // die for a notification; the editor tells the user when notifications are off.
        try {
            NotificationManagerCompat.from(context).notify(USE_REMINDER_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Not granted. Nothing else to do.
        }
    }

    /**
     * True when a reminder posted now could actually reach the user: notifications allowed for
     * Nudge AND this channel not switched off. Read by the rule editors so a reminder that is on
     * but cannot arrive says so instead of staying silent.
     */
    fun canPost(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(USE_REMINDER_CHANNEL_ID)
            ?: return true // not created yet, so not blocked yet
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun homeIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            /* requestCode = */ 4,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            USE_REMINDER_CHANNEL_ID,
            context.getString(R.string.use_reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.use_reminder_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
