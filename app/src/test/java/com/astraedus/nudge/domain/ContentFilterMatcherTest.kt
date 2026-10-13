package com.astraedus.nudge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterMatcherTest {

    private val blocklist = setOf(
        "blockedsite.com",
        "anotherblocked.net"
    )

    // ---- matchesDomain ----

    @Test
    fun `matchesDomain exact base domain`() {
        assertTrue(ContentFilterMatcher.matchesDomain("https://blockedsite.com/page", blocklist))
    }

    @Test
    fun `matchesDomain www-stripped base`() {
        assertTrue(ContentFilterMatcher.matchesDomain("https://www.blockedsite.com", blocklist))
    }

    @Test
    fun `matchesDomain subdomain of blocked base`() {
        // m.blockedsite.com -> extractDomain normalizes m. prefix to blockedsite.com
        assertTrue(ContentFilterMatcher.matchesDomain("m.blockedsite.com/foo", blocklist))
    }

    @Test
    fun `matchesDomain deep subdomain via parent strip`() {
        // cdn.assets.anotherblocked.net is not a known-subdomain prefix, but parent
        // stripping should reach anotherblocked.net.
        assertTrue(ContentFilterMatcher.matchesDomain("https://cdn.assets.anotherblocked.net/x", blocklist))
    }

    @Test
    fun `matchesDomain bare domain`() {
        assertTrue(ContentFilterMatcher.matchesDomain("blockedsite.com", blocklist))
    }

    @Test
    fun `matchesDomain non-match mainstream site`() {
        assertFalse(ContentFilterMatcher.matchesDomain("https://wikipedia.org/wiki/Cat", blocklist))
    }

    @Test
    fun `matchesDomain internal scheme returns false`() {
        assertFalse(ContentFilterMatcher.matchesDomain("chrome://settings", blocklist))
        assertFalse(ContentFilterMatcher.matchesDomain("about:blank", blocklist))
    }

    @Test
    fun `matchesDomain blank returns false`() {
        assertFalse(ContentFilterMatcher.matchesDomain("   ", blocklist))
    }

    @Test
    fun `matchesDomain empty blocklist returns false`() {
        assertFalse(ContentFilterMatcher.matchesDomain("blockedsite.com", emptySet()))
    }

    // ---- ALLOWLIST regression guard (mainstream mixed-content platforms) ----

    // Simulate a regenerated blocklist that WRONGLY re-introduces the mainstream platforms. The
    // allowlist must win over the blocklist for these hosts so Reddit/etc. can never be re-blocked.
    private val blocklistWithAllowlisted = setOf(
        "blockedsite.com",
        "reddit.com",
        "redd.it",
        "twitter.com",
        "x.com",
        "imgur.com",
        "discord.com",
        "discordapp.com",
        "tumblr.com",
        "wikipedia.org"
    )

    @Test
    fun `allowlist exempts reddit even when present in blocklist`() {
        assertFalse(ContentFilterMatcher.matchesDomain("https://reddit.com", blocklistWithAllowlisted))
        assertFalse(ContentFilterMatcher.matchesDomain("https://www.reddit.com/r/all", blocklistWithAllowlisted))
        assertFalse(ContentFilterMatcher.matchesDomain("https://m.reddit.com", blocklistWithAllowlisted))
        // Subdomains resolve to the allowlisted parent via the parent-strip walk.
        assertFalse(ContentFilterMatcher.matchesDomain("https://i.redd.it/abc.jpg", blocklistWithAllowlisted))
    }

    @Test
    fun `allowlist exempts every mainstream platform`() {
        for (url in listOf(
            "https://twitter.com/home",
            "https://x.com/explore",
            "https://imgur.com/gallery/x",
            "https://i.imgur.com/x.png",
            "https://discord.com/channels/1",
            "https://discordapp.com/app",
            "https://www.tumblr.com/dashboard",
            "https://en.wikipedia.org/wiki/Cat"
        )) {
            assertFalse("$url should be allowlisted", ContentFilterMatcher.matchesDomain(url, blocklistWithAllowlisted))
        }
    }

    @Test
    fun `allowlist does not exempt a genuinely-blocked domain`() {
        // Non-allowlisted entry in the same blocklist still matches — the guard is targeted, not global.
        assertTrue(ContentFilterMatcher.matchesDomain("https://blockedsite.com/x", blocklistWithAllowlisted))
    }

    @Test
    fun `allowlist does not exempt lookalike domains`() {
        // Typosquat / third-party reddit-adjacent hosts are NOT the mainstream base domain, so if the
        // blocklist has them they still match.
        val bl = setOf("redditorporn.com", "tumblrgirls.net")
        assertTrue(ContentFilterMatcher.matchesDomain("https://redditorporn.com", bl))
        assertTrue(ContentFilterMatcher.matchesDomain("https://tumblrgirls.net", bl))
    }

    @Test
    fun `isAllowlisted covers host and parents but not lookalikes`() {
        assertTrue(ContentFilterMatcher.isAllowlisted("reddit.com"))
        assertTrue(ContentFilterMatcher.isAllowlisted("i.redd.it"))
        assertTrue(ContentFilterMatcher.isAllowlisted("en.wikipedia.org"))
        assertFalse(ContentFilterMatcher.isAllowlisted("redditorporn.com"))
        assertFalse(ContentFilterMatcher.isAllowlisted("notreddit.com"))
        assertFalse(ContentFilterMatcher.isAllowlisted("example.com"))
    }

    @Test
    fun `allowlist exempts the DOMAIN match only - keyword path is unaffected`() {
        // Anti's rule: the allowlist exempts the DOMAIN match; a URL whose raw text contains an
        // explicit NSFW keyword still blocks regardless of host. reddit.com/r/porn → domain exempt,
        // keyword still fires.
        assertFalse(ContentFilterMatcher.matchesDomain("https://reddit.com/r/porn", blocklistWithAllowlisted))
        assertTrue(ContentFilterMatcher.matchesKeyword("https://reddit.com/r/porn", ContentFilterMatcher.DEFAULT_KEYWORDS))
    }

    // ---- matchesKeyword ----

    private val keywords = ContentFilterMatcher.DEFAULT_KEYWORDS

    @Test
    fun `matchesKeyword in path`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://somesite.tld/porn/videos", keywords))
    }

    @Test
    fun `matchesKeyword in query string`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://google.com/search?q=xvideos", keywords))
    }

    @Test
    fun `matchesKeyword in host of unknown domain`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://freepornsite.example/", keywords))
    }

    @Test
    fun `matchesKeyword case insensitive`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://site.tld/PORN", keywords))
    }

    @Test
    fun `matchesKeyword non-match mainstream`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("https://www.wikipedia.org/wiki/Cat", keywords))
        assertFalse(ContentFilterMatcher.matchesKeyword("https://news.ycombinator.com", keywords))
    }

    @Test
    fun `matchesKeyword false-positive guard sussex`() {
        // "sussex" contains "sex" but we deliberately do NOT use "sex" as a token.
        assertFalse(ContentFilterMatcher.matchesKeyword("https://www.sussex.gov.uk/services", keywords))
    }

    @Test
    fun `matchesKeyword false-positive guard essex and analysis`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("https://essex.gov.uk", keywords))
        assertFalse(ContentFilterMatcher.matchesKeyword("https://stats.com/analysis/report", keywords))
        assertFalse(ContentFilterMatcher.matchesKeyword("https://maps.com/panama-canal", keywords))
    }

    @Test
    fun `matchesKeyword internal scheme returns false`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("chrome://flags#porn", keywords))
        assertFalse(ContentFilterMatcher.matchesKeyword("about:blank", keywords))
    }

    @Test
    fun `matchesKeyword blank returns false`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("   ", keywords))
    }

    @Test
    fun `matchesKeyword empty keyword list returns false`() {
        assertFalse(ContentFilterMatcher.matchesKeyword("https://anything.tld/porn", emptyList()))
    }

    // ---- new DEFAULT_KEYWORDS additions ----

    @Test
    fun `matchesKeyword new coined tokens match`() {
        assertTrue(ContentFilterMatcher.matchesKeyword("https://redgifs.com/watch/x", keywords))
        assertTrue(ContentFilterMatcher.matchesKeyword("https://site.tld/porngif/123", keywords))
        assertTrue(ContentFilterMatcher.matchesKeyword("https://stripchat.com", keywords))
    }

    @Test
    fun `matchesKeyword benign gif URL does not match (no bare gif token)`() {
        // Guards that we did NOT add bare "gif" — a benign giphy URL must not match.
        assertFalse(ContentFilterMatcher.matchesKeyword("https://giphy.com/gifs/cat", keywords))
    }

    // ---- Removed keywords: ordinary English words that were blocking benign browsing ----

    /**
     * "escort", "hardcore" and "creampie" were raw substring matches over the whole URL, so
     * they blocked car reviews, music and recipes. They are gone from BOTH keyword lists —
     * demoting them to [ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS] would not have helped,
     * because a whole-word query match still fires on exactly these searches.
     */
    private val benignPhraseUrls = listOf(
        // "escort"
        "https://www.google.com/search?q=ford+escort+review",
        "https://www.caranddriver.com/ford/escort",
        "https://en.wikipedia.org/wiki/Escort_carrier",
        "https://www.google.com/search?q=police+escort+funeral+procession",
        "https://www.escortradar.com/products",
        // "hardcore"
        "https://www.google.com/search?q=hardcore+punk+bands",
        "https://en.wikipedia.org/wiki/Hardcore_punk",
        "https://www.dancarlin.com/hardcore-history-series/",
        "https://www.google.com/search?q=minecraft+hardcore+mode+tips",
        // "creampie"
        "https://www.google.com/search?q=creampie+recipe",
        "https://www.allrecipes.com/recipe/boston-creampie",
        // "fetish" — demoted to the opt-in query list, so a URL/article must still pass
        "https://en.wikipedia.org/wiki/Commodity_fetishism",
        "https://www.google.com/search?q=commodity+fetishism+marx",
        // Issue #78, ambiguous-list tokens whose dominant everyday meaning is innocent
        // "cnc"
        "https://search.brave.com/search?q=cnc+machine",
        "https://www.google.com/search?q=cnc+router+table+plans",
        // "bbc"
        "https://www.google.com/search?q=bbc+news",
        "https://www.google.com/search?q=bbc+iplayer",
        // "sph"
        "https://www.google.com/search?q=harvard+sph+admissions",
        "https://www.google.com/search?q=sph+cyl+axis+eyeglass+prescription"
    )

    @Test
    fun `benign phrase URLs do not match with strict keywords OFF`() {
        val blocked = benignPhraseUrls.filter { ContentFilterMatcher.matchesKeyword(it, keywords) }
        assertTrue("these benign URLs must not trip a default keyword: $blocked", blocked.isEmpty())
    }

    @Test
    fun `benign phrase URLs do not match with strict keywords ON either`() {
        // Strict mode adds the query-scoped ambiguous list. It is aggressive by design, but
        // it must not resurrect the very tokens we removed for being ordinary words.
        val blocked = benignPhraseUrls.filter {
            ContentFilterMatcher.matchesKeyword(it, keywords) ||
                ContentFilterMatcher.matchesQueryKeyword(it, ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS)
        }
        assertTrue("these benign URLs must not trip strict mode either: $blocked", blocked.isEmpty())
    }

    @Test
    fun `removed tokens are absent from both keyword lists`() {
        // The class-level invariant, not three individual cases: an ordinary English word
        // must never come back as a raw-substring token.
        for (token in listOf("escort", "hardcore", "creampie", "cnc", "bbc", "sph")) {
            assertFalse("$token must not be a default keyword", token in ContentFilterMatcher.DEFAULT_KEYWORDS)
            assertFalse(
                "$token must not be a query keyword either",
                token in ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS
            )
        }
        assertFalse("fetish must not be a raw-substring keyword", "fetish" in ContentFilterMatcher.DEFAULT_KEYWORDS)
        assertTrue(
            "fetish should be demoted, not dropped — it is a strong whole-word query signal",
            "fetish" in ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS
        )
    }

    @Test
    fun `the intended adult searches still match`() {
        // The removals must not have gutted the filter: an explicit search still blocks.
        for (url in listOf(
            "https://www.google.com/search?q=porn",
            "https://www.google.com/search?q=free+xxx+videos",
            "https://www.google.com/search?q=hentai",
            "https://www.bing.com/search?q=chaturbate+cams"
        )) {
            assertTrue("$url should still match", ContentFilterMatcher.matchesKeyword(url, keywords))
        }
        // And the demoted token still works in strict mode.
        assertTrue(
            ContentFilterMatcher.matchesQueryKeyword(
                "https://www.google.com/search?q=fetish",
                ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS
            )
        )
    }

    // ---- extractSearchQuery ----

    @Test
    fun `extractSearchQuery google q param decodes`() {
        assertEquals("bbc porn", ContentFilterMatcher.extractSearchQuery("https://www.google.com/search?q=bbc%20porn"))
    }

    @Test
    fun `extractSearchQuery plus decodes to space`() {
        assertEquals("some terms", ContentFilterMatcher.extractSearchQuery("https://www.google.com/search?q=some+terms"))
    }

    @Test
    fun `extractSearchQuery ddg q param`() {
        assertEquals("cats", ContentFilterMatcher.extractSearchQuery("https://duckduckgo.com/?q=cats"))
    }

    @Test
    fun `extractSearchQuery bing q param`() {
        assertEquals("dogs", ContentFilterMatcher.extractSearchQuery("https://www.bing.com/search?q=dogs"))
    }

    @Test
    fun `extractSearchQuery yandex text param`() {
        assertEquals("weather", ContentFilterMatcher.extractSearchQuery("https://yandex.com/search/?text=weather"))
    }

    @Test
    fun `extractSearchQuery baidu wd param`() {
        assertEquals("news", ContentFilterMatcher.extractSearchQuery("https://www.baidu.com/s?wd=news"))
    }

    @Test
    fun `extractSearchQuery amazon k param`() {
        assertEquals("usb cable", ContentFilterMatcher.extractSearchQuery("https://www.amazon.com/s?k=usb+cable"))
    }

    @Test
    fun `extractSearchQuery youtube search_query param`() {
        assertEquals("music", ContentFilterMatcher.extractSearchQuery("https://www.youtube.com/results?search_query=music"))
    }

    @Test
    fun `extractSearchQuery path style search`() {
        assertEquals("some terms", ContentFilterMatcher.extractSearchQuery("https://example.com/search/some+terms"))
    }

    @Test
    fun `extractSearchQuery path style s`() {
        assertEquals("query here", ContentFilterMatcher.extractSearchQuery("https://example.com/s/query%20here"))
    }

    @Test
    fun `extractSearchQuery lowercases result`() {
        assertEquals("bbc news", ContentFilterMatcher.extractSearchQuery("https://www.google.com/search?q=BBC+News"))
    }

    @Test
    fun `extractSearchQuery non-search URL returns null`() {
        assertNull(ContentFilterMatcher.extractSearchQuery("https://news.site/article"))
    }

    @Test
    fun `extractSearchQuery internal scheme returns null`() {
        assertNull(ContentFilterMatcher.extractSearchQuery("chrome://settings?q=bbc"))
    }

    @Test
    fun `extractSearchQuery blank returns null`() {
        assertNull(ContentFilterMatcher.extractSearchQuery("   "))
    }

    // ---- matchesQueryKeyword ----

    private val ambiguous = ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS

    @Test
    fun `matchesQueryKeyword single term`() {
        assertTrue(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=femdom", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword multi word query with slang`() {
        assertTrue(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=femdom+stories", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword host only never inspected`() {
        // A host that merely contains a listed token has no query -> host is NEVER inspected -> false.
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://femdom.example.com/news", ambiguous))
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://www.fetish.example.co.uk", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword whole word guard`() {
        // "fetishism" / "afetish" as query terms contain "fetish" as a substring but not a whole word.
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=fetishism", ambiguous))
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=afetish", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword legit query with the word of a kept token still matches`() {
        // Intended aggressive-opt-in tradeoff: a legit multi-word query containing the WHOLE
        // WORD of a kept borderline token ("bbw candle sale" = Bath & Body Works) WILL return
        // true. This is the accepted cost of the opt-in strict-keyword mode; the host is never
        // inspected, but the query is. Tokens whose dominant meaning is innocent were removed
        // instead (see the issue #78 corpus below).
        assertTrue(ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=bbw+candle+sale", ambiguous))
    }

    @Test
    fun `matchesQueryKeyword non-search URL returns false`() {
        assertFalse(ContentFilterMatcher.matchesQueryKeyword("https://news.site/article", ambiguous))
    }

    @Test
    fun `issue 78 removals did not gut the ambiguous list`() {
        // Each kept token must still block as a whole-word query under strict mode.
        for (token in ambiguous) {
            assertTrue(
                "$token should still match as a whole-word query",
                ContentFilterMatcher.matchesQueryKeyword("https://www.google.com/search?q=$token", ambiguous)
            )
        }
        // An explicit search that merely contained a removed token still blocks via the default layer.
        assertTrue(ContentFilterMatcher.matchesKeyword("https://www.google.com/search?q=bbc+porn", keywords))
    }
}
