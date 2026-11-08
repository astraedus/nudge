package com.astraedus.nudge.service

import android.os.Handler
import android.os.Looper
import com.astraedus.nudge.domain.logging.NudgeLog

/**
 * ONE timer to the next instant a rule's answer can change by the clock alone, and the re-check of
 * the app in front when it gets there (#169, #170).
 *
 * ## The bug it closes
 *
 * Every block decision is taken when a window event arrives. An app opened at 21:55 under a
 * 22:00-06:00 rule produced its only event at 21:55, so at 22:00 nothing asked again and the user
 * scrolled on, unblocked, for as long as they sat still (#169). A block screen up at a rule's end, or
 * at the midnight a spent daily limit resets, had the mirror-image problem (#170); that half is the
 * overlay's own end timer, and this one covers the app in front when no block screen is up.
 *
 * ## Why one timer, and why a Handler
 *
 *  - **One timer to the earliest boundary** ([nextBoundary], `ScheduleBoundaries.next`), never a
 *    per-minute tick. A set of rules with nothing on a clock answers null and NO timer is posted:
 *    #63's rule that a rule which needs no clock has none.
 *  - **A main-looper Handler, not AlarmManager.** An exact alarm needs `SCHEDULE_EXACT_ALARM` on API
 *    31+ (a new permission, and a Play declaration), an inexact one can be many minutes late, and
 *    any alarm wakes the CPU from Doze to look at a screen nobody is watching. A Handler does none of
 *    that.
 *  - **The Doze cost, and how it is handled.** A Handler runs on uptime, which stops in deep sleep,
 *    so a boundary crossed with the screen off fires late, or not until the device wakes. That is
 *    the right time anyway: nothing can be in front of a sleeping screen. So the timer is re-armed
 *    from the WALL clock on screen-on ([onScreenOn]), and a boundary crossed while the screen was off
 *    or locked is re-checked at the unlock ([onUnlocked]), never over the lock screen.
 *  - **The wall clock is re-read on every fire.** A timer that fires before its wall-clock target
 *    (the clock was set back) simply re-arms ([onFired]); a clock, timezone or date change re-checks
 *    and re-arms at once ([onClockChanged]), because the rules read the local wall clock.
 *
 * ## What it is NOT
 *
 * It decides nothing about blocking. [onBoundary] is the service's re-check, which goes through the
 * ordinary `evaluateForegroundPackage`, so grants already earned (a completed delay's pass, the
 * daily 2-minute pass, a cooldown) are honoured exactly as on any other arrival: a pass earned under
 * a Delay rule lasts its sitting even when a stricter schedule starts (groups spec 534-537).
 *
 * Pure apart from [HandlerScheduler]: every input is a parameter, so a JVM test drives it with a fake
 * scheduler and an injected clock (`ScheduleBoundaryWatchTest`). Main thread only.
 */
class ScheduleBoundaryWatch(
    private val scheduler: Scheduler,
    /** Wall-clock epoch ms. */
    private val wallClock: () -> Long,
    /** The earliest boundary strictly after the given instant, or null for none. */
    private val nextBoundary: (nowMs: Long) -> Long?,
    /** Screen interactive and keyguard down: a re-check may put a block in front of someone. */
    private val deviceReady: () -> Boolean,
    /** Look at the app in front again. */
    private val onBoundary: () -> Unit,
    logger: NudgeLog
) {

    /** Posts and cancels the ONE pending task. */
    interface Scheduler {
        fun post(delayMs: Long, task: () -> Unit)
        fun cancel()
    }

    private val log = logger.tagged("ScheduleBoundary")

    /** The wall-clock instant the posted task is for, or null when nothing is armed. */
    var targetMs: Long? = null
        private set

    /** A boundary was crossed while the device was not ready; re-check at the unlock. */
    var pendingRecheck: Boolean = false
        private set

    /**
     * Post the one task for the next boundary from now, replacing any earlier one. Called when the
     * rules, the master toggle or the first-unlock anchor change, and after every boundary.
     */
    fun rearm(reason: String) {
        scheduler.cancel()
        val now = wallClock()
        val next = nextBoundary(now)
        targetMs = next
        if (next == null) {
            log.d("schedule boundary none reason=$reason")
            return
        }
        val inMs = (next - now).coerceAtLeast(0L)
        log.i("schedule boundary armed at=$next inMs=$inMs reason=$reason")
        scheduler.post(inMs) { onFired() }
    }

    /** Nothing is armed and nothing is pending: Nudge was switched off or the service is going. */
    fun cancel() {
        scheduler.cancel()
        targetMs = null
        pendingRecheck = false
    }

    /**
     * The screen came on. The timer ran on uptime while it was off, so it is re-armed from the wall
     * clock; a boundary already passed is crossed now (re-checked if unlocked, else at the unlock).
     */
    fun onScreenOn() {
        if (targetPassed()) crossed("screen_on") else rearm("screen_on")
    }

    /** The user unlocked: a boundary crossed while they could not see the screen is re-checked now. */
    fun onUnlocked() {
        if (pendingRecheck || targetPassed()) crossed("unlock")
    }

    /**
     * The wall clock, the timezone or the date changed under us. Every rule reads the local wall
     * clock, so the app in front is re-checked and the timer re-armed from the new time.
     */
    fun onClockChanged(reason: String) {
        crossed(reason)
    }

    private fun onFired() {
        val target = targetMs ?: return
        if (wallClock() < target) {
            // Uptime ran ahead of the wall clock (it was set back while we waited): not there yet.
            rearm("early")
            return
        }
        crossed("timer")
    }

    private fun crossed(reason: String) {
        if (deviceReady()) {
            pendingRecheck = false
            onBoundary()
        } else {
            pendingRecheck = true
            log.i("schedule boundary crossed while the device is locked or asleep, re-check at unlock reason=$reason")
        }
        rearm(reason)
    }

    private fun targetPassed(): Boolean = targetMs?.let { wallClock() >= it } ?: false

    companion object {
        /**
         * The app a boundary re-check looks at, or null to look at nothing. Same gates as the
         * first-unlock and Nuke re-checks, plus one: a block screen that is UP owns its own end
         * (`BlockOverlayActivity.scheduleBlockEnd`), and the front window is then Nudge's own anyway.
         */
        fun recheckTarget(
            globalEnabled: Boolean,
            overlayActive: Boolean,
            frontPackage: String?,
            ownPackage: String
        ): String? = when {
            !globalEnabled || overlayActive -> null
            frontPackage.isNullOrBlank() || frontPackage == ownPackage -> null
            else -> frontPackage
        }
    }
}

/**
 * [ScheduleBoundaryWatch.Scheduler] on the main looper, with its OWN Handler and token: nothing else
 * that clears a handler (the overlay's `CancelPendingFailSafe`, any `removeCallbacksAndMessages(null)`)
 * can drop the boundary task, and cancelling it touches nothing else.
 */
class HandlerScheduler : ScheduleBoundaryWatch.Scheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun post(delayMs: Long, task: () -> Unit) {
        cancel()
        val runnable = Runnable {
            pending = null
            task()
        }
        pending = runnable
        handler.postDelayed(runnable, delayMs)
    }

    override fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}
