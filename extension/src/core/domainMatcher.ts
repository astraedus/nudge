/**
 * Domain extraction + matching. PURE.
 *
 * Direct port of Android's `domain/WebDomainMatcher.kt` (same normalization rules and
 * the same known-subdomain set) so a rule for "youtube.com" behaves identically on both
 * platforms. Extended only where the browser sees things Android's URL-bar reader never
 * did: extension/internal schemes.
 */

/** Schemes that can never be a blockable site. */
const INTERNAL_SCHEMES = new Set([
  'chrome',
  'chrome-extension',
  'chrome-search',
  'chrome-untrusted',
  'about',
  'edge',
  'moz-extension',
  'file',
  'data',
  'blob',
  'javascript',
  'view-source',
  'devtools',
]);

/**
 * Subdomains stripped when reducing a host to its base domain, so a rule on
 * "youtube.com" also covers "www.youtube.com" and "m.youtube.com" (Android parity).
 */
const KNOWN_SUBDOMAINS = new Set(['www', 'm', 'mobile', 'l', 'lm']);

/**
 * Reduce a host to its base domain by stripping a single known subdomain prefix.
 * "www.instagram.com" -> "instagram.com"; "m.youtube.com" -> "youtube.com";
 * "instagram.com" -> "instagram.com"; "foo.bar.example.com" -> unchanged.
 */
export function normalizeToBaseDomain(host: string): string {
  const lower = host.trim().toLowerCase();
  const parts = lower.split('.');
  if (parts.length <= 2) return lower;
  if (KNOWN_SUBDOMAINS.has(parts[0]!)) {
    return parts.slice(1).join('.');
  }
  return lower;
}

/**
 * Extract the base domain from a URL or bare domain string.
 *
 * Returns null for blank input, internal/extension schemes, and anything without a dot
 * (a bare hostname like "localhost" is not a blockable site).
 */
export function extractDomain(url: string): string | null {
  const trimmed = url.trim();
  if (trimmed === '') return null;

  const schemeSep = trimmed.indexOf('://');
  if (schemeSep > 0) {
    const scheme = trimmed.slice(0, schemeSep).toLowerCase();
    if (INTERNAL_SCHEMES.has(scheme)) return null;
  } else {
    // Scheme-relative forms without "://" (e.g. "about:blank", "javascript:void(0)").
    const colon = trimmed.indexOf(':');
    if (colon > 0 && INTERNAL_SCHEMES.has(trimmed.slice(0, colon).toLowerCase())) {
      return null;
    }
  }

  const withoutProtocol = schemeSep > 0 ? trimmed.slice(schemeSep + 3) : trimmed;

  // Strip userinfo ("user:pass@host") before splitting on path separators.
  const afterUserinfo = withoutProtocol.includes('@')
    ? withoutProtocol.slice(withoutProtocol.indexOf('@') + 1)
    : withoutProtocol;

  const hostPart = afterUserinfo.split('/')[0]!.split('?')[0]!.split('#')[0]!;
  const host = hostPart.split(':')[0]!.toLowerCase();

  if (host === '' || !host.includes('.')) return null;
  // Reject anything with characters a hostname can't contain — guards against a
  // malformed string being treated as a site.
  if (!/^[a-z0-9.-]+$/.test(host)) return null;

  return normalizeToBaseDomain(host);
}

/**
 * True when `url` belongs to `ruleDomain` — exact base-domain match after normalizing
 * both sides, so "https://www.youtube.com/feed" matches a rule on "youtube.com".
 */
export function matchesDomain(url: string, ruleDomain: string): boolean {
  const extracted = extractDomain(url);
  if (extracted === null) return false;
  const normalizedRule = normalizeToBaseDomain(ruleDomain);
  if (normalizedRule === '') return false;
  return extracted === normalizedRule;
}

/**
 * The DNR `urlFilter` for a rule domain. `||domain.com^` is the standard
 * "this domain and all its subdomains" anchored pattern, so www./m./any subdomain
 * are covered by ONE rule (matching `normalizeToBaseDomain`'s intent).
 */
export function dnrUrlFilter(ruleDomain: string): string {
  return `||${normalizeToBaseDomain(ruleDomain)}^`;
}

/**
 * Normalize whatever the user typed in "add a site" into a storable rule domain.
 * Accepts "youtube.com", "https://www.youtube.com/feed", "m.youtube.com".
 * Returns null when the input can't be read as a site.
 */
export function normalizeUserInput(input: string): string | null {
  const direct = extractDomain(input);
  if (direct !== null) return direct;
  // A user may type a bare host with a path but no scheme and no dot survives —
  // nothing more to recover, treat as invalid.
  return null;
}

/**
 * Normalize one Lights Off allow-list entry.
 *
 * Uses the ordinary site normalizer first, then falls back to accepting a DOTLESS bare host
 * ("localhost", an intranet name). That fallback exists only because Lights Off is a GLOBAL
 * catch-all: `http://localhost:3000` gets redirected like everything else, and
 * `normalizeUserInput` deliberately rejects dotless hosts as "not a site". Without the
 * fallback a dev server or intranet host would be both unreachable AND un-whitelistable —
 * a trap rather than a choice, which is the one thing a lockdown must never be.
 *
 * An entry that matches nothing is harmless (it only ever grants access), so leniency here
 * is the safe direction.
 */
export function normalizeAllowedDomain(input: string): string | null {
  const direct = normalizeUserInput(input);
  if (direct !== null) return direct;

  const withoutScheme = input.trim().toLowerCase().replace(/^[a-z][a-z0-9+.-]*:\/\//, '');
  const bare = withoutScheme.split('/')[0]!.split('?')[0]!.split('#')[0]!.split(':')[0]!;
  // Dotless only: anything WITH a dot already had its shot at `extractDomain` above, and
  // failing that it is malformed rather than a bare hostname.
  return /^[a-z0-9-]+$/.test(bare) ? bare : null;
}
