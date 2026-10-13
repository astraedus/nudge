package com.astraedus.nudge.domain.block

import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a WEBSITE block lets the user out without leaving the browser stuck on the blocked page
 * ([#78](https://github.com/astraedus/nudge/issues/78)).
 *
 * ## The trap
 *
 * A web block (a per-rule website, or the content filter) is shown over the BROWSER, and its only
 * exit used to be the walk-away's "go home". Going home does not touch the browser: its tab is still
 * on the blocked URL. `HARD_BLOCK` never grants passthrough, so the next time the browser comes to
 * the front the service reads the same URL bar and blocks again. Chrome restores its tabs through a
 * force-stop and Brave restores its last tab, so the browser was blocked on every launch, for good,
 * unless Nudge was switched off. The assumption that "leaving" clears the offending state was wrong:
 * for an app it does, for a tab it does not.
 *
 * ## The exit
 *
 * Leaving a website now means navigating the browser AWAY from it: an `ACTION_VIEW` of
 * [NEUTRAL_URL] targeted at that browser package, tagged with our package as the
 * `Browser.EXTRA_APPLICATION_ID`. The user lands in the same browser on a blank page, which is the
 * walk-away they asked for (leave the site) without the side effect they did not (lose the browser).
 *
 *  - **Why `about:blank`.** It loads nothing, needs no network (Nudge has no internet permission
 *    and should not be sending anyone to a third-party page), and it can never itself be blocked:
 *    it has no domain, so the URL-bar read comes back `UNREADABLE` and the service does nothing.
 *    That property is a test (`WebBlockEscapeTest`), not a hope. Chromium accepts it from an
 *    external app explicitly (`ExternalIntentUrlChecker` lists `about:blank` as a safe exception,
 *    and the `IntentDispatcher` filter declares the `about` scheme), which covers Chrome, Brave,
 *    Edge and Kiwi.
 *  - **Why the application id.** Chromium reuses the tab an app opened before
 *    (`ChromeTabCreator.launchUrlFromExternalApp` replaces it at the same index), so repeated
 *    escapes do not pile up blank tabs, and if the user navigated that very tab back to a blocked
 *    site, the next escape replaces it.
 *  - **Why not `GLOBAL_ACTION_BACK`.** Back only reaches the browser once the overlay is gone, so
 *    the blocked page is on screen while the browser processes it, which breaks the one rule a hard
 *    block has. And on a tab with no history Chromium's back MINIMISES the browser and keeps the
 *    tab, which is exactly the restored-tab case this issue is about.
 *
 * ## Failing closed, and not looping
 *
 * The walk-away window ([BlockLaunchGate.WALK_AWAY_TRANSITION_MS]) is deliberately NOT armed for
 * this exit: the browser coming forward is the destination, not a resurfacing, and if it still shows
 * the blocked URL it must be blocked at once, never suppressed for 1.5 seconds. That is the fail
 * closed direction, but on its own it could loop: a browser that accepts the intent and ignores it
 * would bounce the user between the block and the browser forever. So this class remembers the last
 * escape, and the same block delivered again within [ESCAPE_VERIFY_MS] is read as the escape having
 * failed; the NEXT walk-away from that browser goes home instead, exactly as before this fix. It is
 * one-shot on purpose: a mis-read costs one trip home, and the escape is tried again after it.
 *
 * Pure apart from being a process-lifetime singleton (it outlives the overlay instance, which is
 * finished on every exit). Every method takes the clock; the activity passes `elapsedRealtime`.
 */
@Singleton
class WebBlockEscape @Inject constructor() {

    /** Where a web walk-away sends the user. */
    enum class Exit {
        /** Navigate the browser to [NEUTRAL_URL]. */
        LEAVE_THE_PAGE,

        /** The previous escape from this browser did not take: go home, as before #78. */
        GO_HOME
    }

    /** An escape that has been dispatched and not yet proved or disproved. */
    data class Attempt(val browserPackage: String, val domain: String?, val atMs: Long)

    private var inFlight: Attempt? = null

    /** The browser whose last escape was disproved, owed one plain go-home. */
    private var failedBrowser: String? = null

    companion object {
        /** The page a web walk-away lands on. See the class doc for why this one. */
        const val NEUTRAL_URL = "about:blank"

        /**
         * How long after an escape the SAME block arriving again means the escape did not take.
         *
         * A failed escape re-blocks in the time it takes the browser to come forward and the service
         * to read its URL bar, about a second on the bench. A user deliberately typing the same
         * blocked address again takes longer than this, and getting that wrong costs one trip home.
         */
        const val ESCAPE_VERIFY_MS = 3_000L

        /**
         * The browser to escape, given the overlay's `EXTRA_PASSTHROUGH_PACKAGE`, or null when this
         * is not a web block.
         *
         * Keyed off that extra because ONLY the two web launch sites set it (a rule's website, and
         * the web auto-kick cooldown); it is the browser the user is sitting in, which is what has
         * to be navigated. Nuke's block on a browser is an APP block and carries none, so it still
         * goes home: Nuke blocks the browser itself, whatever page is open.
         */
        fun browserToEscape(passthroughPackage: String?): String? =
            passthroughPackage?.takeIf { it.isNotBlank() }
    }

    /** A web block for [domain] in [browserPackage] has just been delivered to the overlay. */
    @Synchronized
    fun onBlockDelivered(browserPackage: String, domain: String?, nowMs: Long) {
        val attempt = inFlight ?: return
        inFlight = null
        val sameBlock = attempt.browserPackage == browserPackage && attempt.domain == domain
        if (sameBlock && nowMs - attempt.atMs < ESCAPE_VERIFY_MS) failedBrowser = browserPackage
    }

    /**
     * Decide the exit for a walk-away from a web block in [browserPackage], and record it.
     *
     * Called exactly once per executed walk-away (the overlay calls it while performing the effect,
     * never while computing it), because it CONSUMES state: a [Exit.LEAVE_THE_PAGE] starts the
     * verification window, and a [Exit.GO_HOME] spends the one-shot fallback.
     */
    @Synchronized
    fun decideExit(browserPackage: String, domain: String?, nowMs: Long): Exit {
        if (failedBrowser == browserPackage) {
            failedBrowser = null
            inFlight = null
            return Exit.GO_HOME
        }
        inFlight = Attempt(browserPackage, domain, nowMs)
        return Exit.LEAVE_THE_PAGE
    }

    /** The escape could not even be started (no activity for it): nothing is in flight. */
    @Synchronized
    fun onEscapeUnavailable() {
        inFlight = null
    }
}
