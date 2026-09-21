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
import androidx.compose.material3.CircularProgressIndicator
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
import kotlin.math.ceil

/**
 * The thing a [com.astraedus.nudge.domain.model.BlockMode.HOLD] block asks the user to do: keep a
 * finger on this target for the rule's whole duration ([issue #35](https://github.com/astraedus/nudge/issues/35)).
 *
 * Deliberately the SAME shape and the same big number as the DELAY countdown next door, because it
 * is the same promise with one difference: the clock only runs while you are touching it, and
 * letting go puts it back to the start. Someone who has met a delay before knows what they are
 * looking at without reading anything.
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
 * machine for the SAME duration; the friction is the wait, not the finger, and it is preserved
 * either way. Both paths converge on one [HoldProgress] and one [onHoldComplete].
 *
 * @param holdDurationMs how long the hold must be sustained without a break.
 * @param onHoldComplete invoked exactly once, when the hold completes. This is the block overlay's
 *   existing completion callback, the one that grants passthrough: the hold IS the block's timer, it
 *   does not add a second way in.
 */
@Composable
fun HoldTarget(
    holdDurationMs: Long,
    onHoldComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Keyed on the duration so a rule edit (or, later, a per-rule override) cannot leave a machine
    // measuring against the old length.
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
    val currentOnHoldComplete by rememberUpdatedState(onHoldComplete)

    LaunchedEffect(holding, holdDurationMs, lifecycleOwner) {
        // A completed hold is done; re-entering would spin the frame loop below forever against a
        // machine that can never advance again.
        if (progress.isCompleted) return@LaunchedEffect
        if (!holding) {
            progress.release()
            fraction = 0f
            return@LaunchedEffect
        }
        try {
            // RESUMED-gated for the same reason the DELAY countdown is (issue #8): an overlay that
            // is not on screen must not be able to open an app. `press` restarts from the moment we
            // come back, so wall-clock time spent away never counts toward entry.
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
                        currentOnHoldComplete()
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
            durationMillis = if (holding) 0 else 320,
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

    val totalSeconds = (holdDurationMs / 1000L).coerceAtLeast(1L)
    // Seconds LEFT, rounded up, so the number only reaches zero when the hold is genuinely done and
    // the last second is visible for its whole length — the same arithmetic the countdown shows.
    val remainingSeconds =
        ceil((holdDurationMs * (1f - drawnFraction)) / 1000f).toLong().coerceIn(0L, totalSeconds)

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
                // Merged into one node so a screen reader announces one control, not a ring, a
                // number and a word.
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "Hold to open. Press and hold for $totalSeconds seconds. " +
                        "Let go and it starts over."
                    onClick(label = "Open after $totalSeconds seconds") {
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
                Text(
                    text = "$remainingSeconds",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp),
                    // Always the face's own ON colour. The pressed state is already carried by the
                    // face, the ring and the scale; tinting the number too would mean picking a
                    // colour that has to stay legible on BOTH faces, and the obvious candidate
                    // (primary on primaryContainer) is the one pairing M3 does not guarantee.
                    color = faceContentColor
                )
                Text(
                    text = if (holding) "KEEP HOLDING" else "HOLD",
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
            text = "Hold to open. Let go and it starts over.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
