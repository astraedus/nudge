/**
 * declarativeNetRequest rule compiler.
 *
 * Blocking model (ext-08 fixed decisions, extended by the v0.2 per-site design):
 *  - DNR handles whole-site and whole-SURFACE, full-page-load blocking. Content scripts
 *    handle in-SPA navigation (DNR cannot see it).
 *  - An in-force rule compiles to a *redirect* to blocked.html — Hard Block, Delay and
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
 *
 * ## What changed in v0.2: the rule set is a function of (settings, usage, now)
 *
 * In v0.1 "there is a rule for this domain" and "this domain is blocked" were the same
 * fact, so the compiler only had to read `settings`. Both halves of that broke:
 *
 *  - An ALLOW rule exists to carry a limit, a grayscale toggle or feature gates, and is in
 *    force ONLY once its daily budget is spent. So the compiler needs today's usage, and it
 *    needs to recompile when a budget is crossed rather than only when settings change.
 *  - A schedule can flip a rule in BOTH directions now, so the compiler needs `now` and the
 *    worker needs an alarm at the next schedule boundary (see `alarmsHub`).
 *
 * Whether a rule is in force is NOT re-derived here: it is exactly one question with four
 * consumers, and it lives in `core/applies.ts`. A second copy of that predicate in the
 * network layer is how you get a block page for a site the popup calls "Allowed".
 *
 * ## Priorities
 *
 * `allow` outranks `redirect` only when its priority is strictly higher (vendors refuse to
 * standardize same-priority ordering), so every priority is set explicitly:
 *
 *   4  session temp-allow      a completed pause opens the origin, gates included
 *   3  YouTube channel allow   the "block YouTube except these channels" carve-out
 *   2  gate redirect           /shorts/, /reels/, /home … — above the site rule so a gated
 *                              surface is still caught on an otherwise ALLOWed site
 *   1  site redirect           the whole domain
 *
 * Temp-allow sits at the TOP deliberately. A Delay pause taken on a gate surface grants a
 * per-origin allow (ext-08: grants are per-origin, not per-tab); if a gate redirect
 * outranked that grant, completing the pause would bounce the user straight back into the
 * block page — the infinite redirect loop the ENGINE INVARIANT note in blockEngine.ts
 * exists to prevent. The cost is that a site-level pause also opens that site's gated
 * surfaces for the grant window, which is the documented per-origin semantics.
 */

import { gateAppliesNow, siteRuleAppliesNow } from '../core/applies';
import {
  extractDomain,
  hostMatchesRuleDomain,
  normalizeToBaseDomain,
} from '../core/domainMatcher';
import { platformById, type GateId } from '../core/platforms';
import type { NudgeSettings, SiteRule } from '../core/settingsSchema';
import { domainRegexFilter, gateRegexFilter } from '../core/urlFilters';
import {
  allYoutubeAllowFilters,
  youtubeAllowFilters,
  youtubeWhitelistPaths,
} from '../core/youtubeAccess';
import { surfaceKey } from '../core/surfaceKeys';
import type { UsageSnapshot } from '../core/protocol';
import { todayUsageSnapshot } from './storage';

/**
 * Today's active milliseconds per usage key — plain domains AND `domain#gate` surface
 * buckets (see `core/surfaceKeys.ts`). A missing key means zero.
 */
export type UsageByKey = Readonly<Record<string, number>>;

/** Re-exported so every caller of the compiler reads one definition of "today's usage". */
export type { UsageSnapshot };

/** Id ranges, one per kind, so a rule's kind is legible from its id while debugging. */
const SITE_RULE_ID_BASE = 1;
const GATE_RULE_ID_BASE = 1_000;
const CHANNEL_ALLOW_RULE_ID_BASE = 5_000;
const SESSION_ALLOW_RULE_ID_BASE = 10_000;

/**
 * The priority ladder, lowest first. DNR resolves `allow` over `redirect` only when
 * priorities are equal, and no vendor will commit to same-priority ordering, so every rung
 * is explicit.
 *
 *   1 SITE_PRIORITY          the whole-site redirect
 *   2 GATE_PRIORITY          one surface of that site, above the site so a surface can be
 *                            blocked while the site itself is Allowed
 *   3 CHANNEL_ALLOW_PRIORITY the YouTube whitelist carve-out, above both so an allowed
 *                            channel's /watch survives the site redirect — but NOT above
 *                            a gate, so /shorts/ stays blocked for allowed channels too
 *   4 TEMP_ALLOW_PRIORITY    a completed pause, above everything
 *
 * Rung 4 sitting above rung 2 is deliberate and is the one that needs justifying: a pause
 * completed ON a gate surface must not bounce straight back into the gate's own redirect,
 * which is an infinite loop rather than a no-op (see the ENGINE INVARIANT note in
 * blockEngine.ts). The cost is that a site-level grant also opens that site's gate surfaces
 * at the NETWORK layer for the grant window — acceptable only because every gate surface
 * lives on a platform that has a content script, and that script gates the surface in-page
 * on full loads as well as SPA hops. `GET_SITE_CONFIG` must therefore never resolve a gate
 * to OFF just because a site temp-allow is live; the gate's own mode stands, and only a
 * pause completed on the gate surface satisfies the gate.
 */
export const SITE_PRIORITY = 1;
export const GATE_PRIORITY = 2;
export const CHANNEL_ALLOW_PRIORITY = 3;
/** Strictly greater than every redirect priority so a temporary grant always wins. */
export const TEMP_ALLOW_PRIORITY = 4;

// The filter builders live in core so the YouTube content script builds its in-SPA check
// from the SAME strings (see core/urlFilters.ts); re-exported for existing importers.
export { domainRegexFilter, gateRegexFilter };

/** The blocked.html URL, with the original URL appended verbatim as the last query param. */
function blockedPageSubstitution(): string {
  // `target` is deliberately LAST and unencoded: the original URL may contain '?' and '&',
  // so the block page reads everything after the first "?target=" rather than parsing
  // query params. (regexSubstitution cannot URL-encode.)
  return `${chrome.runtime.getURL('blocked.html')}?target=\\0`;
}

function redirectAction(): chrome.declarativeNetRequest.RuleAction {
  return {
    type: 'redirect' as const,
    redirect: { regexSubstitution: blockedPageSubstitution() },
  };
}

/** `main_frame` only — see compileRules. */
function mainFrameCondition(
  regexFilter: string,
): chrome.declarativeNetRequest.RuleCondition {
  return { regexFilter, resourceTypes: ['main_frame' as const] };
}

/** The usage key for a rule's site bucket. */
function siteUsageKey(rule: SiteRule): string {
  return normalizeToBaseDomain(rule.domain);
}

/**
 * Distinct base domains whose SITE rule is in force right now.
 *
 * Note the difference from v0.1's `blockedDomains`: a rule existing is no longer enough. An
 * ALLOW rule under its budget contributes nothing here, which is the entire point of Allow
 * mode — the site opens normally until the limit runs out.
 */
export function redirectedDomains(
  settings: NudgeSettings,
  usage: UsageSnapshot,
  now: Date,
): string[] {
  if (!settings.globalEnabled) return [];
  const domains = new Set<string>();
  for (const rule of settings.rules) {
    const key = siteUsageKey(rule);
    // Site rules have no count axis: "one item" is a property of a gate's stream, not of a
    // whole site, so only the minute budget can put a site rule in force.
    if (siteRuleAppliesNow(rule, usage.ms[key] ?? 0, now).applies) domains.add(key);
  }
  return [...domains].sort();
}

/** One compiled gate redirect, before ids are assigned. */
interface GateRedirect {
  domain: string;
  gateId: GateId;
  regexFilter: string;
}

/**
 * Every gate surface that is in force right now, across every enabled rule.
 *
 * A gate is in force when its own mode is not OFF, or when its own daily budget is spent —
 * `gateAppliesNow` owns that distinction, because "don't gate Shorts, but stop me after ten
 * minutes of them" is OFF **plus** a budget and must still produce a redirect once spent.
 *
 * No `now` parameter, unlike its site counterpart: a gate has no schedule of its own. It
 * inherits the site's rhythm by living inside the site rule, so the only thing that can
 * change a gate's verdict between two moments is its usage.
 */
function gateRedirects(settings: NudgeSettings, usage: UsageSnapshot): GateRedirect[] {
  if (!settings.globalEnabled) return [];
  const redirects: GateRedirect[] = [];

  const ordered = [...settings.rules].sort((a, b) => a.domain.localeCompare(b.domain));
  for (const rule of ordered) {
    if (!rule.enabled || rule.features === null) continue;
    const domain = siteUsageKey(rule);
    // Registry order, not stored-object order: the stored map is JSON and its key order is
    // not a contract, while the registry's is the order the UI renders too.
    for (const definition of platformById(rule.features.platform).gates) {
      const gate = rule.features.gates[definition.id];
      if (gate === undefined) continue;
      const key = surfaceKey(domain, definition.id);
      // Both axes, from the same snapshot: a gate whose COUNT is spent has to compile its
      // redirect exactly as one whose minutes are, or "20 Shorts a day" would be enforced
      // in-page and wide open to a typed URL.
      if (!gateAppliesNow(gate, usage.ms[key] ?? 0, usage.counts[key] ?? 0).applies) continue;
      for (const path of definition.paths) {
        redirects.push({
          domain,
          gateId: definition.id,
          regexFilter: gateRegexFilter(domain, path),
        });
      }
    }
  }
  return redirects;
}

// Moved to core/youtubeAccess.ts (the content script needs the same answer); re-exported
// for existing importers.
export { youtubeWhitelistPaths };

/**
 * The youtube.com carve-outs: "block YouTube by default, allow these channels", plus
 * creator mode's Studio routes.
 *
 * WHICH URLs is not decided here: `core/youtubeAccess.youtubeAllowFilters` owns that, and
 * the YouTube content script reads the same function for in-SPA navigation. This only
 * walks the rules and asks whether each one is in force (and whether its budget is spent).
 */
function channelAllowFilters(
  settings: NudgeSettings,
  usage: UsageSnapshot,
  now: Date,
): string[] {
  if (!settings.globalEnabled) return [];
  const filters = new Set<string>();

  for (const rule of settings.rules) {
    const youtube = rule.features?.youtube;
    if (!rule.enabled || youtube === undefined) continue;
    const domain = siteUsageKey(rule);
    const verdict = siteRuleAppliesNow(rule, usage.ms[domain] ?? 0, now);
    const allowed = youtubeAllowFilters(domain, youtube, {
      siteApplies: verdict.applies,
      limitReached: verdict.reason === 'limit-exhausted',
    });
    for (const filter of allYoutubeAllowFilters(allowed)) filters.add(filter);
  }
  return [...filters].sort();
}

/**
 * Compile settings + today's usage into the full dynamic rule set.
 *
 * `main_frame` only — blocking subresources would break unrelated sites that merely embed
 * something from a blocked domain, and the product blocks *browsing*, not requests.
 *
 * Rule ids are DERIVED from the compiled set (a base per kind plus the index within a
 * deterministically ordered list), never incremented across calls, and the whole dynamic
 * set is replaced on every apply. That is what makes a service-worker teardown mid-update
 * harmless: the next compile reproduces the same ids from the same inputs instead of
 * drifting further from whatever is installed.
 */
/**
 * How many dynamic rules `compileRules` will produce for this state, without building them.
 *
 * Exists for the e2e harness, which has to wait until the worker's rule set actually
 * reflects a settings write and cannot call `compileRules` itself — that builds redirect
 * actions through `chrome.runtime.getURL`, which does not exist in the Playwright process.
 *
 * It is derived from the SAME three functions `compileRules` uses, and
 * `tests/background/dnr.test.ts` pins the two together. That matters because the harness
 * previously waited on "one rule per enabled rule", which quietly stopped being true the
 * moment an Allow rule could compile none and a single gate could compile several — and a
 * wait that is wrong in the permissive direction turns into a race, not a failure.
 */
export function compiledRuleCount(
  settings: NudgeSettings,
  usage: UsageSnapshot,
  now: Date,
): number {
  return (
    redirectedDomains(settings, usage, now).length +
    gateRedirects(settings, usage).length +
    channelAllowFilters(settings, usage, now).length
  );
}

export function compileRules(
  settings: NudgeSettings,
  usage: UsageSnapshot,
  now: Date,
): chrome.declarativeNetRequest.Rule[] {
  const rules: chrome.declarativeNetRequest.Rule[] = [];

  redirectedDomains(settings, usage, now).forEach((domain, index) => {
    rules.push({
      id: SITE_RULE_ID_BASE + index,
      priority: SITE_PRIORITY,
      action: redirectAction(),
      condition: mainFrameCondition(domainRegexFilter(domain)),
    });
  });

  gateRedirects(settings, usage).forEach((gate, index) => {
    rules.push({
      id: GATE_RULE_ID_BASE + index,
      priority: GATE_PRIORITY,
      action: redirectAction(),
      condition: mainFrameCondition(gate.regexFilter),
    });
  });

  channelAllowFilters(settings, usage, now).forEach((regexFilter, index) => {
    rules.push({
      id: CHANNEL_ALLOW_RULE_ID_BASE + index,
      priority: CHANNEL_ALLOW_PRIORITY,
      action: { type: 'allow' as const },
      condition: mainFrameCondition(regexFilter),
    });
  });

  return rules;
}

/**
 * Replace the entire dynamic rule set with the one implied by `settings` right now.
 *
 * Today's usage is read HERE rather than taken as a parameter. Every caller would otherwise
 * have to remember to pass it, and the one that forgot would compile a rule set in which no
 * budget is ever exhausted — a limit-only rule that silently never blocks, which is exactly
 * the failure Allow mode must not have.
 */
export function applyRules(settings: NudgeSettings, now: Date = new Date()): Promise<void> {
  // SERIALIZED. Bootstrap runs on every worker wake, and on a browser start it runs twice
  // (onStartup plus the top-level call) alongside the storage listener, the tracker and the
  // block page's self-heal. Interleaved, two calls read the same `existing` list and the
  // second one's adds can collide on ids the first just installed, failing the whole update
  // and leaving whichever rule set happened to land. One at a time, each reads what the
  // previous one wrote.
  const run = applyQueue.then(async () => {
    const usage = await todayUsageSnapshot(now);
    const existing = await chrome.declarativeNetRequest.getDynamicRules();
    await chrome.declarativeNetRequest.updateDynamicRules({
      removeRuleIds: existing.map((rule) => rule.id),
      addRules: compileRules(settings, usage, now),
    });
  });
  // The queue must survive a failed apply, or one rejected update would wedge every later one.
  applyQueue = run.catch(() => undefined);
  return run;
}

/** Tail of the `applyRules` chain. */
let applyQueue: Promise<void> = Promise.resolve();

/** Stable session-rule id for a domain, derived so re-grants replace rather than stack. */
function allowRuleId(domain: string, allDomains: string[]): number {
  const index = allDomains.indexOf(domain);
  return SESSION_ALLOW_RULE_ID_BASE + (index >= 0 ? index : allDomains.length);
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
    priority: TEMP_ALLOW_PRIORITY,
    action: { type: 'allow' as const },
    condition: mainFrameCondition(domainRegexFilter(domain)),
  }));
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: existing.map((rule) => rule.id),
    addRules,
  });
}

/**
 * Send every open tab currently on `domain` to the block page.
 *
 * This is the "budget flip mid-browsing" path: a page already open when a daily limit is
 * crossed would otherwise stay readable until the next navigation, because DNR only sees
 * requests. Fires inside the accounting step, so the flip is immediate rather than up to a
 * heartbeat late (ext-01 §4).
 *
 * `matchesUrl` narrows it to one feature surface. A spent SHORTS budget must not yank the
 * user off the ordinary YouTube page they are reading — the site itself is still within its
 * own budget, and blocking more than the user asked for is how a feature stops being
 * trusted.
 */
export async function redirectOpenTabs(
  domain: string,
  matchesUrl: (url: string) => boolean = () => true,
): Promise<void> {
  const base = normalizeToBaseDomain(domain);
  const tabs = await chrome.tabs.query({ url: ['http://*/*', 'https://*/*'] });
  const blockedUrl = chrome.runtime.getURL('blocked.html');
  await Promise.all(
    tabs.map(async (tab) => {
      if (tab.id === undefined || tab.url === undefined) return;
      // Subdomain-aware, because the redirect that DNR would do on the next navigation is.
      // An exact compare left the tab the user was ACTUALLY on (en.wikipedia.org) sitting
      // open and readable at the very moment its budget ran out.
      const host = extractDomain(tab.url);
      if (host === null || !hostMatchesRuleDomain(host, base)) return;
      if (!matchesUrl(tab.url)) return;
      try {
        await chrome.tabs.update(tab.id, { url: `${blockedUrl}?target=${tab.url}` });
      } catch {
        // Tab closed or is otherwise not updatable — nothing to do.
      }
    }),
  );
}
