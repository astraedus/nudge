package com.astraedus.nudge.ui.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.astraedus.nudge.domain.hold.HoldProgress

/**
 * The press-and-hold that opens a blocked app once its timer has run out
 * ([issue #35](https://github.com/astraedus/nudge/issues/35)).
 *
 * Before this existed, a delay reaching zero opened the app on its own — so the entire cost of
 * entering was *waiting*, and waiting is something a thumb can spend while the person is somewhere
 * else. The hold moves the last step back into the user's hands: it takes a few seconds of
 * continuous, deliberate contact, and **letting go is the cheap action**. Which is the direction
 * every affordance in this app should point.
 *
 * Rendered in place of the countdown ring, so the same circle the user has been watching drain now
 * fills under their thumb. One shape, two phases, nothing new to find on the screen.
 *
 * ## What lives where
 *
 * The RULES of the gesture — a release resets it, a completion fires exactly once, time spent
 * off-screen does not count — are [HoldProgress], pure and unit-tested, because none of them can be
 * observed on a device without a finger and a stopwatch. This composable owns only the rendering and
 * the two ways a hold can be started.
 *
 * ## The two ways to hold, and why TalkBack needs its own
 *
 * A press-and-hold is invisible to a screen reader: TalkBack consumes touch exploration, so
 * [detectTapGestures] never sees a finger and a user on TalkBack would be looking at a control they
 * physically cannot operate — locked out of their own phone by an accessibility feature. So the
 * control also publishes a semantic click action ([onClick]) that starts the SAME hold on the SAME
 * machine for the SAME duration; the friction is the delay, not the finger, and it is preserved
 * either way. Both paths converge on one [HoldProgress] and one [onUnlock].
 *
 * @param holdDurationMs how long the hold must be sustained. Callers must only render this when
 *   [com.astraedus.nudge.domain.hold.HoldToUnlock.isEnabled] — "Off" means the timer completing
 *   opens the app directly and this control never appears.
 * @param onUnlock invoked exactly once, when the hold completes. This is the block overlay's
 *   existing completion callback, the one that grants passthrough: the hold gates that path, it does
 *   not add a second one.
 */
@Composable
fun HoldToUnlockControl(
    holdDurationMs: Long,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Keyed on the duration so a setting change (or, later, a per-rule override) cannot leave a
    // machine measuring against the old length.
    val progress = remember(holdDurationMs) { HoldProgress(holdDurationMs) }

    // Whether a finger is down, and whether TalkBack asked for a hold. Either one holds.
    var fingerDown by remember(holdDurationMs) { mutableStateOf(false) }
    var accessibilityHold by remember(holdDurationMs) { mutableStateOf(false) }
    val holding = fingerDown || accessibilityHold

    // What the ring draws. Written once per frame by the loop below rather than derived from a
    // clock during composition, so the fill cannot advance while nothing is driving it.
    var fraction by remember(holdDurationMs) { mutableFloatStateOf(0f) }

    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The effect below is keyed on `holding`, not on this lambda, so a recomposition that hands us a
    // fresh lambda instance must not leave the effect calling a stale one.
    val currentOnUnlock by rememberUpdatedState(onUnlock)

    LaunchedEffect(holding, holdDurationMs, lifecycleOwner) {
        if (!holding) {
            progress.release()
            fraction = if (progress.isCompleted) 1f else 0f
            return@LaunchedEffect
        }
        try {
            // RESUMED-gated for the same reason the countdown above it is (issue #8): an overlay
            // that is not on screen must not be able to open an app. `press` restarts from the
            // moment we come back, so wall-clock time spent away never counts toward entry.
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                progress.press(System.currentTimeMillis())
                while (true) {
                    withFrameMillis { }
                    val now = System.currentTimeMillis()
                    fraction = progress.fraction(now)
                    if (progress.advance(now)) {
                        // One tick at the moment it lands, so the user knows the hold is done
                        // without having to keep watching the ring.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnUnlock()
                        break
                    }
                }
            }
        } finally {
            // Cancelled mid-hold (the finger lifted, or this composable left): abandon the attempt.
            if (!progress.isCompleted) {
                progress.release()
                fraction = 0f
            }
        }
    }

    // Snaps up under the thumb, eases back down on release — so letting go READS as undoing
    // something, rather than as the screen glitching.
    val drawnFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(
            durationMillis = if (holding) 0 else 220,
            easing = FastOutSlowInEasing
        ),
        label = "hold_fill"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (holding) 0.96f else 1f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "hold_press"
    )
    val faceColor by animateColorAsState(
        targetValue = if (holding) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(durationMillis = 160),
        label = "hold_face"
    )
    val faceContentColor = if (holding) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val seconds = (holdDurationMs / 1000L).coerceAtLeast(1L)
    val holdLabel = "Hold to open"

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(180.dp)
                .scale(pressScale)
                // Merged into one node so a screen reader announces one control, not a ring, an
                // icon and a word.
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription =
                        "$holdLabel. Press and hold for $seconds seconds. Let go to stay out."
                    onClick(label = "Open after $seconds seconds") {
                        accessibilityHold = true
                        true
                    }
                }
                .pointerInput(holdDurationMs) {
                    detectTapGestures(
                        onPress = {
                            fingerDown = true
                            // Suspends until the finger lifts OR the gesture is cancelled (a
                            // scroll steals it, the window goes away). Both mean "not holding
                            // any more", and both must put the ring back to zero.
                            tryAwaitRelease()
                            fingerDown = false
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .size(152.dp)
                    .clip(CircleShape)
                    .background(faceColor)
            )

            CircularProgressIndicator(
                progress = { drawnFraction },
                modifier = Modifier.size(180.dp),
                strokeWidth = 8.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.TouchApp,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = faceContentColor
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "HOLD",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp
                    ),
                    color = faceContentColor
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (holding) "Keep holding…" else "Press and hold for $seconds seconds",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
