package com.astraedus.nudge.domain

/**
 * Canonicalisation shared by the content filter's two user-owned lists: "Allowed words & sites"
 * ([ContentFilterAllowlist]) and "Blocked words & sites" ([ContentFilterBlocklist]).
 *
 * Both lists accept the same kinds of entry: a word, a whitespace-separated phrase, or a site (a
 * bare host or a pasted URL, stored as its registrable host). One implementation means a form the
 * Settings screen refuses is refused by the backup importer too, and the two caps cannot drift.
 *
 * No Android imports, JVM-testable.
 */
object ContentFilterUserList {

    /** Longest entry accepted. A search phrase or a host name; nothing legitimate is longer. */
    const val MAX_ENTRY_LENGTH = 64

    /** Most entries kept in ONE list. Bounds the per-evaluation work on the URL-bar path. */
    const val MAX_ENTRIES = 100

    private val WHITESPACE = Regex("""\s+""")
    private val DOMAIN_CHARS = Regex("""[a-z0-9.-]+""")
    private val HAS_SCHEME = Regex("""^[a-z][a-z0-9+.-]*://""")

    /**
     * Canonical form of one user-typed entry, or null when it cannot be an entry.
     *
     * - Lowercased, trimmed, internal whitespace collapsed to one space.
     * - A single token that parses as a host (or a pasted URL) becomes that host, with the scheme,
     *   path, port and a leading `www.`/`m.` stripped: "https://www.Shop.com/x" -> "shop.com". That
     *   is the same normalisation [WebDomainMatcher] applies to the URL bar, so an entry and the
     *   page it names compare equal.
     * - Anything else is a word or phrase, kept as typed.
     * - Rejected: blank, longer than [MAX_ENTRY_LENGTH], or carrying no letter or digit.
     */
    fun normalize(raw: String): String? {
        val collapsed = raw.trim().lowercase().replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return null
        val canonical = asDomain(collapsed) ?: collapsed
        if (canonical.length > MAX_ENTRY_LENGTH) return null
        if (canonical.none { it.isLetterOrDigit() }) return null
        return canonical
    }

    /**
     * Canonicalises a whole list the way storage and import need it: normalised, de-duplicated,
     * capped at [MAX_ENTRIES] (first ones win), sorted so the stored and exported forms are stable.
     */
    fun canonicalize(raw: Collection<String>): List<String> =
        raw.mapNotNullTo(LinkedHashSet()) { normalize(it) }.take(MAX_ENTRIES).sorted()

    /** True when [entry] (already normalised) names a site rather than a word. */
    fun isDomainEntry(entry: String): Boolean =
        ' ' !in entry && '.' in entry && DOMAIN_CHARS.matches(entry) &&
            entry.split('.').none { it.isEmpty() }

    /** The site entries in [entries]; words and phrases are excluded. */
    fun domainEntries(entries: Collection<String>): Set<String> =
        entries.filterTo(HashSet()) { isDomainEntry(it) }

    /**
     * True when [host] is one of [domains] or a subdomain of one. "shop.example.com" is covered by
     * "example.com"; "badexample.com" is not (a distinct registrable name).
     */
    fun hostMatches(host: String, domains: Set<String>): Boolean {
        if (domains.isEmpty()) return false
        var candidate = host
        while (true) {
            if (candidate in domains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun asDomain(collapsed: String): String? {
        if (' ' in collapsed) return null
        val looksLikeUrl = HAS_SCHEME.containsMatchIn(collapsed)
        if (!looksLikeUrl && '.' !in collapsed) return null
        val host = WebDomainMatcher.extractDomain(collapsed) ?: return null
        return host.takeIf { isDomainEntry(it) }
    }
}
