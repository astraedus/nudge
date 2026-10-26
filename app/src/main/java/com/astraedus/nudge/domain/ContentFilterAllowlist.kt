package com.astraedus.nudge.domain

/**
 * The user's own exceptions to the content filter ("Allowed words & sites", issue #78). Pure
 * Kotlin, JVM-testable.
 *
 * ## What an entry rescues, and what it never can
 *
 * An entry excuses a hit from [ContentFilterMatcher.matchesQueryKeyword], the opt-in whole-word
 * match of [ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS] inside a search query, and a hit from the
 * user's own [ContentFilterBlocklist]. Those are the layers that block innocent searches by design
 * ("cnc machine" in #78, "bbc news") and the ones a user can over-extend themselves.
 *
 * It never touches the two hard layers:
 * - the curated domain list ([ContentFilterMatcher.matchesDomain]). Every entry there is an
 *   individually justified, purpose-built site, and a wrong one is a curation bug fixed for every
 *   user in the asset (`ContentFilterAssetTest`), not something one user patches locally;
 * - the high-signal keyword substrings ([ContentFilterMatcher.DEFAULT_KEYWORDS]).
 *
 * Keeping it that narrow keeps the feature explainable in one line ("exceptions to the words and
 * sites that would otherwise block"), means typing a site's name into the list can never switch the
 * filter off for it, and means the editor never acts as an oracle for what the built-in list
 * contains.
 *
 * ## Matching
 *
 * Every entry is a case-insensitive WHOLE-WORD phrase. A keyword hit in the query is excused only
 * when its whole span lies inside an occurrence of some entry, so allowing "cnc" lets "cnc machine"
 * through while "cnc bbw" still blocks. Coverage is the UNION of every entry's matches in the
 * original query, which makes the list monotonic: adding an entry can only allow more, removing one
 * can only block more. An allowlist that weakly excused nothing would make removals a bypass, which
 * is why [com.astraedus.nudge.domain.lock.ContentFilterAllowlistWeakening] can gate additions and
 * leave removals free.
 *
 * An entry that looks like a site ("cnc-shop.com", or a pasted URL) is ALSO a domain: searches on
 * that host or any subdomain of it skip the layers this list reaches entirely.
 *
 * Canonicalisation is [ContentFilterUserList]'s, shared with [ContentFilterBlocklist].
 */
class ContentFilterAllowlist(entries: Collection<String>) {

    /** The canonical entries this list was built from (invalid input dropped, duplicates merged). */
    val entries: Set<String> = entries.mapNotNullTo(LinkedHashSet()) {
        ContentFilterUserList.normalize(it)
    }

    private val domains: Set<String> = ContentFilterUserList.domainEntries(this.entries)

    private val phrases: List<Regex> by lazy { this.entries.map { phraseRegex(it) } }

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * True when the URL's host is an allowed site or a subdomain of one. "shop.example.com" is
     * covered by "example.com"; "badexample.com" is not (a distinct registrable name).
     */
    fun coversHost(urlBarText: String): Boolean {
        if (entries.isEmpty()) return false
        val host = WebDomainMatcher.extractDomain(urlBarText) ?: return false
        return ContentFilterUserList.hostMatches(host, domains)
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

        /**
         * Whole-word, whitespace-tolerant phrase match. Word chars mirror the matcher's `\b`.
         *
         * The separators between the phrase's words also accept `+` and `%20`, because the same
         * permit is applied to a decoded search query ([ContentFilterMatcher.extractSearchQuery])
         * and to a raw URL bar string ([ContentFilterMatcher.matchesUserBlocklist]). One regex,
         * both forms, and no index-shifting decode in between.
         */
        private fun phraseRegex(entry: String): Regex {
            val body = entry.split(' ').joinToString(SEPARATOR) { Regex.escape(it) }
            return Regex("""(?<![\p{L}\p{N}_])$body(?![\p{L}\p{N}_])""")
        }

        private const val SEPARATOR = """(?:\s|\+|%20)+"""
    }
}
