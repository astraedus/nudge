package com.astraedus.nudge.domain.web

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pass/revoke decision for one URL-bar reading.
 *
 * The case this file exists for is [an unreadable omnibox must not revoke a live pass]: the inline
 * version in `NudgeAccessibilityService` compared `extractedDomain != lastBlockedDomain` with a
 * NULL extracted domain, so any reading that yielded no domain -- a page title, a search query, a
 * half-typed address -- looked like "the user navigated away" and dropped the pass while they were
 * still sitting on the page. The next content change then re-blocked them mid-visit.
 */
class WebDomainGateTest {

    @Test
    fun `an unreadable url bar changes nothing`() {
        assertEquals(
            WebDomainGate.Action.UNREADABLE,
            WebDomainGate.decide(extractedDomain = null, grantedDomain = "instagram.com")
        )
    }

    @Test
    fun `an unreadable url bar with no pass held is still a no-op`() {
        assertEquals(
            WebDomainGate.Action.UNREADABLE,
            WebDomainGate.decide(extractedDomain = null, grantedDomain = null)
        )
    }

    @Test
    fun `the domain the user completed is passed through`() {
        assertEquals(
            WebDomainGate.Action.PASSTHROUGH,
            WebDomainGate.decide(extractedDomain = "instagram.com", grantedDomain = "instagram.com")
        )
    }

    @Test
    fun `a different domain is evaluated`() {
        assertEquals(
            WebDomainGate.Action.EVALUATE,
            WebDomainGate.decide(extractedDomain = "youtube.com", grantedDomain = "instagram.com")
        )
    }

    @Test
    fun `a domain with no pass held is evaluated`() {
        assertEquals(
            WebDomainGate.Action.EVALUATE,
            WebDomainGate.decide(extractedDomain = "instagram.com", grantedDomain = null)
        )
    }

    /**
     * Returning to a site is a fresh block, not a pass: the grant was dropped when the user left the
     * domain, so the only way to reach PASSTHROUGH is to still be on the exact domain that was
     * granted.
     */
    @Test
    fun `re-entry after the grant was dropped re-evaluates`() {
        val granted: String? = null // cleared on navigating away / leaving the browser / Home
        assertEquals(
            WebDomainGate.Action.EVALUATE,
            WebDomainGate.decide(extractedDomain = "instagram.com", grantedDomain = granted)
        )
    }
}
