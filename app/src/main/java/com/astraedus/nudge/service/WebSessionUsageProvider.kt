package com.astraedus.nudge.service

import com.astraedus.nudge.domain.web.WebSessionKey

/**
 * Answers "how much foreground time has this website had" by reading the BROWSER's clock.
 *
 * There is no `UsageStatsManager` event stream for a website -- only the browser package produces
 * `ACTIVITY_RESUMED`/`PAUSED` -- so a web session has to measure something real, and the only real
 * thing available is how long the browser itself has been on screen. Combined with
 * [InteractionTracker]'s session baseline (a reading taken when the user landed on the domain), the
 * delta is "browser-on-screen time since you arrived here", which inherits every property the app
 * path already gets for free: time in other apps is not in it, and time with the screen off is not
 * in it. Nothing new polls the system, and no new permission is involved.
 *
 * **Known imprecision, deliberate.** A quick detour to another site inside the same browser session
 * still accrues, because the reading is browser-wide and a session survives a short absence
 * ([InteractionTracker.SESSION_EXPIRY_MS]). That is the same direction the app path already chose --
 * a tab-out-and-back must not refill a time budget -- so it errs toward enforcement, which is the
 * right way for a blocker to be wrong. Attributing time to a domain exactly needs persisted
 * per-domain session records; see `docs/BACKLOG.md`.
 *
 * A real package name passes straight through to [delegate], so one provider can serve both.
 */
class WebSessionUsageProvider(
    private val delegate: UsageProvider
) : UsageProvider {

    /**
     * The browser the current web session is running in. Null between sessions, which reads as 0 --
     * `TimeKickEvaluator` treats a zero/absent reading as "start a session", never as a kick.
     */
    @Volatile
    var browserPackage: String? = null

    override fun getDailyForegroundTimeMs(packageName: String): Long {
        if (!WebSessionKey.isWebKey(packageName)) {
            return delegate.getDailyForegroundTimeMs(packageName)
        }
        val browser = browserPackage ?: return 0L
        return delegate.getDailyForegroundTimeMs(browser)
    }
}
