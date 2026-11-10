package com.astraedus.nudge.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.ui.theme.NudgeTheme

/**
 * The overlay for a [com.astraedus.nudge.domain.model.BlockMode.MIRROR] block: a DELAY whose
 * picture is the front camera. You go to open the app and you are looking at yourself, with a
 * small countdown and the usual exits over the feed.
 *
 * ONE countdown for the whole block ([rememberBlockCountdown], the same clock and the same
 * exactly-once completion as DELAY), and two pictures over it ([MirrorFallback.bodyFor]): the
 * camera, or the plain [DelayBody] when the camera cannot be shown. Falling back mid-wait swaps
 * the picture; the wait carries on from where it was.
 *
 * Privacy: CameraX `Preview` only. No ImageCapture, no ImageAnalysis, no recording; frames go from
 * the camera to the screen and nowhere else, and the app has no INTERNET permission.
 *
 * [onCameraFallback] is told why the camera was not shown (for the log), once per reason.
 */
@Composable
fun MirrorContent(
    delaySeconds: Int,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    ruleName: String? = null,
    untilText: String? = null,
    appLabel: String? = null,
    dailyTimeRemainingMs: Long? = null,
    dailyLimitMinutes: Int? = null,
    titlePool: List<String> = NudgeMessages.delayTitles,
    subtitlePool: List<String> = NudgeMessages.delaySubtitles,
    canUseEmergencyPass: Boolean = false,
    emergencyLocked: Boolean = false,
    nextPassMs: Long = 0L,
    onUseEmergencyPass: () -> Unit = {},
    onCameraFallback: (reason: String) -> Unit = {},
    redirect: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val countdown = rememberBlockCountdown(delaySeconds, onComplete)

    // Read once per block. Revoking a runtime permission kills the process, so it cannot change
    // under a live overlay.
    val permissionGranted = remember { hasCameraPermission(context) }
    val hasFrontCamera = remember { hasFrontCameraFeature(context) }
    var cameraFailed by remember { mutableStateOf(false) }

    val body = MirrorFallback.bodyFor(permissionGranted, hasFrontCamera, cameraFailed)
    val currentOnFallback by rememberUpdatedState(onCameraFallback)
    LaunchedEffect(Unit) {
        // The up-front reasons; a camera that fails later reports its own through onCameraFailed.
        if (!permissionGranted) {
            currentOnFallback("no camera permission")
        } else if (!hasFrontCamera) {
            currentOnFallback("no front camera")
        }
    }

    when (body) {
        MirrorBody.CAMERA -> MirrorCameraBody(
            countdown = countdown,
            onCameraFailed = { reason ->
                if (!cameraFailed) {
                    cameraFailed = true
                    currentOnFallback(reason)
                }
            },
            onCancel = onCancel,
            ruleName = ruleName,
            untilText = untilText,
            appLabel = appLabel,
            dailyTimeRemainingMs = dailyTimeRemainingMs,
            dailyLimitMinutes = dailyLimitMinutes,
            canUseEmergencyPass = canUseEmergencyPass,
            emergencyLocked = emergencyLocked,
            nextPassMs = nextPassMs,
            onUseEmergencyPass = onUseEmergencyPass,
            redirect = redirect
        )

        MirrorBody.DELAY -> DelayBody(
            countdown = countdown,
            onCancel = onCancel,
            ruleName = ruleName,
            untilText = untilText,
            appLabel = appLabel,
            dailyTimeRemainingMs = dailyTimeRemainingMs,
            dailyLimitMinutes = dailyLimitMinutes,
            titlePool = titlePool,
            subtitlePool = subtitlePool,
            canUseEmergencyPass = canUseEmergencyPass,
            emergencyLocked = emergencyLocked,
            nextPassMs = nextPassMs,
            onUseEmergencyPass = onUseEmergencyPass,
            redirect = redirect
        )
    }
}

/**
 * The camera picture: the feed full-bleed, a scrim top and bottom, and the countdown and exits
 * laid over it. The chrome is drawn in the dark theme whatever the system theme is, because it
 * sits on a camera feed (usually a face, often black), never on the app's light background.
 */
@Composable
private fun MirrorCameraBody(
    countdown: BlockCountdown,
    onCameraFailed: (reason: String) -> Unit,
    onCancel: () -> Unit,
    ruleName: String?,
    untilText: String?,
    appLabel: String?,
    dailyTimeRemainingMs: Long?,
    dailyLimitMinutes: Int?,
    canUseEmergencyPass: Boolean,
    emergencyLocked: Boolean,
    nextPassMs: Long,
    onUseEmergencyPass: () -> Unit,
    redirect: @Composable () -> Unit
) {
    // Opaque and full-bleed like every block body: it covers every pixel of the app behind, even
    // before the first camera frame arrives. Insets go on the content below, never on this.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FrontCameraPreview(open = !countdown.finished, onCameraFailed = onCameraFailed)

            NudgeTheme(darkTheme = true) {
                // Legibility scrims: the text sits on these, not on the raw feed.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.25f)
                        .background(Brush.verticalGradient(listOf(SCRIM, Color.Transparent)))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, SCRIM)))
                )

                // Centred while it fits, scrollable only when it does not: see overlayContentScroll.
                // The inner column is at least the screen's height so the countdown can sit at the
                // top and the exits at the bottom, leaving the face in the middle uncovered.
                val scroll = rememberScrollState()
                BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    val screenHeight = maxHeight
                    Column(modifier = Modifier.fillMaxSize().overlayContentScroll(scroll)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = screenHeight)
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (appLabel != null) {
                                    Text(
                                        text = appLabel,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White
                                    )
                                    if (dailyTimeRemainingMs != null &&
                                        dailyLimitMinutes != null && dailyLimitMinutes > 0
                                    ) {
                                        Text(
                                            text = "${formatDuration(dailyTimeRemainingMs)} left today",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = timeRemainingColor(dailyTimeRemainingMs, dailyLimitMinutes)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                // The countdown: small, but always there, camera or no camera.
                                Text(
                                    text = "${countdown.remainingSeconds}",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Still want it?",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(16.dp))

                                LeaveButton(onClick = onCancel)

                                Spacer(Modifier.height(16.dp))

                                // The redirect app: one tap leaves for somewhere better, through the
                                // same walk-away.
                                redirect()

                                EmergencyPassAction(
                                    canUse = canUseEmergencyPass,
                                    locked = emergencyLocked,
                                    nextPassMs = nextPassMs,
                                    onUse = onUseEmergencyPass
                                )

                                RuleFooter(ruleName = ruleName, until = untilText)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val SCRIM = Color.Black.copy(alpha = 0.6f)
