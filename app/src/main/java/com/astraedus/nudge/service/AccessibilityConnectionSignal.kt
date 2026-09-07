package com.astraedus.nudge.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fires when our accessibility service is bound or torn down, so anything showing protection state
 * knows to look at [ProtectionStatus] again.
 *
 * ## The bug this exists for
 *
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` is written when the user flips the toggle; the
 * system then binds the service **asynchronously** (`AccessibilityManagerService.updateServicesLocked`
 * → `bindServiceAsUser`, with the component sitting in `mBindingServices` until `onServiceConnected`
 * lands). So a `ContentObserver` on that setting fires DURING the gap, when the service is granted
 * but not yet connected, which is byte-for-byte the "crashed" state.
 *
 * That would only be a momentary wrong tick if anything read again afterwards. Nothing did. While
 * the Settings screen stays resumed, the observer was its only live refresh path (the other is
 * `ON_RESUME`, and re-enabling the service from a split window, from `adb shell settings put`, or
 * from a quick-settings tile never pauses the screen). So the stale "granted but not connected"
 * reading **latched**, and the screen sat on ✖ with "turn it off and back on" over a service that
 * had been happily bound for twenty seconds. Device QA saw exactly that and could not tell it from
 * a screen that just needed more time. It never would have healed.
 *
 * ## Why a signal and not a retry
 *
 * The re-read has to be driven by the event it was racing, not by a timer: a poll would still be
 * wrong for however long its interval is, and it would paper over the actual mechanism.
 * `onServiceConnected()` IS the bind completing, it is the earliest correct moment to look, with
 * no interval to tune and no wakeups when nothing is happening.
 *
 * This is a "look again" nudge, never an answer. The truth stays [ProtectionStatus], which asks the
 * framework. A static flag would be the same mistake `NudgeMonitorService.isRunning` is documented
 * as deliberately NOT making for the watchdog: an in-process boolean cannot tell "our process was
 * killed and the service is gone" from "our process has only just started", and the watchdog's
 * whole job is that distinction. It is only usable here because the UI is asking a different
 * question, *has something changed?*, of a process that is by definition alive to ask it.
 */
object AccessibilityConnectionSignal {

    private val _generation = MutableStateFlow(0)

    /**
     * Increments on every bind and teardown. Collectors get the current value immediately, so a
     * screen that starts collecting after a change has already happened still re-reads at once.
     * The value itself carries no meaning beyond "different from last time".
     */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Called from `NudgeAccessibilityService.onServiceConnected` and `onDestroy`. */
    fun onConnectionChanged() {
        _generation.update { it + 1 }
    }
}
