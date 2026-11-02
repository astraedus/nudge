package com.astraedus.nudge.data.repository

import com.astraedus.nudge.domain.ContentFilterMatcher
import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hygiene + behaviour gates on the SHIPPED blocklist asset.
 *
 * These exist because the asset used to be a 274,642-domain upstream blob that blocked
 * `virginia.gov`, `purdue.edu`, `rice.edu`, `ku.edu`, `itu.int` and `utwente.nl` outright —
 * and, because [ContentFilterMatcher.matchesDomain] walks parent domains, a single
 * `amazonaws.com` / `cloudfront.net` / `wordpress.com` entry silently blocked every site
 * hosted on them. Unit tests over a hand-written fake blocklist could never catch that:
 * the bug was in the DATA. So this test reads the real file, parses it with the app's own
 * parser, and asserts the invariants no future edit may break.
 */
class ContentFilterAssetTest {

    private val entries: List<String> by lazy {
        assetFile().readLines().mapNotNull { ContentFilterRepository.parseLine(it) }
    }

    private val blocklist: Set<String> by lazy { entries.toSet() }

    private fun assetFile(): File = SourceScan.mainFile("assets/content_filter_domains.txt")

    // ---- Hygiene: what may live in the file at all ----

    @Test
    fun `asset stays small - nobody may paste an upstream blob back in`() {
        // The old blob was 274,642 entries / 4.5MB. The curated list is a few hundred.
        // This ceiling is the durable guard: "improving coverage" by dumping a public
        // blocklist in here fails the build instead of shipping false positives.
        assertTrue("blocklist is suspiciously empty (${entries.size})", entries.size >= 100)
        assertTrue(
            "blocklist has ${entries.size} entries — curated lists stay small; " +
                "if you pasted an upstream blob, read the policy header in the asset",
            entries.size <= 3_000
        )
        assertTrue("asset should stay well under 1MB", assetFile().length() < 1_000_000)
    }

    @Test
    fun `every entry is a lowercase registrable domain`() {
        for (entry in entries) {
            assertEquals("entry must be lowercase: $entry", entry.lowercase(), entry)
            assertTrue("entry must contain a dot: $entry", entry.contains('.'))
            assertFalse("entry must not start or end with a dot: $entry", entry.startsWith(".") || entry.endsWith("."))
            assertFalse("entry must not contain a scheme or path: $entry", entry.contains("/") || entry.contains(":"))
            assertFalse("entry must not contain whitespace: $entry", entry.any { it.isWhitespace() })
            assertTrue(
                "entry has unexpected characters: $entry",
                entry.all { it.isDigit() || it in 'a'..'z' || it == '.' || it == '-' }
            )
        }
    }

    @Test
    fun `no duplicate entries`() {
        val duplicates = entries.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate entries: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `no government or academic domain is ever blocked`() {
        // The exact class of bug that started this: virginia.gov, purdue.edu, rice.edu,
        // ku.edu, metrostate.edu and ohiochristian.edu were all in the shipped blocklist.
        val institutionalSuffixes = listOf(".gov", ".edu", ".mil", ".int", ".ac.uk", ".edu.au", ".ac.nz")
        val offenders = entries.filter { entry ->
            institutionalSuffixes.any { entry.endsWith(it) } ||
                entry.contains(".gov.") || entry.contains(".edu.") || entry.contains(".ac.")
        }
        assertTrue("government/academic domains must never be blocked: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no entry is a public suffix - one would block an entire TLD`() {
        // matchesDomain walks parent domains, so an entry like "co.uk" would block every
        // British site on earth. Same for a bare TLD.
        val publicSuffixes = setOf(
            "co.uk", "org.uk", "me.uk", "ac.uk", "gov.uk",
            "com.au", "net.au", "org.au", "edu.au", "gov.au",
            "co.jp", "ne.jp", "or.jp", "co.nz", "co.za", "co.in", "co.kr",
            "com.br", "com.mx", "com.tr", "com.cn", "com.tw", "com.hk", "com.sg",
            "co.il", "com.ar", "com.co", "com.pl", "com.ua", "com.ru"
        )
        val offenders = entries.filter { it in publicSuffixes || it.count { c -> c == '.' } == 0 }
        assertTrue("entries must be registrable domains, not public suffixes: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no entry collides with the mainstream ALLOWLIST`() {
        val offenders = entries.filter { ContentFilterMatcher.isAllowlisted(it) }
        assertTrue("allowlisted platforms must not be in the blocklist: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no entry is redundant with a broader entry already in the list`() {
        // "example.com" already blocks "sub.example.com" via the parent-strip walk, so a
        // child entry is dead weight and usually a sign of a copy-paste error.
        val redundant = entries.filter { entry ->
            var candidate = entry.substringAfter('.')
            while (candidate.contains('.')) {
                if (candidate in blocklist) return@filter true
                candidate = candidate.substringAfter('.')
            }
            false
        }
        assertTrue("entries already covered by a parent entry: $redundant", redundant.isEmpty())
    }

    @Test
    fun `comment and blank lines are excluded from the blocklist`() {
        // The asset documents its own curation policy in '#' comments. If those ever leaked
        // into the set they would be harmless-but-wrong entries — and, more importantly, it
        // would mean parseLine had stopped doing its job.
        val raw = assetFile().readLines()
        assertTrue("asset should carry its curation policy as comments", raw.any { it.startsWith("#") })
        assertTrue("no comment may reach the blocklist", entries.none { it.startsWith("#") })
        assertTrue("no blank line may reach the blocklist", entries.none { it.isBlank() })
    }

    // ---- Behaviour: the benign corpus must survive BOTH matching layers ----

    /**
     * Mainstream domains a normal person visits. None of these may match via the domain
     * list OR the default keyword list. Deliberately includes every category the old blob
     * got wrong: US state government, universities, CDNs/hosting whose parent entry
     * blocked the entire platform, news, banking, and the "adjacent but not adult" sites
     * (sex education, fan fiction, creator platforms) our curation policy excludes.
     */
    private val benignDomains = listOf(
        // The actual false positives users hit
        "reddit.com", "virginia.gov", "purdue.edu", "rice.edu", "ku.edu",
        "metrostate.edu", "ohiochristian.edu", "itu.int", "utwente.nl", "sagepub.com",
        // Government
        "health.gov.au", "servicesaustralia.gov.au", "ato.gov.au", "my.gov.au",
        "usa.gov", "irs.gov", "cdc.gov", "nasa.gov", "gov.uk", "essex.gov.uk", "sussex.ac.uk",
        // Platforms and CDNs the old blob blocked wholesale via parent-strip
        "amazonaws.com", "s3.amazonaws.com", "cloudfront.net", "wordpress.com",
        "blogspot.com", "myshopify.com", "sourceforge.net", "appspot.com",
        // News and reference
        "bbc.com", "bbc.co.uk", "news.com.au", "abc.net.au", "theguardian.com",
        "nytimes.com", "wikipedia.org", "en.wikipedia.org",
        // Everyday
        "google.com", "youtube.com", "github.com", "stackoverflow.com", "imgur.com",
        "twitter.com", "x.com", "giphy.com", "9gag.com", "newgrounds.com",
        // Banking
        "commbank.com.au", "nab.com.au", "anz.com.au", "chase.com", "paypal.com",
        // Deliberately NOT blocked by curation policy: sex ed, fan fiction, creator
        // platforms, sexual-wellness retail, modelling industry, general marketplaces
        "scarleteen.com", "plannedparenthood.org", "archiveofourown.org", "fanfiction.net",
        "patreon.com", "deviantart.com", "itch.io", "bandcamp.com", "lovehoney.com",
        "modelmayhem.com", "models.com", "dlsite.com", "dmm.com", "match.com", "meetup.com"
    )

    @Test
    fun `no mainstream domain matches the blocklist`() {
        val blocked = benignDomains.filter {
            ContentFilterMatcher.matchesDomain("https://www.$it/", blocklist)
        }
        assertTrue("these mainstream domains must never be blocked: $blocked", blocked.isEmpty())
    }

    @Test
    fun `no mainstream domain matches a default keyword`() {
        // The keyword layer is a raw substring match over the whole URL, so it is just as
        // capable of a false positive as the domain list ("sussex" contains "sex").
        val blocked = benignDomains.filter {
            ContentFilterMatcher.matchesKeyword("https://www.$it/", ContentFilterMatcher.DEFAULT_KEYWORDS)
        }
        assertTrue("these mainstream domains must not trip a keyword: $blocked", blocked.isEmpty())
    }

    // ---- Behaviour: the sites we actually mean to block still block ----

    @Test
    fun `the major adult sites still match`() {
        for (domain in listOf("pornhub.com", "xvideos.com", "xhamster.com", "xnxx.com", "redtube.com")) {
            assertTrue(
                "$domain must be blocked",
                ContentFilterMatcher.matchesDomain("https://www.$domain/", blocklist)
            )
        }
    }

    @Test
    fun `no-signal-token adult sites match via the domain list`() {
        // These are the whole reason the domain list exists: popular adult sites whose
        // names contain none of DEFAULT_KEYWORDS, so the keyword layer cannot see them.
        val noSignalToken = listOf(
            "bangbros.com", "naughtyamerica.com", "realitykings.com", "literotica.com",
            "beeg.com", "motherless.com", "e621.net", "f95zone.to", "erome.com",
            "missav.com", "fetlife.com", "jerkmate.com", "coomer.su", "hitomi.la",
            "imagefap.com", "listcrawler.com", "blacked.com", "kink.com"
        )
        for (domain in noSignalToken) {
            assertTrue("$domain must be in the curated blocklist", domain in blocklist)
            assertTrue(
                "$domain must be blocked by the domain layer",
                ContentFilterMatcher.matchesDomain("https://www.$domain/", blocklist)
            )
            assertFalse(
                "$domain is in the no-signal-token set, so the keyword layer must NOT be " +
                    "what catches it — if this fails the entry is redundant, not wrong",
                ContentFilterMatcher.matchesKeyword("https://www.$domain/", ContentFilterMatcher.DEFAULT_KEYWORDS)
            )
        }
    }

    @Test
    fun `subdomains of a blocked base still match`() {
        assertTrue(ContentFilterMatcher.matchesDomain("https://cdn.media.beeg.com/x.mp4", blocklist))
        assertTrue(ContentFilterMatcher.matchesDomain("m.bangbros.com/videos", blocklist))
    }
}
