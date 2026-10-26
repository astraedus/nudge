package com.astraedus.nudge.data.repository

import android.content.Context
import com.astraedus.nudge.domain.ContentFilterAllowlist
import com.astraedus.nudge.domain.ContentFilterBlocklist
import com.astraedus.nudge.domain.ContentFilterMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small abstraction over the content-filter check so callers (e.g. the use case)
 * can be unit-tested without touching the bundled asset.
 */
interface ContentFilter {
    /**
     * True if [urlBarText] matches the bundled blocklist or a high-signal keyword.
     *
     * @param strictKeywords when true, ALSO matches ambiguous slang keywords found as
     *   whole words in the URL's search query (opt-in; a no-op otherwise).
     * @param allowlist the user's exceptions. They excuse a strict keyword hit and a hit from
     *   [userBlocklist], never the bundled domain list or a high-signal keyword (see
     *   [ContentFilterAllowlist]).
     * @param userBlocklist the user's own blocked words and sites (see [ContentFilterBlocklist]).
     *   Empty by default, which keeps the old call sites honest.
     */
    suspend fun isBlocked(
        urlBarText: String,
        strictKeywords: Boolean,
        allowlist: ContentFilterAllowlist,
        userBlocklist: ContentFilterBlocklist = ContentFilterBlocklist.EMPTY
    ): Boolean
}

/**
 * Loads the bundled blocklist (`assets/content_filter_domains.txt`, a hand-curated
 * few-hundred-entry list of lowercased base domains) into an in-memory set on first
 * use and answers content-filter queries.
 *
 * Loading is:
 *  - lazy (first [isBlocked] call, NOT app/service start),
 *  - off the main thread (Dispatchers.IO),
 *  - guarded by a [Mutex] so concurrent first-callers load exactly once.
 *
 * Blank lines and `#` comment lines are skipped, so the asset can carry its own
 * curation policy inline — which is the whole point after a 274k-entry upstream blob
 * silently blocked virginia.gov, purdue.edu and every site hosted on amazonaws.com.
 */
@Singleton
class ContentFilterRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : ContentFilter {

    @Volatile
    private var blocklist: Set<String>? = null
    private val loadMutex = Mutex()

    override suspend fun isBlocked(
        urlBarText: String,
        strictKeywords: Boolean,
        allowlist: ContentFilterAllowlist,
        userBlocklist: ContentFilterBlocklist
    ): Boolean {
        if (urlBarText.isBlank()) return false
        val list = ensureLoaded()
        return ContentFilterMatcher.isBlocked(
            urlBarText, list, strictKeywords, allowlist, userBlocklist
        )
    }

    private suspend fun ensureLoaded(): Set<String> {
        blocklist?.let { return it }
        return loadMutex.withLock {
            blocklist?.let { return it }
            val loaded = withContext(Dispatchers.IO) { loadFromAssets() }
            blocklist = loaded
            loaded
        }
    }

    private fun loadFromAssets(): Set<String> {
        val set = HashSet<String>(1_024)
        return try {
            context.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
                lines.forEach { line -> parseLine(line)?.let(set::add) }
            }
            set
        } catch (_: Exception) {
            // If the asset is missing/unreadable, fail open to an empty set so the
            // keyword pass still works and we never crash navigation.
            emptySet()
        }
    }

    companion object {
        private const val ASSET_NAME = "content_filter_domains.txt"

        /**
         * One asset line -> a blocklist entry, or null for a line that carries none.
         * Blank lines and `#` comments are skipped so the curated asset can document
         * its own inclusion policy inline.
         *
         * Exposed so the asset-hygiene test parses the shipped file exactly the way the
         * app does — a test with its own parser would be testing the wrong bytes.
         */
        internal fun parseLine(line: String): String? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            return trimmed
        }
    }
}
