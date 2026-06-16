package com.astraedus.nudge.domain

/**
 * Pure Kotlin utility for extracting domains from URL bar text and matching
 * them against rule-defined web domains. No Android dependencies.
 */
object WebDomainMatcher {

    private val INTERNAL_SCHEMES = setOf("chrome", "about", "file", "data", "javascript")

    /**
     * Extract the base domain from URL bar text.
     *
     * Handles:
     * - Full URLs: "https://www.instagram.com/reels" -> "instagram.com"
     * - Bare domains: "instagram.com/p/abc" -> "instagram.com"
     * - Subdomains: "m.youtube.com" -> "youtube.com"
     * - Chrome shows just domain: "instagram.com" -> "instagram.com"
     *
     * Returns null for:
     * - Empty/blank input
     * - Internal URLs (chrome://, about://, etc.)
     * - Unrecognizable input
     */
    fun extractDomain(urlBarText: String): String? {
        val trimmed = urlBarText.trim()
        if (trimmed.isBlank()) return null

        // Check for internal schemes
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep > 0) {
            val scheme = trimmed.substring(0, schemeSep).lowercase()
            if (scheme in INTERNAL_SCHEMES) return null
        }

        // Strip protocol if present
        val withoutProtocol = if (schemeSep > 0) {
            trimmed.substring(schemeSep + 3)
        } else {
            trimmed
        }

        // Strip path, query, fragment
        val hostPart = withoutProtocol.split('/').firstOrNull()?.split('?')?.firstOrNull()
            ?.split('#')?.firstOrNull() ?: return null

        // Strip port
        val host = hostPart.split(':').firstOrNull()?.lowercase() ?: return null

        if (host.isBlank() || !host.contains('.')) return null

        // Normalize: strip common subdomains to get base domain
        return normalizeToBaseDomain(host)
    }

    /**
     * Check if the URL bar text matches any of the comma-separated web domains.
     *
     * Matching rules:
     * - Exact domain match (after normalization)
     * - Subdomain match: "www.instagram.com" matches rule "instagram.com"
     */
    fun matches(urlBarText: String, webDomains: String): Boolean {
        val extracted = extractDomain(urlBarText) ?: return false
        val domains = webDomains.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }

        return domains.any { ruleDomain ->
            val normalizedRule = normalizeToBaseDomain(ruleDomain)
            extracted == normalizedRule
        }
    }

    /**
     * Normalize a host to its base domain by stripping common subdomain prefixes.
     * "www.instagram.com" -> "instagram.com"
     * "m.youtube.com" -> "youtube.com"
     * "instagram.com" -> "instagram.com"
     */
    fun normalizeToBaseDomain(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host

        // Strip known subdomain prefixes (www, m, mobile, etc.)
        val knownSubdomains = setOf("www", "m", "mobile", "l", "lm")
        return if (parts.first() in knownSubdomains && parts.size > 2) {
            parts.drop(1).joinToString(".")
        } else {
            // For multi-part subdomains not in known list, keep as-is but also
            // check if the last N parts form the base domain
            host
        }
    }
}
