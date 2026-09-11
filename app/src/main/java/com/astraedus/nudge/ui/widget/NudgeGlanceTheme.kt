package com.astraedus.nudge.ui.widget

import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders
import com.astraedus.nudge.ui.theme.DarkColors
import com.astraedus.nudge.ui.theme.LightColors

/**
 * The widgets' colours, built from the app's OWN two Material 3 schemes.
 *
 * Not a third palette. `LightColors` / `DarkColors` in `ui/theme/Theme.kt` are `internal` precisely
 * so this file can read them: a widget that defined its own teal would drift from the app's the
 * first time either was touched, and the drift would only ever be visible on a home screen nobody
 * is looking at in a code review.
 *
 * The app also uses Material You dynamic colour on API 31+, which the widget deliberately does NOT:
 * a `ColorProviders` is resolved per-configuration by Glance, not per-composition, so the honest
 * thing is one fixed pair that follows the system light/dark setting. The launcher's own wallpaper
 * scrim is what the user is reading these against, and a stable brand teal survives that better
 * than a colour extracted from the same wallpaper.
 */
val NudgeGlanceColors: ColorProviders = ColorProviders(light = LightColors, dark = DarkColors)

/** One wrapper so no widget can forget the theme and inherit the launcher's defaults. */
@Composable
fun NudgeGlanceTheme(content: @Composable () -> Unit) {
    GlanceTheme(colors = NudgeGlanceColors, content = content)
}
