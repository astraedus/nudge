package com.astraedus.nudge.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Compose [content] under a lifecycle that cannot reach RESUMED while [paused].
 *
 * Every timed block body runs its clock inside `repeatOnLifecycle(RESUMED)`: the countdown, the
 * breathing exercise and the hold all stop the moment the overlay is not the thing on screen
 * (issue #8). The redirect-app picker breaks that assumption from the inside: it is a bottom sheet
 * in its own dialog window, so the ACTIVITY stays RESUMED while the user is scrolling a list of
 * apps with the countdown hidden under a scrim. Left alone, a 15-second delay would finish behind
 * the sheet and drop the user into the blocked app halfway through choosing somewhere better to
 * go, the exact opposite of what they asked for.
 *
 * Capping the lifecycle the content SEES fixes all three timers with no change to any of them, and
 * keeps one definition of "the block is being looked at". It only ever holds a timer BACK: the cap
 * is STARTED, never above the activity's own state, and the activity's own lifecycle (which
 * `onTimerComplete`'s grant gate reads) is untouched.
 */
@Composable
fun PauseWhile(paused: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { CappedLifecycleOwner(parent) }
    SideEffect { owner.paused = paused }
    DisposableEffect(owner) {
        owner.attach()
        onDispose { owner.detach() }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner) { content() }
}

/**
 * The state [PauseWhile]'s content sees: the parent's, held at STARTED while [paused]. Never raises
 * a state, so a stopped or destroyed parent passes straight through.
 */
internal fun cappedLifecycleState(parent: Lifecycle.State, paused: Boolean): Lifecycle.State =
    if (paused && parent.isAtLeast(Lifecycle.State.RESUMED)) Lifecycle.State.STARTED else parent

private class CappedLifecycleOwner(private val parent: LifecycleOwner) : LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val observer = LifecycleEventObserver { _, _ -> sync() }

    var paused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    init {
        sync()
    }

    fun attach() = parent.lifecycle.addObserver(observer)

    /** The subtree is gone: stop following the parent, and end every `repeatOnLifecycle` in it. */
    fun detach() {
        parent.lifecycle.removeObserver(observer)
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private fun sync() {
        val current = registry.currentState
        if (current == Lifecycle.State.DESTROYED) return
        val target = cappedLifecycleState(parent.lifecycle.currentState, paused)
        // LifecycleRegistry refuses INITIALIZED -> DESTROYED; there is nothing to tear down yet.
        if (target == Lifecycle.State.DESTROYED && current == Lifecycle.State.INITIALIZED) return
        registry.currentState = target
    }
}
