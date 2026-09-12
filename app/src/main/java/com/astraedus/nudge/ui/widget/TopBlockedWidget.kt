package com.astraedus.nudge.ui.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import com.astraedus.nudge.R

/**
 * "Blocked most" — the week's leaderboard of which apps pulled hardest.
 *
 * Reads through `InsightsCalculator.topBlockedApps`, the ONE per-app aggregation in the app, over
 * the same trailing-week boundary the dashboard's own week card subscribes with. A widget with its
 * own loop would be a second answer to a question that already has one, which is the defect this
 * package has shipped twice.
 *
 * Header opens Temptation patterns; a row opens that app's detail screen.
 */
class TopBlockedWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(250.dp, 110.dp), // 4x2
            DpSize(250.dp, 180.dp)  // 4x3
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // The store is a CACHE, not the source of truth: seed it when this is a cold session (a
        // fresh process, or the launcher adding the widget) so the first frame has real data.
        val deps = NudgeWidgetEntryPoint.from(context)
        val store = deps.widgetSnapshotStore()
        if (store.topBlocked == null) {
            store.publishTopBlocked(
                runCatching { WidgetReads.topBlocked(deps, MAX_ROWS) }
                    .getOrElse { WidgetReads.TopBlockedRead.EMPTY }
            )
        }

        provideContent {
            // READ INSIDE THE COMPOSITION - see WidgetSnapshotStore. A value captured before
            // provideContent is frozen for the whole Glance session.
            val read = store.topBlocked ?: WidgetReads.TopBlockedRead.EMPTY
            NudgeGlanceTheme { TopBlockedContent(read) }
        }
    }

    internal companion object {
        /** Rows the tall variant shows. The short variant takes the first [SHORT_ROWS] of these. */
        const val MAX_ROWS = 5
    }
}

/** Rows that fit the 4x2 variant under the header. */
private const val SHORT_ROWS = 3

/** Above this height the widget is the 4x3 variant. */
private val TALL_THRESHOLD = 150.dp

@Composable
private fun TopBlockedContent(read: WidgetReads.TopBlockedRead) {
    val context = LocalContext.current
    val size = LocalSize.current
    val rows = if (size.height >= TALL_THRESHOLD) read.snapshot.apps else read.snapshot.apps.take(SHORT_ROWS)

    // The bar track is what is left after the icon, the gap, and the fixed count column. Computed
    // from the size Glance actually laid us out at, because Glance has no fractional-width
    // modifier: a proportional bar has to be an absolute Dp here.
    val barTrack = (size.width - WIDGET_PADDING * 2 - ICON_SIZE - ROW_GAP * 2 - COUNT_COLUMN)
        .coerceAtLeast(MIN_BAR_TRACK)

    Column(modifier = widgetSurface(WIDGET_PADDING)) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(openAppAt(WidgetDeepLink.ROUTE_INTERVENTIONS))
                // "Blocked most / Last 7 days" describes the list, not what tapping the header
                // does. TalkBack needs the action named.
                .semantics {
                    contentDescription = context.getString(R.string.widget_top_blocked_open)
                },
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = context.getString(R.string.widget_top_blocked_title),
                style = titleStyle(),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1
            )
            Text(
                text = context.getString(R.string.widget_top_blocked_range),
                style = labelStyle(),
                maxLines = 1
            )
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        if (rows.isEmpty()) {
            // Rendered, not hidden. A card that vanishes when there is nothing to say teaches the
            // user nothing about what it would say when there is.
            Text(
                text = context.getString(R.string.widget_top_blocked_empty),
                style = labelStyle(),
                maxLines = 3
            )
        } else {
            rows.forEachIndexed { index, app ->
                BlockedRow(
                    app = app,
                    rank = index + 1,
                    icon = read.icons[app.packageName],
                    barTrack = barTrack
                )
            }
        }
    }
}

@Composable
private fun BlockedRow(
    app: WidgetSnapshot.BlockedApp,
    rank: Int,
    icon: Bitmap?,
    barTrack: Dp
) {
    val context = LocalContext.current
    // The row is the tap target for that app's detail screen. A package the deep link refuses to
    // route (see WidgetDeepLink) falls back to opening the app at Home rather than to a route
    // nobody can resolve.
    val route = WidgetDeepLink.appDetailRoute(app.packageName) ?: WidgetDeepLink.ROUTE_HOME

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(openAppAt(route)),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        if (icon != null) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = app.label,
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.size(ICON_SIZE)
            )
        } else {
            // A rank, never an emoji, and never a blank gap that would knock the bars out of line.
            Text(
                text = context.getString(R.string.widget_top_blocked_rank, rank),
                style = labelStyle(),
                modifier = GlanceModifier.size(ICON_SIZE),
                maxLines = 1
            )
        }

        Spacer(modifier = GlanceModifier.width(ROW_GAP))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(text = app.label, style = bodyStyle(), maxLines = 1)
            Spacer(modifier = GlanceModifier.height(2.dp))
            // Track then fill, both absolute: the fill is barPercent of the track computed above.
            ProportionBar(
                trackWidth = barTrack,
                fillWidth = barTrack * (app.barPercent / 100f)
            )
        }

        Spacer(modifier = GlanceModifier.width(ROW_GAP))

        Text(
            text = app.count.toString(),
            style = valueStyle(),
            modifier = GlanceModifier.width(COUNT_COLUMN),
            maxLines = 1
        )
    }
}

/**
 * The proportional bar.
 *
 * Two stacked `Row`s rather than a Glance `Box`, because the fill has to sit at the START of the
 * track and Glance's `Box` alignment plus a fixed width is the same thing with more moving parts.
 * The track is always drawn, so a one-block app still reads as "one out of that many".
 */
@Composable
private fun ProportionBar(trackWidth: Dp, fillWidth: Dp) {
    Row(
        modifier = GlanceModifier
            .width(trackWidth)
            .height(BAR_HEIGHT)
            .cornerRadius(BAR_HEIGHT / 2)
            .background(GlanceTheme.colors.surfaceVariant)
    ) {
        Spacer(
            modifier = GlanceModifier
                .width(fillWidth.coerceAtLeast(BAR_HEIGHT))
                .height(BAR_HEIGHT)
                .cornerRadius(BAR_HEIGHT / 2)
                .background(GlanceTheme.colors.primary)
        )
    }
}

private val WIDGET_PADDING = 12.dp
private val ICON_SIZE = 20.dp
private val ROW_GAP = 8.dp
private val COUNT_COLUMN = 28.dp
private val MIN_BAR_TRACK = 24.dp
private val BAR_HEIGHT = 6.dp

class TopBlockedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TopBlockedWidget()
}
