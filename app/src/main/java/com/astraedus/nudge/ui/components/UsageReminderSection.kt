package com.astraedus.nudge.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.astraedus.nudge.domain.autokick.UseReminderEvaluator
import com.astraedus.nudge.service.UseReminderNotifier

/**
 * The editor half of the usage reminder ([#77](https://github.com/astraedus/nudge/issues/77)), shared
 * by BOTH rule editors (the per-app config screen and the legacy rule editor) so the save contract,
 * the copy and the kick-precedence rule exist once.
 *
 * Pure: the composable below only renders what these return, and `UsageReminderInputTest` pins them.
 */
internal object UsageReminderInput {

    /** What an empty field fills with when the switch goes on, so the switch is never a no-op. */
    const val DEFAULT_MINUTES_TEXT = "20"

    const val TITLE = "Usage reminder"
    const val FIELD_LABEL = "Remind me after"
    const val FIELD_SUPPORTING = "Counts time in the app in one sitting. One heads-up, nothing closes."
    const val BLOCKED_WARNING =
        "Notifications are off for Nudge, so this reminder can't reach you. Tap to turn them on."

    /** The field text after the switch is flipped: an empty field gets a sensible starting value. */
    fun textAfterToggle(enabled: Boolean, currentText: String): String =
        if (enabled && DurationInput.parseMinutes(currentText) == null) DEFAULT_MINUTES_TEXT else currentText

    /**
     * The value to persist in `BlockRule.remindAfterMinutes`. Off (or a blank/zero field) is null.
     * An untouched field re-saves the exact loaded value (see [DurationInput.resolveMinutes]).
     */
    fun resolve(enabled: Boolean, text: String, originalMinutes: Int?): Int? =
        if (enabled) DurationInput.resolveMinutes(text, originalMinutes) else null

    /**
     * Explains, under the field, that the kick will happen first and this reminder will never show.
     * Asks the SAME predicate the service fires on ([UseReminderEvaluator.effectiveMinutes]), so the
     * editor can never promise a reminder the engine will not send, or hide one it will.
     *
     * @param kickMinutes the time-based auto-kick this rule will save with, or null if none.
     */
    fun supersededHint(remindMinutes: Int?, kickMinutes: Int?): String? {
        if (remindMinutes == null || remindMinutes <= 0) return null
        if (UseReminderEvaluator.effectiveMinutes(remindMinutes, kickMinutes) != null) return null
        val kick = kickMinutes ?: return null
        return "Auto-kick at $kick min comes first, so this reminder won't show. " +
            "Set it below $kick min to get both."
    }

    /** The help text. The reminder is app-only, which matters when the rule also blocks websites. */
    fun info(websitesConfigured: Boolean): String =
        "Get one gentle notification once you've spent this long in the app in one sitting. " +
            "It doesn't close anything, it just lets you know.\n\n" +
            "It resets the same way auto-kick does: a short trip to another app keeps the sitting " +
            "going, a real break (5 minutes or more) starts a new one. If auto-kick by time is set " +
            "for the same or a shorter time, auto-kick wins and the reminder is skipped." +
            if (websitesConfigured) "\n\nApps only: it does not cover the websites below." else ""
}

/**
 * Title row + switch + minutes field for the usage reminder, in the same idiom as the auto-kick
 * block it sits next to.
 *
 * Turning it ON on Android 13+ without the notification grant asks for it right there (the moment
 * the user said they want a notification, exactly as the "Bro. wtf." row does). While it is on and
 * notifications cannot be posted, it says so in the error colour and a tap opens Nudge's
 * notification settings; the state is re-read on resume, since that is where the user comes back
 * from.
 *
 * @param info the screen's own help button, so this section matches its neighbours exactly.
 */
@Composable
internal fun UsageReminderSection(
    enabled: Boolean,
    minutesText: String,
    kickMinutes: Int?,
    websitesConfigured: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onMinutesTextChange: (String) -> Unit,
    info: @Composable (String) -> Unit
) {
    val context = LocalContext.current
    var canPost by remember { mutableStateOf(UseReminderNotifier.canPost(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canPost = UseReminderNotifier.canPost(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { canPost = UseReminderNotifier.canPost(context) }

    val onToggle: (Boolean) -> Unit = { enable ->
        onEnabledChange(enable)
        if (enable && NotificationAccess.needsPermission(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    UsageReminderInput.TITLE,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                info(UsageReminderInput.info(websitesConfigured))
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        if (enabled) {
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MinutesField(
                    value = minutesText,
                    onValueChange = onMinutesTextChange,
                    labelText = UsageReminderInput.FIELD_LABEL,
                    supportingText = UsageReminderInput.FIELD_SUPPORTING
                )
                UsageReminderInput.supersededHint(
                    remindMinutes = DurationInput.parseMinutes(minutesText),
                    kickMinutes = kickMinutes
                )?.let { hint ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (!canPost) {
                    Text(
                        UsageReminderInput.BLOCKED_WARNING,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { NotificationAccess.openSettings(context) }
                    )
                }
            }
        }
    }
}
