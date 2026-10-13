package com.astraedus.nudge.domain.block

import com.astraedus.nudge.data.repository.ContentFilterRepository
import com.astraedus.nudge.domain.ContentFilterMatcher
import com.astraedus.nudge.domain.WebDomainMatcher
import com.astraedus.nudge.domain.block.WebBlockEscape.Exit
import com.astraedus.nudge.domain.web.WebDomainGate
import com.astraedus.nudge.service.WebDomainDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * [WebBlockEscape]: the exit a website block takes so the browser is not left on the blocked page
 * ([#78](https://github.com/astraedus/nudge/issues/78)).
 *
 * Two things are pinned here. The decision (escape, unless the last escape from this browser
 * demonstrably did not take, and then go home exactly once), and the destination itself: the
 * neutral page must be something NO layer of the web blocker can ever block, or the escape would
 * land the user on another block and the loop this fixes would come straight back.
 */
class WebBlockEscapeTest {

    private companion object {
        const val CHROME = "com.android.chrome"
        const val BRAVE = "com.brave.browser"
        const val DOMAIN = "brave.com"
    }

    private var clock = 50_000L
    private val escape = WebBlockEscape()

    private fun walkAway(browser: String = CHROME, domain: String? = DOMAIN) =
        escape.decideExit(browser, domain, clock)

    private fun deliver(browser: String = CHROME, domain: String? = DOMAIN) =
        escape.onBlockDelivered(browser, domain, clock)

    // ── which blocks escape at all ──

    @Test
    fun `only a block carrying a passthrough browser is a web block`() {
        assertEquals(CHROME, WebBlockEscape.browserToEscape(CHROME))
        assertNull("an app block carries no passthrough package", WebBlockEscape.browserToEscape(null))
        assertNull(WebBlockEscape.browserToEscape(""))
        assertNull(WebBlockEscape.browserToEscape("   "))
    }

    // ── the decision ──

    @Test
    fun `a web walk-away leaves the page`() {
        assertEquals(Exit.LEAVE_THE_PAGE, walkAway())
    }

    /**
     * The reporter's case once the escape works: block, leave, browse, get blocked again later,
     * leave again. Every one of those must escape; nothing about a successful escape may degrade
     * the next one.
     */
    @Test
    fun `repeated blocks that are not a failed escape keep leaving the page`() {
        repeat(5) {
            deliver()
            assertEquals(Exit.LEAVE_THE_PAGE, walkAway())
            clock += WebBlockEscape.ESCAPE_VERIFY_MS + 10_000L
        }
    }

    /**
     * A browser that accepts the intent and ignores it comes back still on the blocked page, and
     * the service blocks it at once (the walk-away window is deliberately not armed). Without this
     * memory the next walk-away escapes again, into the same block: the user bounces between the
     * two forever, which is worse than the pre-#78 behaviour of simply going home.
     */
    @Test
    fun `the same block delivered again right after an escape sends the next walk-away home`() {
        assertEquals(Exit.LEAVE_THE_PAGE, walkAway())
        clock += 900L // the browser came forward still on the page and re-blocked
        deliver()

        assertEquals(Exit.GO_HOME, walkAway())
    }

    /** One-shot: a mis-read costs one trip home, and the escape is tried again after it. */
    @Test
    fun `the go-home fallback is spent after one use`() {
        walkAway()
        clock += 900L
        deliver()
        assertEquals(Exit.GO_HOME, walkAway())

        clock += 60_000L
        deliver()
        assertEquals(Exit.LEAVE_THE_PAGE, walkAway())
    }

    /** A user who escaped, waited, and typed the same address again has not seen the escape fail. */
    @Test
    fun `the same block after the verify window is a new visit, not a failed escape`() {
        walkAway()
        clock += WebBlockEscape.ESCAPE_VERIFY_MS
        deliver()

        assertEquals(Exit.LEAVE_THE_PAGE, walkAway())
    }

    @Test
    fun `a different site blocked right after an escape is not a failed escape`() {
        walkAway(domain = DOMAIN)
        clock += 500L
        deliver(domain = "example-blocked.com")

        assertEquals(Exit.LEAVE_THE_PAGE, walkAway(domain = "example-blocked.com"))
    }

    @Test
    fun `a block in a different browser right after an escape is not a failed escape`() {
        walkAway(browser = CHROME)
        clock += 500L
        deliver(browser = BRAVE)

        assertEquals(Exit.LEAVE_THE_PAGE, walkAway(browser = CHROME))
        assertEquals(Exit.LEAVE_THE_PAGE, walkAway(browser = BRAVE))
    }

    @Test
    fun `a failed escape in one browser does not send another browser home`() {
        walkAway(browser = CHROME)
        clock += 500L
        deliver(browser = CHROME)

        assertEquals(Exit.LEAVE_THE_PAGE, walkAway(browser = BRAVE))
        assertEquals(Exit.GO_HOME, walkAway(browser = CHROME))
    }

    /** The intent could not even start, so it went home already; nothing is left to disprove. */
    @Test
    fun `an escape that could not be started is not in flight`() {
        walkAway()
        escape.onEscapeUnavailable()
        clock += 500L
        deliver()

        assertEquals(Exit.LEAVE_THE_PAGE, walkAway())
    }

    /** A block with no domain (the cooldown overlay never has one missing, but the extra is nullable). */
    @Test
    fun `a domainless block still verifies by browser`() {
        walkAway(domain = null)
        clock += 500L
        deliver(domain = null)

        assertEquals(Exit.GO_HOME, walkAway(domain = null))
    }

    // ── the destination can never itself be blocked ──

    /**
     * The service reads the URL bar after the escape lands. `about:blank` must come back as "no
     * domain", which `WebDomainGate` turns into UNREADABLE: do nothing. Anything that yields a
     * domain would go on to the rule lookup and the content filter, and could block again.
     */
    @Test
    fun `the neutral page yields no domain, so the service does nothing on it`() {
        val domain = WebDomainMatcher.extractDomain(WebBlockEscape.NEUTRAL_URL)
        assertNull(domain)
        assertEquals(WebDomainGate.Action.UNREADABLE, WebDomainGate.decide(domain, null))
        assertEquals(WebDomainGate.Action.UNREADABLE, WebDomainGate.decide(domain, DOMAIN))
    }

    /**
     * Belt and braces behind the test above, over the SHIPPED content-filter layers (the real asset
     * and the real keyword lists, never a fake): even if the gate ever let it through, no layer
     * of the content filter may match the neutral page.
     */
    @Test
    fun `no content filter layer matches the neutral page`() {
        val url = WebBlockEscape.NEUTRAL_URL
        assertFalse(ContentFilterMatcher.matchesDomain(url, shippedBlocklist()))
        assertFalse(ContentFilterMatcher.matchesKeyword(url, ContentFilterMatcher.DEFAULT_KEYWORDS))
        assertFalse(
            ContentFilterMatcher.matchesQueryKeyword(url, ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS)
        )
    }

    /** What some browsers show in the URL bar for it instead of the URL itself. */
    @Test
    fun `the neutral page as a browser might display it still yields no domain`() {
        listOf("about:blank", "ABOUT:BLANK", " about:blank ", "about:blank#").forEach {
            assertNull("'$it' must not yield a domain", WebDomainMatcher.extractDomain(it))
        }
    }

    /** Every browser the detector supports is a browser an escape may be aimed at. */
    @Test
    fun `every supported browser package is accepted as an escape target`() {
        WebDomainDetector.BROWSER_URL_BAR_IDS.keys.forEach {
            assertEquals(it, WebBlockEscape.browserToEscape(it))
        }
    }

    private fun shippedBlocklist(): Set<String> {
        val file = listOf(
            File("src/main/assets/content_filter_domains.txt"),
            File("app/src/main/assets/content_filter_domains.txt")
        ).firstOrNull { it.exists() } ?: error("content_filter_domains.txt not found")
        return file.readLines().mapNotNull { ContentFilterRepository.parseLine(it) }.toSet()
    }
}
