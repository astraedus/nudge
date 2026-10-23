/**
 * Full-URL regex filters, in the RE2 ∩ JavaScript subset. PURE.
 *
 * These strings are compiled by the network layer (`background/dnr.ts`, as DNR
 * `regexFilter`s) AND tested by content scripts (as `RegExp`s) for in-SPA navigation, which
 * DNR cannot see. They live in core so both layers build from ONE string: a URL the network
 * layer lets through but the in-page gate refuses (or the reverse) is the classic
 * half-enforced feature, and two copies of a pattern are how that happens.
 *
 * Anchored `^...$` so a DNR `regexSubstitution` of `\0` is the whole original URL.
 */

import { normalizeToBaseDomain } from './domainMatcher';

/** Escape a literal for embedding in a RE2 / JavaScript pattern. */
export function escapeForRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Match `domain` and every subdomain of it, over http(s), for the whole URL. */
export function domainRegexFilter(domain: string): string {
  const base = escapeForRegex(normalizeToBaseDomain(domain));
  return `^https?://([^/:@?#]*\\.)?${base}(?::[0-9]+)?(?:[/?#].*)?$`;
}

/**
 * The same, narrowed to one PATH pattern (still every subdomain). The path patterns come
 * from the platform registry and from `core/youtubeAccess.ts`, both written in the shared
 * RE2 ∩ JavaScript subset.
 */
export function gateRegexFilter(domain: string, pathPattern: string): string {
  const base = escapeForRegex(normalizeToBaseDomain(domain));
  return `^https?://([^/:@?#]*\\.)?${base}(?::[0-9]+)?${pathPattern}(?:[?#].*)?$`;
}

/**
 * ONE exact host, every path. No subdomain wildcard.
 *
 * Exists for `studio.youtube.com`: the site and gate filters above deliberately match
 * every subdomain (a rule for youtube.com has to catch m.youtube.com too), so carving out
 * one subdomain needs a filter that names that host and nothing else, never one that a
 * `studio.` prefix on some other label could also satisfy.
 */
export function exactHostRegexFilter(host: string): string {
  return `^https?://${escapeForRegex(host.toLowerCase())}(?::[0-9]+)?(?:[/?#].*)?$`;
}

/**
 * Test a URL against one of these filters the way DNR does.
 *
 * DNR's `isUrlFilterCaseSensitive` defaults to FALSE, so the network layer matches these
 * case-insensitively; the in-page check has to agree or `/@Veritasium` would be allowed by
 * one layer and refused by the other.
 */
export function urlMatchesFilter(url: string, filter: string): boolean {
  try {
    return new RegExp(filter, 'i').test(url);
  } catch {
    return false;
  }
}
