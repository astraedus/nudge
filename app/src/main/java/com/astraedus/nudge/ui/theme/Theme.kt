package com.astraedus.nudge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F2DC),
    secondary = Color(0xFF4B635B),
    onSecondary = Color.White,
    background = Color(0xFFFBFDF9),
    surface = Color(0xFFFBFDF9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD6C1),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005143),
    secondary = Color(0xFFB3CCC3),
    onSecondary = Color(0xFF1F352D),
    background = Color(0xFF191C1B),
    surface = Color(0xFF191C1B),
)

@Composable
fun NudgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    @OptIn(ExperimentalMaterial3Api::class)
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
