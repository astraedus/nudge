package com.astraedus.nudge.domain.web

/**
 * What to do with a URL-bar reading, given the domain the user currently holds a pass for.
 *
 * Extracted from `NudgeAccessibilityService.evaluateWebDomain` because the inline version got the
 * unreadable case backwards. It read:
 *
 * ```
 * if (extractedDomain != null && extractedDomain == lastBlockedDomain) return   // pass
 * if (extractedDomain != lastBlockedDomain) lastBlockedDomain = null            // revoke
 * ```
 *
 * so a reading that yielded NO domain -- the omnibox showing a page title or a search query, a
 * partially-typed URL, a `chrome://` internal scheme -- was `null != "instagram.com"` and **revoked
 * a live pass while the user was still on the page**, re-blocking them mid-visit. That is issue #5's
 * failure class, arrived at from a different direction.
 *
 * The rule this file exists to hold: **unverifiable means do nothing.** It is the same call the
 * issue-#7 content-change fallback makes for a null active window -- a false negative just retries
 * on the next event, a false positive costs the user their pass while they are still using the page.
 */
object WebDomainGate {

    enum class Action {
        /** No domain could be read. Leave every piece of state exactly as it is. */
        UNREADABLE,

        /** The user already completed this domain's block. Skip evaluation, keep the pass. */
        PASSTHROUGH,

        /** A real, different (or unheld) domain. Revoke any stale pass and evaluate it. */
        EVALUATE
    }

    fun decide(extractedDomain: String?, grantedDomain: String?): Action = when {
        extractedDomain == null -> Action.UNREADABLE
        grantedDomain != null && extractedDomain == grantedDomain -> Action.PASSTHROUGH
        else -> Action.EVALUATE
    }
}
