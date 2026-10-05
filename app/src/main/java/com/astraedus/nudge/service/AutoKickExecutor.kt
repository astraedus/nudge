package com.astraedus.nudge.service

import com.astraedus.nudge.domain.logging.NudgeLog

/**
 * The single place an auto-kick actually happens.
 *
 * Nudge has two triggers — the interaction count (`autoKickAfter`, driven by tap/scroll events in
 * [InteractionHandler]) and session foreground time (`autoKickAfterMinutes`, driven by the periodic
 * clock in [AutoKickTimeHandler]) — but exactly ONE kick: arm the cooldown, go home, reset the
 * session, drop the counter overlay. Both call [kick] so the two triggers can never drift apart in
 * what they do to the user, only in what makes them fire.
 *
 * Must be invoked on the main thread: hiding the counter overlay touches the WindowManager.
 *
 * @param onKicked told the kicked key after every kick, for the bounce check-in (a kick is a
 *   "wall"). Defaulted so the executor's own tests need not care.
 * @param goHome how to actually send the user home. Injected rather than built here so this class
 *   holds only the POLICY (which is pure and unit-testable) and the caller owns the Android
 *   mechanism — which lets the service prefer the accessibility global action over a HOME intent.
 */
class AutoKickExecutor(
    private val interactionTracker: InteractionTracker,
    private val counterOverlayManager: CounterOverlayManagerApi,
    private val counterCache: CounterCacheRefresher,
    private val logger: NudgeLog,
    private val onKicked: (String) -> Unit = {},
    private val goHome: () -> Unit
) {

    /**
     * Sends the user home and starts the cooldown for [packageName].
     *
     * @param reason short trigger description for the log — the two triggers are otherwise
     *   indistinguishable in logcat, which matters when diagnosing "why was I kicked".
     */
    fun kick(packageName: String, reason: String) {
        logger.i("auto-kick triggered package=$packageName reason=$reason")

        val cooldownSeconds = counterCache.getEntry(packageName)?.autoKickCooldownSeconds ?: 0
        if (cooldownSeconds > 0) {
            interactionTracker.setCooldown(packageName, cooldownSeconds.toLong() * 1000L)
        }

        goHome()
        onKicked(packageName)

        // Resets BOTH the interaction count and the foreground-time baseline, so the next session
        // starts fresh regardless of which trigger fired.
        interactionTracker.resetSession(packageName)

        try {
            counterOverlayManager.hide()
        } catch (e: Exception) {
            logger.w("counter overlay hide after auto-kick failed package=$packageName", e)
        }
    }
}
