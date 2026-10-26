package com.astraedus.nudge.domain

/**
 * The user's own "Blocked words & sites" for the content filter: the mirror of
 * [ContentFilterAllowlist], and the answer to a category the shipped asset does not cover (crypto,
 * gambling, one specific forum) without waiting for an app update.
 *
 * ## Matching
 *
 * A word or phrase is a case-insensitive RAW SUBSTRING of the URL bar text, the same rule
 * [ContentFilterMatcher.DEFAULT_KEYWORDS] uses. Over-blocking is the accepted cost: `sex` catches
 * `sussex.ac.uk`, and the fix is an entry on the allow list, not cleverer matching. That is
 * deliberate. Missing the site the user meant to stop is the failure that matters here.
 *
 * A site entry covers that host and its subdomains, the same shape as a curated domain entry.
 *
 * ## What can take it back
 *
 * [ContentFilterAllowlist] excuses this list too: a page on an allowed host skips it entirely
 * (paths and searches included), and a blocked word whose every occurrence sits inside an allowed
 * phrase is excused. This is the correction path, and it has to reach this far: block `sex`, allow
 * `sussex.ac.uk`, and that university is usable, its sex-education pages included.
 *
 * The reach is bounded. The two HARD layers stay out of it, so the allow list still cannot switch
 * off a curated domain or [ContentFilterMatcher.DEFAULT_KEYWORDS] on that host, and adding an allow
 * entry is itself a weakening change gated by Strict Mode
 * ([com.astraedus.nudge.domain.lock.ContentFilterAllowlistWeakening]).
 *
 * ## Cost
 *
 * A user-typed word has no length floor, so no precomputed regex is built here: matching is a
 * substring scan over one URL bar string for each non-site entry. Both lists are capped at
 * [ContentFilterUserList.MAX_ENTRIES], and both are only constructed while the content filter is on.
 *
 * No Android imports, JVM-testable.
 */
class ContentFilterBlocklist(entries: Collection<String>) {

    /** The canonical entries this list was built from (invalid input dropped, duplicates merged). */
    val entries: Set<String> = entries.mapNotNullTo(LinkedHashSet()) {
        ContentFilterUserList.normalize(it)
    }

    /** Site entries, matched against the URL's host and its parents. */
    internal val domains: Set<String> = ContentFilterUserList.domainEntries(this.entries)

    /** Word and phrase entries, matched as raw substrings of the whole URL bar text. */
    internal val words: List<String> = this.entries.filterNot { ContentFilterUserList.isDomainEntry(it) }

    /**
     * One pattern per word entry, built lazily. A single word is its escaped self (substring
     * semantics, no boundaries). A phrase joins its words with the same `\s`/`+`/`%20` separator
     * [ContentFilterAllowlist] uses, so "crypto gambling" matches `?q=crypto+gambling` in a raw URL
     * and the same phrase after decoding.
     */
    internal val wordPatterns: List<Regex> by lazy { words.map { phraseRegex(it) } }

    val isEmpty: Boolean get() = entries.isEmpty()

    companion object {
        /** An empty list: nothing user-blocked. The fail-open-to-no-block value. */
        val EMPTY = ContentFilterBlocklist(emptyList())

        private const val SEPARATOR = """(?:\s|\+|%20)+"""

        private fun phraseRegex(entry: String): Regex {
            val body = entry.split(' ').joinToString(SEPARATOR) { Regex.escape(it) }
            return Regex(body)
        }
    }
}
