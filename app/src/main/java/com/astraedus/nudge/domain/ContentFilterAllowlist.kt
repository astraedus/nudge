package com.astraedus.nudge.domain

/**
 * The user's own exceptions to the content filter's STRICT keyword layer ("Allowed words & sites",
 * issue #78). Pure Kotlin, JVM-testable.
 *
 * ## What an entry rescues, and what it never can
 *
 * An entry only ever excuses a hit from [ContentFilterMatcher.matchesQueryKeyword], the opt-in
 * whole-word match of [ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS] inside a search query. That
 * layer is the one that blocks innocent searches by design ("cnc machine" in #78, "bbc news"), so it
 * is the one a user needs a way out of.
 *
 * It never touches the two hard layers:
 * - the curated domain list ([ContentFilterMatcher.matchesDomain]). Every entry there is an
 *   individually justified, purpose-built site, and a wrong one is a curation bug fixed for every
 *   user in the asset (`ContentFilterAssetTest`), not something one user patches locally;
 * - the high-signal keyword substrings ([ContentFilterMatcher.DEFAULT_KEYWORDS]).
 *
 * Keeping it that narrow keeps the feature explainable in one line ("exceptions to strict keyword
 * matching"), means typing a site's name into the list can never switch the filter off for it, and
 * means the editor never acts as an oracle for what the built-in list contains.
 *
 * ## Matching
 *
 * Every entry is a case-insensitive WHOLE-WORD phrase. A keyword hit in the query is excused only
 * when its whole span lies inside an occurrence of some entry, so allowing "cnc" lets "cnc machine"
 * through while "cnc bbw" still blocks on "bbw". Coverage is the UNION of every entry's matches in
 * the original query, which makes the list monotonic: adding an entry can only allow more, removing
 * one can only block more. That property is what lets Strict Mode gate additions and leave
 * removals free ([com.astraedus.nudge.domain.lock.ContentFilterAllowlistWeakening]); a
 * mask-and-rescan design would break it (a later entry eating half of an earlier one).
 *
 * An entry that looks like a site ("cnc-shop.com", or a pasted URL) is ALSO a domain: searches on
 * that host or any subdomain of it skip the strict layer entirely.
 *
 * Construction is cheap and the regexes are built lazily, so callers on the URL-bar path build one
 * per evaluation only when strict matching is on.
 */
class ContentFilterAllowlist(entries: Collection<String>) {

    /** The canonical entries this list was built from (invalid input dropped, duplicates merged). */
    val entries: Set<String> = entries.mapNotNullTo(LinkedHashSet()) { normalize(it) }

    private val domains: Set<String> by lazy { this.entries.filterTo(HashSet()) { isDomainEntry(it) } }

    private val phrases: List<Regex> by lazy { this.entries.map { phraseRegex(it) } }

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * True when the URL's host is an allowed site or a subdomain of one. "shop.example.com" is
     * covered by "example.com"; "badexample.com" is not (a distinct registrable name).
     */
    fun coversHost(urlBarText: String): Boolean {
        if (entries.isEmpty()) return false
        val host = WebDomainMatcher.extractDomain(urlBarText) ?: return false
        var candidate = host
        while (true) {
            if (candidate in domains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /**
     * True when the character range [hit] of [query] lies entirely inside one entry's occurrence.
     * [query] must be the same lowercased string the hit was found in.
     */
    fun excuses(query: String, hit: IntRange): Boolean =
        phrases.any { phrase ->
            phrase.findAll(query).any { it.range.first <= hit.first && hit.last <= it.range.last }
        }

    companion object {
        /** An empty list: no exceptions. The fail-closed value. */
        val EMPTY = ContentFilterAllowlist(emptyList())

        /** Longest entry accepted. A search phrase or a host name; nothing legitimate is longer. */
        const val MAX_ENTRY_LENGTH = 64

        /** Most entries kept. Bounds the per-evaluation regex work on the URL-bar path. */
        const val MAX_ENTRIES = 100

        private val WHITESPACE = Regex("""\s+""")
        private val DOMAIN_CHARS = Regex("""[a-z0-9.-]+""")
        private val HAS_SCHEME = Regex("""^[a-z][a-z0-9+.-]*://""")

        /**
         * Canonical form of one user-typed entry, or null when it cannot be an entry.
         *
         * - Lowercased, trimmed, internal whitespace collapsed to one space.
         * - A single token that parses as a host (or a pasted URL) becomes that host, with the
         *   scheme, path, port and a leading `www.`/`m.` stripped: "https://www.Shop.com/x" ->
         *   "shop.com". That is the same normalisation [WebDomainMatcher] applies to the URL bar, so
         *   an entry and the page it names compare equal.
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
         * capped at [MAX_ENTRIES] (first ones win), sorted so the stored and exported forms are
         * stable.
         */
        fun canonicalize(raw: Collection<String>): List<String> =
            raw.mapNotNullTo(LinkedHashSet()) { normalize(it) }.take(MAX_ENTRIES).sorted()

        /** True when [entry] (already normalised) names a site rather than a word. */
        fun isDomainEntry(entry: String): Boolean =
            ' ' !in entry && '.' in entry && DOMAIN_CHARS.matches(entry) &&
                entry.split('.').none { it.isEmpty() }

        private fun asDomain(collapsed: String): String? {
            if (' ' in collapsed) return null
            val looksLikeUrl = HAS_SCHEME.containsMatchIn(collapsed)
            if (!looksLikeUrl && '.' !in collapsed) return null
            val host = WebDomainMatcher.extractDomain(collapsed) ?: return null
            return host.takeIf { isDomainEntry(it) }
        }

        /** Whole-word, whitespace-tolerant phrase match. Word chars mirror the matcher's `\b`. */
        private fun phraseRegex(entry: String): Regex {
            val body = entry.split(' ').joinToString("""\s+""") { Regex.escape(it) }
            return Regex("""(?<![\p{L}\p{N}_])$body(?![\p{L}\p{N}_])""")
        }
    }
}
