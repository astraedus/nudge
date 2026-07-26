/**
 * declarativeNetRequest rule compiler.
 *
 * Blocking model (ext-08 fixed decisions):
 *  - DNR handles whole-site, full-page-load blocking. Content scripts handle in-SPA
 *    navigation (DNR cannot see it).
 *  - EVERY enabled rule compiles to a *redirect* to blocked.html — Hard Block, Delay and
 *    Breathing all begin with the interstitial. The block page then asks the engine which
 *    of the three to render, so the network layer stays mode-agnostic and only has to
 *    change when the rule SET changes, not when a mode does.
 *  - Temporary access after a completed pause is a SESSION allow-rule at a higher priority.
 *    Session rules are wiped on browser restart, which is exactly what we want: a crash can
 *    never leave a site permanently unlocked (ext-01 §1).
 *
 * The original URL is carried to the block page via `regexSubstitution` with `\0` (the
 * entire matched text) — verified against the DNR reference. `regexSubstitution` requires
 * `regexFilter`, and the pattern is anchored `^...$` so `\0` is the WHOLE url rather than
 * just its prefix.
 */

import { extractDomain, normalizeToBaseDomain } from '../core/domainMatcher';
import type { NudgeSettings } from '../core/settingsSchema';

/** Redirect rules occupy 1..N; session allow rules start well above them. */
const BLOCK_RULE_ID_BASE = 1;
const ALLOW_RULE_ID_BASE = 10_000;

const BLOCK_PRIORITY = 1;
/** Strictly greater than BLOCK_PRIORITY so a temporary grant always wins. */
const ALLOW_PRIORITY = 2;

/** Escape a domain for embedding in a RE2 pattern. */
function escapeForRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Match `domain` and every subdomain of it, over http(s), for the whole URL.
 *
 * Anchored at both ends so the entire URL is the match — `\0` in the substitution then
 * yields the full original URL for the block page's `target`.
 */
export function domainRegexFilter(domain: string): string {
  const base = escapeForRegex(normalizeToBaseDomain(domain));
  return `^https?://([^/:@?#]*\\.)?${base}(?::[0-9]+)?(?:[/?#].*)?$`;
}

/** The blocked.html URL, with the original URL appended verbatim as the last query param. */
function blockedPageSubstitution(): string {
  // `target` is deliberately LAST and unencoded: the original URL may contain '?' and '&',
  // so the block page reads everything after the first "?target=" rather than parsing
  // query params. (regexSubstitution cannot URL-encode.)
  return `${chrome.runtime.getURL('blocked.html')}?target=\\0`;
}

/** Distinct base domains that currently have an enabled rule. */
export function blockedDomains(settings: NudgeSettings): string[] {
  if (!settings.globalEnabled) return [];
  const domains = new Set<string>();
  for (const rule of settings.rules) {
    if (rule.enabled) domains.add(normalizeToBaseDomain(rule.domain));
  }
  return [...domains].sort();
}

/**
 * Compile settings into the full dynamic rule set.
 *
 * `main_frame` only — blocking subresources would break unrelated sites that merely embed
 * something from a blocked domain, and the product blocks *browsing*, not requests.
 */
export function compileRules(settings: NudgeSettings): chrome.declarativeNetRequest.Rule[] {
  return blockedDomains(settings).map((domain, index) => ({
    id: BLOCK_RULE_ID_BASE + index,
    priority: BLOCK_PRIORITY,
    action: {
      type: 'redirect' as const,
      redirect: { regexSubstitution: blockedPageSubstitution() },
    },
    condition: {
      regexFilter: domainRegexFilter(domain),
      resourceTypes: ['main_frame' as const],
    },
  }));
}

/** Replace the entire dynamic rule set with the one implied by `settings`. */
export async function applyRules(settings: NudgeSettings): Promise<void> {
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: existing.map((rule) => rule.id),
    addRules: compileRules(settings),
  });
}

/** Stable session-rule id for a domain, derived so re-grants replace rather than stack. */
function allowRuleId(domain: string, allDomains: string[]): number {
  const index = allDomains.indexOf(domain);
  return ALLOW_RULE_ID_BASE + (index >= 0 ? index : allDomains.length);
}

/**
 * Rewrite the session allow-rules so exactly the domains in `domains` are permitted.
 * Called on every grant and every expiry, so the rule set is always derived from state
 * rather than incrementally patched (no drift after a service-worker restart).
 */
export async function applyTempAllows(domains: string[]): Promise<void> {
  const existing = await chrome.declarativeNetRequest.getSessionRules();
  const sorted = [...new Set(domains.map(normalizeToBaseDomain))].sort();
  const addRules: chrome.declarativeNetRequest.Rule[] = sorted.map((domain) => ({
    id: allowRuleId(domain, sorted),
    priority: ALLOW_PRIORITY,
    action: { type: 'allow' as const },
    condition: {
      regexFilter: domainRegexFilter(domain),
      resourceTypes: ['main_frame' as const],
    },
  }));
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: existing.map((rule) => rule.id),
    addRules,
  });
}

/**
 * Send every open tab currently on `domain` to the block page.
 *
 * This is the "budget flip mid-browsing" path: a page already open when the daily limit is
 * crossed would otherwise stay readable until the next navigation, because DNR only sees
 * requests. Fires inside the accounting step, so the flip is immediate rather than up to a
 * heartbeat late (ext-01 §4).
 */
export async function redirectOpenTabs(domain: string): Promise<void> {
  const base = normalizeToBaseDomain(domain);
  const tabs = await chrome.tabs.query({ url: ['http://*/*', 'https://*/*'] });
  const blockedUrl = chrome.runtime.getURL('blocked.html');
  await Promise.all(
    tabs.map(async (tab) => {
      if (tab.id === undefined || tab.url === undefined) return;
      if (extractDomain(tab.url) !== base) return;
      try {
        await chrome.tabs.update(tab.id, { url: `${blockedUrl}?target=${tab.url}` });
      } catch {
        // Tab closed or is otherwise not updatable — nothing to do.
      }
    }),
  );
}
