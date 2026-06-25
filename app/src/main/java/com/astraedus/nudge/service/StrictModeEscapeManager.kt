package com.astraedus.nudge.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory grace window for Strict Mode OS-escape guarding (Phase 2).
 *
 * Modeled on [PassthroughManager]: a small, thread-safe, process-lifetime singleton with no DB
 * writes. After the user passes the unlock challenge over a protected Settings screen, we open a
 * short grace window; while it is open the accessibility service does NOT re-guard, so the user can
 * actually complete the disable/uninstall they just committed to instead of being re-challenged on
 * every window event the Settings app emits.
 *
 * The window is purely time-based and intentionally short ([GRACE_WINDOW_MS]); it is not persisted,
 * so a process restart simply clears it (fail-safe: a cleared grace window only ever means "guard
 * again", never "stop guarding").
 */
@Singleton
class StrictModeEscapeManager @Inject constructor() {

    @Volatile
    var graceUntil: Long = 0L
        private set

    /**
     * Open a grace window of [GRACE_WINDOW_MS] starting now. Called after a successful unlock so the
     * user can finish the legitimate disable they committed to without being re-challenged.
     */
    fun grantGrace(now: Long = System.currentTimeMillis()) {
        graceUntil = now + GRACE_WINDOW_MS
    }

    /** True while inside the grace window. Outside it (or never granted) → false. */
    fun isWithinGrace(now: Long = System.currentTimeMillis()): Boolean = now < graceUntil

    /** Clear any active grace window. */
    fun clear() {
        graceUntil = 0L
    }

    fun resetForTests() = clear()

    companion object {
        /**
         * Grace duration. ~60s is enough to toggle a switch / tap Force stop / confirm an uninstall
         * dialog without being re-challenged, but short enough that it does not become a standing
         * bypass of Strict Mode.
         */
        const val GRACE_WINDOW_MS = 60_000L
    }
}
