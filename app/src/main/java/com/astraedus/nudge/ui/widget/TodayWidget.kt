package com.astraedus.nudge.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import com.astraedus.nudge.R

/**
 * "Today at a glance" — screen time, blocks, walk-aways.
 *
 * The three numbers the app is about, which is why this is the widget that exists rather than
 * something cleverer: everything it shows is already on the dashboard, and the dashboard is two
 * taps away on a phone whose whole problem is that opening it is two taps too many.
 *
 * Responsive at two sizes. 2x2 leads with screen time and puts the two counts on one line under it;
 * 4x2 has room for three labelled columns. Both come from ONE read — [WidgetReads.today] — so the
 * two variants cannot disagree, which is the failure mode a per-size branch invites.
 *
 * Tapping anywhere opens Usage Stats, the screen these numbers came from.
 */
class TodayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp), // 2x2
            DpSize(250.dp, 110.dp)  // 4x2
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // ONE suspending read, before composition. See WidgetReads for why a Flow subscription
        // would be a lie here. `runCatching` because a widget must never crash its host: a thrown
        // exception in a launcher's process is the user's home screen misbehaving, not ours.
        val snapshot = runCatching { WidgetReads.today(NudgeWidgetEntryPoint.from(context)) }
            .getOrElse { WidgetSnapshot.Today.EMPTY }

        provideContent {
            NudgeGlanceTheme { TodayContent(snapshot) }
        }
    }
}

@Composable
private fun TodayContent(snapshot: WidgetSnapshot.Today) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= WIDE_THRESHOLD

    Column(
        modifier = widgetSurface()
            .clickable(openAppAt(WidgetDeepLink.ROUTE_STATS))
            // The whole surface is one tap target, so TalkBack has to be told what the tap does.
            // Without this a screen reader announces three loose numbers and no action.
            .semantics { contentDescription = context.getString(R.string.widget_open_app) },
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.Start
    ) {
        if (wide) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Metric(
                    value = snapshot.screenTime,
                    label = context.getString(R.string.widget_today_screen_time),
                    modifier = GlanceModifier.defaultWeight()
                )
                Metric(
                    value = snapshot.blocked.toString(),
                    label = context.getString(R.string.widget_today_blocked),
                    modifier = GlanceModifier.defaultWeight()
                )
                Metric(
                    value = snapshot.walkedAway.toString(),
                    label = context.getString(R.string.widget_today_walked_away),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
        } else {
            Text(
                text = context.getString(R.string.widget_today_title),
                style = labelStyle()
            )
            Text(text = snapshot.screenTime, style = heroStyle(), maxLines = 1)
            Spacer(modifier = GlanceModifier.height(6.dp))
            // One line rather than two stacked metrics: at 2x2 there is room for one more row of
            // text, and two numbers with their own labels would each be too small to read.
            Text(
                text = context.getString(
                    R.string.widget_today_compact_counts,
                    snapshot.blocked,
                    snapshot.walkedAway
                ),
                style = bodyStyle(GlanceTheme.colors.primary),
                maxLines = 2
            )
        }

        // Only shown when it is TRUE, and it explains the placeholder above rather than being an
        // alarm: without Usage Access there is no screen time to read, and a widget that printed
        // "0s" would be asserting a measurement it never took.
        if (!snapshot.hasUsagePermission) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.widget_today_no_usage_access),
                style = labelStyle(GlanceTheme.colors.error),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: GlanceModifier = GlanceModifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Horizontal.Start
    ) {
        Text(text = value, style = valueStyle(), maxLines = 1)
        Text(text = label, style = labelStyle(), maxLines = 1)
    }
}

/** Above this the widget has room for three labelled columns; below it, it does not. */
private val WIDE_THRESHOLD = 180.dp

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
