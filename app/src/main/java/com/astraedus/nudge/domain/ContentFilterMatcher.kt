package com.astraedus.nudge.domain

/**
 * Pure Kotlin matcher for the generic content filter. No Android dependencies,
 * fully unit-testable on the JVM.
 *
 * Two independent signals:
 *  - [matchesDomain]: the URL's base domain (and any parent domains) is present
 *    in the bundled blocklist set.
 *  - [matchesKeyword]: a high-signal keyword appears as a substring of the raw
 *    URL text (catches search queries and unknown domains where the term is in
 *    the path/host/query).
 *
 * Both skip browser-internal schemes (chrome://, about://, etc.) via
 * [WebDomainMatcher.extractDomain] / explicit scheme handling.
 */
object ContentFilterMatcher {

    private val INTERNAL_SCHEME_PREFIXES = listOf(
        "chrome://", "about:", "file://", "data:", "javascript:",
        "chrome-native://", "content://"
    )

    /**
     * High-signal explicit tokens used for keyword matching against the raw URL.
     *
     * False-positive reasoning:
     *  - We deliberately AVOID short ambiguous tokens. The classic trap is bare
     *    "sex" (matches "sussex", "essex", "middlesex", "sextant") and "anal"
     *    (matches "analysis", "analog", "canal"). Neither is in this list.
     *  - Every token here is either a coined/brand term (xvideos, xhamster,
     *    redtube, brazzers, pornhub) that has no mainstream-word collision, or a
     *    long compound unlikely to appear inside a benign domain/path
     *    ("camgirl", "escort", "hardcore"). "milf" / "hentai" / "nsfw" / "nude" /
     *    "porn" / "xxx" are strong and effectively never substrings of
     *    legitimate site URLs people browse.
     *  - "xxx" is kept because it is overwhelmingly adult-signalling; the only
     *    benign collisions ("xxxl" sizing) are rare in URLs and acceptable.
     *  - These are SUBSTRING matches on the full URL (host + path + query), so a
     *    search like "google.com/search?q=porn" or an unknown domain
     *    "freepornsite.tld" is caught even when the domain isn't in the list.
     */
    val DEFAULT_KEYWORDS: List<String> = listOf(
        "porn",
        "xxx",
        "nsfw",
        "xvideos",
        "xhamster",
        "redtube",
        "youporn",
        "pornhub",
        "brazzers",
        "onlyfans",
        "hentai",
        "rule34",
        "camgirl",
        "camsoda",
        "chaturbate",
        "escort",
        "milf",
        "hardcore",
        "bukkake",
        "fetish",
        "bdsm",
        "deepthroat",
        "creampie",
        "cumshot",
        "blowjob",
        "handjob",
        "fapello",
        "spankbang",
        "nudez"
    )

    /**
     * True if the URL's base domain (or any of its parent domains) is in the
     * blocklist set. The blocklist contains lowercased base domains.
     *
     * Example: blocklist has "example.com"; "m.cdn.example.com" extracts to
     * a host whose parent-strip reaches "example.com" -> match.
     *
     * Returns false for internal schemes / blank / unparseable input.
     */
    fun matchesDomain(urlBarText: String, blocklist: Set<String>): Boolean {
        if (blocklist.isEmpty()) return false
        val base = WebDomainMatcher.extractDomain(urlBarText) ?: return false
        // Check the extracted base domain, then progressively strip the leftmost
        // label so subdomains of a blocked base also match.
        var candidate = base
        while (candidate.contains('.')) {
            if (candidate in blocklist) return true
            val dot = candidate.indexOf('.')
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    /**
     * True if any keyword appears (case-insensitive substring) in the raw URL
     * text. Skips browser-internal schemes and blank input.
     */
    fun matchesKeyword(urlBarText: String, keywords: List<String>): Boolean {
        val trimmed = urlBarText.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase()
        if (INTERNAL_SCHEME_PREFIXES.any { lower.startsWith(it) }) return false
        return keywords.any { it.isNotBlank() && lower.contains(it.lowercase()) }
    }
}
