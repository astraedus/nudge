package com.astraedus.nudge.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.astraedus.nudge.R
import kotlinx.coroutines.flow.first

/**
 * Protection: is blocking on, and turn it on or off.
 *
 * The only widget that ACTS rather than reports, and therefore the only one with a security
 * contract. It also surfaces the app's worst silent failure — the OS having killed the
 * accessibility service, so Nudge looks enabled and blocks nothing — on a surface the user sees
 * without opening anything.
 *
 * ## Strict Mode (`docs/architecture/strict-mode.md`)
 *
 * Turning protection ON is free. Turning it OFF while the commitment lock is on must go through the
 * app's typed challenge, exactly as `HomeViewModel.toggleGlobalEnabled` does. A widget that wrote
 * `setGlobalEnabled(false)` itself would be a ONE-TAP BYPASS of that lock, sitting on the home
 * screen, reachable without unlocking anything — the precise hole Strict Mode exists to close.
 *
 * So the locked case is a DIFFERENT COMPOSABLE, chosen at compose time from the snapshot, whose tap
 * target is `actionStartActivity` into the app rather than a callback. That is not a style choice:
 * starting an Activity from inside an `ActionCallback` is subject to the API 31+ background-activity
 * -start restriction and would simply do nothing on a modern phone, which would read to the user as
 * a dead widget rather than as a locked one. [WidgetSnapshot.Protection.togglesInWidget] is the one
 * place the branch is decided, and it is unit-tested over all eight input combinations.
 */
class ProtectionWidget : GlanceAppWidget() {

    // Single size: this widget is a state light and a switch. There is no second layout worth
    // maintaining for something whose whole content is a dot and four words.
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // On failure, WidgetSnapshot.Protection.EMPTY reads as "on, and locked" — a failed read
        // must never render an inviting one-tap OFF affordance.
        val snapshot = runCatching { WidgetReads.protection(NudgeWidgetEntryPoint.from(context)) }
            .getOrElse { WidgetSnapshot.Protection.EMPTY }

        provideContent {
            NudgeGlanceTheme { ProtectionContent(snapshot) }
        }
    }
}

@Composable
private fun ProtectionContent(snapshot: WidgetSnapshot.Protection) {
    if (snapshot.togglesInWidget) {
        ProtectionRow(
            snapshot = snapshot,
            // Free to write: either turning protection ON (never gated), or turning it off with no
            // commitment lock in force.
            modifier = widgetSurface(PADDING)
                .clickable(actionRunCallback<ToggleProtectionAction>())
        )
    } else {
        ProtectionRow(
            snapshot = snapshot,
            // Locked. Opens the app at Home, where the master toggle lives and where the real
            // typed challenge will be raised. Nothing is written from here.
            modifier = widgetSurface(PADDING)
                .clickable(openAppAt(WidgetDeepLink.ROUTE_HOME)),
            lockedHint = true
        )
    }
}

@Composable
private fun ProtectionRow(
    snapshot: WidgetSnapshot.Protection,
    modifier: GlanceModifier,
    lockedHint: Boolean = false
) {
    val context = LocalContext.current
    val dotColor: ColorProvider = when (snapshot.state) {
        ProtectionState.ON -> GlanceTheme.colors.primary
        ProtectionState.OFF -> GlanceTheme.colors.onSurfaceVariant
        ProtectionState.DEGRADED -> GlanceTheme.colors.error
    }
    val statusText = when (snapshot.state) {
        ProtectionState.ON -> context.getString(R.string.widget_protection_on)
        ProtectionState.OFF -> context.getString(R.string.widget_protection_off)
        ProtectionState.DEGRADED -> context.getString(R.string.widget_protection_degraded)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        // A filled dot, drawn as a rounded Spacer. Not an icon and never an emoji: this has to be
        // legible at 2x1 over an arbitrary wallpaper, and colour is doing the work.
        Spacer(
            modifier = GlanceModifier
                .size(DOT_SIZE)
                .cornerRadius(DOT_SIZE / 2)
                .background(dotColor)
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = statusText,
                style = titleStyle(
                    if (snapshot.state == ProtectionState.DEGRADED) {
                        GlanceTheme.colors.error
                    } else {
                        GlanceTheme.colors.onSurface
                    }
                ),
                maxLines = 1
            )
            if (lockedHint) {
                // Says what WILL happen, so a tap that opens the app does not read as the widget
                // failing to toggle.
                Text(
                    text = context.getString(R.string.widget_protection_locked),
                    style = labelStyle(),
                    maxLines = 1
                )
            }
        }
    }
}

private val PADDING = 10.dp
private val DOT_SIZE = 10.dp

/**
 * The ONLY place a widget writes the master toggle, and it never weakens protection behind Strict
 * Mode's back.
 *
 * Re-reads the current state rather than trusting what the widget was rendering: the snapshot that
 * produced the tapped view can be minutes old, and "what it looked like when I drew it" is not a
 * safe basis for writing a protection setting.
 *
 * There is deliberately NO `startActivity` here. Starting an Activity from a broadcast-driven
 * callback is blocked on API 31+, so the locked branch must be an `actionStartActivity` chosen at
 * compose time — see [ProtectionContent]. `WidgetStrictModeContractTest` pins both halves.
 */
class ToggleProtectionAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val preferences = NudgeWidgetEntryPoint.from(context).nudgePreferences()
        val enabled = preferences.isGlobalEnabled.first()
        val strictModeEnabled = preferences.isStrictModeEnabled.first()

        if (enabled) {
            // Weakening. Gated on Strict Mode being off, on the SAME line as the write so the
            // guard cannot be separated from what it guards by a later refactor. When Strict Mode
            // is on this is a no-op and the user never reaches it: the composable that routed here
            // is only chosen when togglesInWidget is true.
            if (!strictModeEnabled) preferences.setGlobalEnabled(false)
        } else {
            // Strengthening is never gated, on any surface.
            preferences.setGlobalEnabled(true)
        }

        ProtectionWidget().updateAll(context)
    }
}

class ProtectionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProtectionWidget()
}
