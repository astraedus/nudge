package com.astraedus.nudge.domain.web

import com.astraedus.nudge.domain.WebDomainMatcher

/**
 * The synthetic key a blocked website is tracked under.
 *
 * Everything the service keeps per foreground app -- the counter cache, [com.astraedus.nudge.service.InteractionTracker]'s
 * session/baseline/cooldown maps, `AutoKickExecutor.kick` -- is keyed by an opaque `String`, not by
 * a real package. A website therefore needs no parallel machinery: it just needs a key of its own.
 *
 * It must NOT be the browser's package. A kick or a cooldown armed on `com.android.chrome` would
 * lock the whole browser, every site, which is over-blocking of exactly the kind this app must never
 * do; and two blocked domains open in two tabs would share one session. It must not be the rule's
 * app package either, or time on instagram.com would spend the Instagram app's session.
 *
 * The domain half is normalised through [WebDomainMatcher.normalizeToBaseDomain] on the way in, so
 * `www.instagram.com`, `m.instagram.com` and `instagram.com` are ONE session rather than three.
 */
object WebSessionKey {

    const val PREFIX = "web:"

    /** The key for [domain], normalised and lowercased. Blank input yields null. */
    fun forDomain(domain: String): String? {
        val normalized = WebDomainMatcher.normalizeToBaseDomain(domain.trim().lowercase())
        if (normalized.isBlank()) return null
        return PREFIX + normalized
    }

    fun isWebKey(key: String): Boolean = key.startsWith(PREFIX) && key.length > PREFIX.length

    /** The domain a [key] refers to, or null if it is a real package name. */
    fun domainOf(key: String): String? =
        if (isWebKey(key)) key.substring(PREFIX.length) else null
}
