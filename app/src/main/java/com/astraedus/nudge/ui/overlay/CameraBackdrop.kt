package com.astraedus.nudge.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.astraedus.nudge.ui.theme.NudgeTheme

/**
 * True while the block screen is drawn over the camera. The screens read it through
 * [overlaySurfaceColor] only: their full-bleed Surface goes clear so the camera shows through.
 */
internal val LocalCameraBackdrop = staticCompositionLocalOf { false }

/**
 * The colour of a block screen's own full-bleed Surface: the theme background, as it always was,
 * or clear when [CameraBackdrop] is drawing the camera under it. The screen is still opaque either
 * way, because the backdrop itself is an opaque black Surface under the camera.
 */
@Composable
internal fun overlaySurfaceColor(): Color =
    if (LocalCameraBackdrop.current) Color.Transparent else MaterialTheme.colorScheme.background

/**
 * The "camera on block screens" setting: draws [content] (any block screen) over the mirrored
 * front camera when [BlockBackdrop.backdropFor] says so, and exactly as it is otherwise.
 *
 * [settingOn] and [screen] are fixed for the delivery; when [BlockBackdrop.wantsCamera] is false
 * (the setting is off, the screen is MIRROR, or there is no screen) this is a
 * plain pass-through and nothing about the screen changes. When true the composition shape stays
 * the same whatever the camera does, so a camera failing mid-wait swaps the background and the
 * colours and nothing else: every `remember` in [content] (the countdown, the hold, the breathing
 * clock) carries on from where it was.
 *
 * The camera layer: an opaque black Surface (it covers the app behind before the first frame),
 * the preview, and a uniform [CameraBackdropPalette.SCRIM]. The content above it is drawn with
 * [cameraColorScheme], whose text colours pass WCAG AA over that scrim on a white frame and a black
 * one (`CameraBackdropContrastTest`).
 *
 * [onCameraFallback] is told why the camera was not shown (for the log), once per reason.
 */
@Composable
internal fun CameraBackdrop(
    settingOn: Boolean,
    screen: OverlayScreen?,
    onCameraFallback: (reason: String) -> Unit = {},
    content: @Composable () -> Unit
) {
    if (screen == null || !BlockBackdrop.wantsCamera(settingOn, screen)) {
        content()
        return
    }
    val context = LocalContext.current
    val permissionGranted = remember { hasCameraPermission(context) }
    val hasFrontCamera = remember { hasFrontCameraFeature(context) }
    var cameraFailed by remember { mutableStateOf(false) }
    val currentOnFallback by rememberUpdatedState(onCameraFallback)
    LaunchedEffect(Unit) {
        // The up-front reasons; a camera that fails later reports its own through onCameraFailed.
        if (!permissionGranted) {
            currentOnFallback("no camera permission")
        } else if (!hasFrontCamera) {
            currentOnFallback("no front camera")
        }
    }

    val backdrop = BlockBackdrop.backdropFor(
        settingOn = settingOn,
        screen = screen,
        permissionGranted = permissionGranted,
        hasFrontCamera = hasFrontCamera,
        cameraFailed = cameraFailed
    )
    val onCamera = backdrop == Backdrop.CAMERA

    Box(modifier = Modifier.fillMaxSize()) {
        if (onCamera) {
            // Opaque and full-bleed like every block body; insets belong to the content above.
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                FrontCameraPreview(
                    open = true,
                    onCameraFailed = { reason ->
                        if (!cameraFailed) {
                            cameraFailed = true
                            currentOnFallback(reason)
                        }
                    }
                )
                Box(modifier = Modifier.fillMaxSize().background(CameraBackdropPalette.SCRIM))
            }
        }

        // Always this one call, camera or not, so the content keeps its place in the composition.
        // Over the camera the chrome is the dark theme with the camera palette, whatever the system
        // theme: it sits on a scrimmed feed, never on the app's light background.
        NudgeTheme(darkTheme = onCamera || isSystemInDarkTheme()) {
            val scheme = if (onCamera) cameraColorScheme(MaterialTheme.colorScheme) else MaterialTheme.colorScheme
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(
                    LocalCameraBackdrop provides onCamera,
                    LocalContentColor provides if (onCamera) Color.White else LocalContentColor.current
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * The fixed colours a block screen uses over the camera. Fixed rather than the device's dynamic
 * palette, because legibility over an unknown, moving picture has to be checked against numbers:
 * every TEXT colour here is at least 4.5:1 against the scrim over a pure white frame (the worst
 * case) and over a black one. `CameraBackdropContrastTest` holds that.
 */
internal object CameraBackdropPalette {
    /** Uniform, not a gradient: every block screen centres its text, so all of it needs the help. */
    val SCRIM = Color.Black.copy(alpha = 0.65f)

    val TEXT = Color.White
    val TEXT_MUTED = Color(0xFFEEEEEE)
    val TEXT_FAINT = Color(0xFFD9D9D9)
    val PRIMARY = Color(0xFFB8F0E0)
    val ON_PRIMARY = Color(0xFF00382D)
    val ERROR = Color(0xFFFFDAD6)
    val TERTIARY = Color(0xFFFFE08A)
}

/**
 * [base] (the dark scheme) with every colour that is drawn straight onto the backdrop swapped for
 * the [CameraBackdropPalette]. Containers (the hold face, the redirect bubble, the buttons) keep
 * the dark scheme's own fills, so text inside them is judged against those, as it always was.
 */
internal fun cameraColorScheme(base: ColorScheme): ColorScheme = base.copy(
    onBackground = CameraBackdropPalette.TEXT,
    onSurface = CameraBackdropPalette.TEXT,
    onSurfaceVariant = CameraBackdropPalette.TEXT_MUTED,
    outline = CameraBackdropPalette.TEXT_FAINT,
    primary = CameraBackdropPalette.PRIMARY,
    onPrimary = CameraBackdropPalette.ON_PRIMARY,
    error = CameraBackdropPalette.ERROR,
    tertiary = CameraBackdropPalette.TERTIARY
)
