package com.astraedus.nudge.data.repository

import android.content.Context
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
 * can be unit-tested without loading the ~274k-entry bundled asset.
 */
interface ContentFilter {
    /**
     * True if [urlBarText] matches the bundled blocklist or a high-signal keyword.
     *
     * @param strictKeywords when true, ALSO matches ambiguous slang keywords found as
     *   whole words in the URL's search query (opt-in; a no-op otherwise).
     */
    suspend fun isBlocked(urlBarText: String, strictKeywords: Boolean): Boolean
}

/**
 * Loads the bundled blocklist (`assets/content_filter_domains.txt`,
 * ~274k newline-separated lowercased base domains) into an in-memory set on
 * first use and answers content-filter queries.
 *
 * Loading is:
 *  - lazy (first [isBlocked] call, NOT app/service start),
 *  - off the main thread (Dispatchers.IO),
 *  - guarded by a [Mutex] so concurrent first-callers load exactly once.
 *
 * Memory: a HashSet<String> of ~274k base domains is acceptable on our low-end
 * target (Pixel 3). We load with a buffered reader and pre-size the set to avoid
 * rehashing. A sorted-array + binary-search would trade a little CPU per lookup
 * for lower per-entry object overhead, but the HashSet keeps lookups O(1) and the
 * footprint is well within budget, so we keep it simple.
 */
@Singleton
class ContentFilterRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : ContentFilter {

    @Volatile
    private var blocklist: Set<String>? = null
    private val loadMutex = Mutex()

    override suspend fun isBlocked(urlBarText: String, strictKeywords: Boolean): Boolean {
        if (urlBarText.isBlank()) return false
        val list = ensureLoaded()
        return ContentFilterMatcher.matchesDomain(urlBarText, list) ||
            ContentFilterMatcher.matchesKeyword(urlBarText, ContentFilterMatcher.DEFAULT_KEYWORDS) ||
            (strictKeywords && ContentFilterMatcher.matchesQueryKeyword(
                urlBarText, ContentFilterMatcher.AMBIGUOUS_QUERY_KEYWORDS
            ))
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
        // Pre-size to avoid rehashing the ~274k entries during load.
        val set = HashSet<String>(400_000)
        return try {
            context.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) set.add(trimmed)
                }
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
    }
}
