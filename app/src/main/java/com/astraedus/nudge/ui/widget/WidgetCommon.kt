package com.astraedus.nudge.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.astraedus.nudge.MainActivity

/**
 * Shared shell for the three widgets: one surface, one type scale, one way to open the app.
 *
 * The point of the file is that no widget re-derives any of them. Three copies of "how a Nudge
 * widget is painted" would drift, and three copies of "how a widget opens the app" would be three
 * chances for one of them to hand `navigate()` a route that does not exist.
 */

/**
 * The action-parameter key that carries a deep-link route.
 *
 * Glance copies each parameter into the launched Intent's extras using the key's NAME verbatim
 * (`ApplyAction` -> `bundleOf` -> `Intent.putExtras`), so this key and the extra `MainActivity`
 * reads are the same constant rather than two constants that have to agree.
 */
internal val WidgetRouteKey = ActionParameters.Key<String>(WidgetDeepLink.EXTRA_ROUTE)

/**
 * Opens the app at [route].
 *
 * Glance builds the `PendingIntent` itself, with the mutability flags API 31+ demands and a unique
 * data URI per clickable view — which is why several rows of one widget can carry different routes
 * without collapsing onto one cached PendingIntent. Hand-building one here would forfeit both.
 */
internal fun openAppAt(route: String): Action =
    actionStartActivity<MainActivity>(actionParametersOf(WidgetRouteKey to route))

/**
 * The root modifier every widget uses.
 *
 * `appWidgetBackground()` is what lets the launcher apply its own rounded-corner treatment on
 * API 31+; `cornerRadius` is a no-op below that, an accepted cosmetic difference on the older
 * devices this app still supports (minSdk 26).
 */
@Composable
internal fun widgetSurface(padding: Dp = 12.dp): GlanceModifier = GlanceModifier
    .fillMaxSize()
    .appWidgetBackground()
    .background(GlanceTheme.colors.surface)
    .cornerRadius(16.dp)
    .padding(padding)

/** The hero number on a widget — the one thing readable at arm's length. */
@Composable
internal fun heroStyle(color: ColorProvider = GlanceTheme.colors.onSurface) =
    TextStyle(color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)

/** A secondary number: still bold, still countable at a glance, not the hero. */
@Composable
internal fun valueStyle(color: ColorProvider = GlanceTheme.colors.primary) =
    TextStyle(color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)

/** What a number means. Deliberately quiet; the number carries the message. */
@Composable
internal fun labelStyle(color: ColorProvider = GlanceTheme.colors.onSurfaceVariant) =
    TextStyle(color = color, fontSize = 11.sp)

@Composable
internal fun bodyStyle(color: ColorProvider = GlanceTheme.colors.onSurface) =
    TextStyle(color = color, fontSize = 13.sp)

@Composable
internal fun titleStyle(color: ColorProvider = GlanceTheme.colors.onSurface) =
    TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
