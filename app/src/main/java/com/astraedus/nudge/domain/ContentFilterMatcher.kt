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
        "nudez",
        // Additional coined/compound low-collision tokens. Each is a brand name or
        // compound that is effectively never a substring of a benign site URL, so
        // they are safe as raw substring matches (same false-positive rationale as
        // above: no short mainstream-colliding token — deliberately NOT bare "gif").
        "redgifs",
        "xnxx",
        "eporner",
        "tnaflix",
        "youjizz",
        "spankwire",
        "keezmovies",
        "porntrex",
        "hqporner",
        "faphouse",
        "stripchat",
        "bongacams",
        "livejasmin",
        "myfreecams",
        "gangbang",
        "gloryhole",
        "porngif",
        "gifporn",
        "sexgif",
        "nsfwgif",
        "sextube",
        "camwhore",
        "pornstar"
    )

    /**
     * Ambiguous slang tokens that are dangerous as raw substring matches (e.g. "bbc"
     * would block bbc.com news) but genuinely wanted. These are matched ONLY as WHOLE
     * WORDS inside a URL's SEARCH QUERY (never the host), via [matchesQueryKeyword],
     * and the whole feature is gated behind an opt-in pref so the general userbase is
     * unaffected.
     *
     * Curation: only genuinely-wanted-but-ambiguous tokens whose whole-word match in a
     * search query is a strong signal. We deliberately skip tokens too collision-prone
     * even as a whole word (e.g. bare "dp", which appears innocently in countless queries).
     */
    val AMBIGUOUS_QUERY_KEYWORDS: List<String> = listOf(
        "bbc",
        "bwc",
        "bbw",
        "pawg",
        "gilf",
        "dilf",
        "jav",
        "sph",
        "cnc",
        "femdom",
        "cuckold",
        "hotwife"
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

    /**
     * Case-insensitive names of query parameters that commonly carry the user's search
     * terms across mainstream engines (Google/DDG/Bing `q`, YouTube/generic `query`,
     * YouTube `search_query`, Yahoo `p`, Yandex `text`, Baidu `wd`, Amazon `k`, and
     * a couple of other common ones).
     */
    private val SEARCH_QUERY_PARAMS = setOf(
        "q", "query", "search_query", "p", "text", "wd", "k", "kw", "kp"
    )

    /** Path prefixes that carry the search terms directly in the path, e.g. `/search/<terms>`. */
    private val SEARCH_PATH_PREFIXES = listOf("/search/", "/s/")

    /**
     * Extract the decoded, lowercased search terms from a URL bar string, or null if the
     * input does not look like a search URL (or is an internal scheme / blank).
     *
     * A tiny hand-rolled parser — intentionally NOT android.net.Uri — so it stays pure
     * JVM-testable. Handles:
     *  - query params [SEARCH_QUERY_PARAMS] (param names compared case-insensitively);
     *  - path-style search: [SEARCH_PATH_PREFIXES] (`/search/<terms>`, `/s/<terms>`).
     *
     * Decoding is best-effort: `+` and `%20` become spaces and other `%XX` escapes are
     * percent-decoded (a malformed escape is left as-is rather than throwing).
     */
    fun extractSearchQuery(urlBarText: String): String? {
        val trimmed = urlBarText.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase()
        if (INTERNAL_SCHEME_PREFIXES.any { lower.startsWith(it) }) return null

        // Strip scheme so path/query parsing is uniform (reuse the internal-scheme list
        // plus the common web schemes).
        val schemeless = stripScheme(trimmed)

        // 1) Query-string params: everything after the first '?'.
        val qIndex = schemeless.indexOf('?')
        if (qIndex >= 0) {
            val queryString = schemeless.substring(qIndex + 1).substringBefore('#')
            for (pair in queryString.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq)
                if (name.lowercase() in SEARCH_QUERY_PARAMS) {
                    val raw = pair.substring(eq + 1)
                    val decoded = decode(raw)
                    if (decoded.isNotBlank()) return decoded.lowercase()
                }
            }
        }

        // 2) Path-style search: /search/<terms> or /s/<terms>. Compare on the path only
        //    (strip host by finding the first '/', and drop any query/fragment).
        val pathStart = schemeless.indexOf('/')
        if (pathStart >= 0) {
            val path = schemeless.substring(pathStart).substringBefore('?').substringBefore('#')
            val lowerPath = path.lowercase()
            for (prefix in SEARCH_PATH_PREFIXES) {
                if (lowerPath.startsWith(prefix)) {
                    val terms = path.substring(prefix.length).trimEnd('/')
                    val decoded = decode(terms)
                    if (decoded.isNotBlank()) return decoded.lowercase()
                }
            }
        }

        return null
    }

    /**
     * True if any keyword appears as a WHOLE WORD inside the URL's search query (extracted
     * via [extractSearchQuery]). Returns false when the URL is not a search URL — the host
     * is NEVER inspected, so "https://bbc.com/news" does not match. Whole-word matching means
     * "bbc" matches the query "bbc" / "bbc porn" but NOT "clubbc" / "abbcd".
     *
     * Aggressive-opt-in tradeoff: because this matches whole words in the raw query, a legit
     * multi-word news query that happens to contain the word (e.g. "bbc news") WILL match.
     * That is the intended cost of the opt-in strict-keyword mode — see the pref gate.
     */
    fun matchesQueryKeyword(urlBarText: String, keywords: List<String>): Boolean {
        val query = extractSearchQuery(urlBarText) ?: return false
        if (query.isBlank()) return false
        return keywords.any { kw ->
            kw.isNotBlank() && Regex("""\b${Regex.escape(kw.lowercase())}\b""").containsMatchIn(query)
        }
    }

    private val WEB_SCHEME_PREFIXES = listOf("http://", "https://")

    /** Strip a leading scheme (web or internal) so host/path/query parsing is uniform. */
    private fun stripScheme(text: String): String {
        val lower = text.lowercase()
        for (prefix in WEB_SCHEME_PREFIXES + INTERNAL_SCHEME_PREFIXES) {
            if (lower.startsWith(prefix)) return text.substring(prefix.length)
        }
        return text
    }

    /**
     * Best-effort URL-component decode: `+` -> space, `%20` -> space, other `%XX` ->
     * their byte value. A malformed escape is left untouched rather than throwing.
     */
    private fun decode(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '+' -> { sb.append(' '); i++ }
                c == '%' && i + 2 < raw.length -> {
                    val hex = raw.substring(i + 1, i + 3)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 3
                    } else {
                        sb.append(c); i++
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
