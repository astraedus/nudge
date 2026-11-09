package com.astraedus.nudge.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The wait of a DELAY-family block: [totalSeconds] counted down to zero, one second at a time.
 *
 * One object so that a body which can change its picture mid-wait (MIRROR, falling back from the
 * camera to the plain delay body) keeps ONE clock: the fallback neither restarts the wait nor runs
 * a second countdown that could complete twice.
 */
@Stable
class BlockCountdown internal constructor(val totalSeconds: Int) {
    var remainingSeconds by mutableIntStateOf(totalSeconds)
        private set

    /** True from the moment the wait has reached zero. */
    val finished: Boolean get() = remainingSeconds <= 0

    internal fun tick() {
        if (remainingSeconds > 0) remainingSeconds--
    }
}

/**
 * Remember the countdown for this block and run it, calling [onComplete] exactly once when it
 * reaches zero.
 *
 * Unkeyed by design: BlockOverlayActivity composes the block body under a per-delivery key, so a
 * new block already gets fresh state (issue #15). Keying here on the seconds would look like a fix
 * but miss the common case: two apps both on the default 15s delay would still share it.
 *
 * The countdown ticks ONLY while the overlay is actually on screen (issue #8). A plain
 * LaunchedEffect(Unit) is not frame-gated: `delay()` kept counting after the user tabbed out, so
 * the timer reached zero invisibly and granted passthrough while they were on the launcher, and
 * returning to the app then opened it with no delay at all. The count lives OUTSIDE the block
 * below, so a pause resumes where it left off instead of restarting.
 *
 * repeatOnLifecycle cancels the block below RESUMED and starts a NEW one from the top on re-entry,
 * so once the count has reached zero a pause/resume cycle would fall straight through the loop and
 * fire onComplete a second time. The guard makes completion exactly-once: onComplete grants
 * passthrough, and this path must never be able to grant it twice.
 */
@Composable
fun rememberBlockCountdown(delaySeconds: Int, onComplete: () -> Unit): BlockCountdown {
    val countdown = remember { BlockCountdown(delaySeconds) }
    val completed = remember { AtomicBoolean(false) }
    val currentOnComplete by rememberUpdatedState(onComplete)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (countdown.remainingSeconds > 0) {
                delay(1000L)
                countdown.tick()
                // "X left today" is deliberately NOT ticked down here: while this overlay is up the
                // blocked app is paused, so UsageStatsManager accrues nothing against the budget.
                // Draining the display would show the user spending time they are not spending.
            }
            if (completed.compareAndSet(false, true)) currentOnComplete()
        }
    }
    return countdown
}
