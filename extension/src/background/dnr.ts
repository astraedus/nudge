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
 *
 * ── The priority ladder (all five rungs in one place, because they only make sense together)
 *
 *   1  site block          per-site redirect to the interstitial
 *   2  pause grant         temporary access earned by completing a Delay/Breathing pause
 *   3  Lights Off block    the global catch-all redirect (+ the STRICT sub_frame block)
 *   4  Lights Off allow    the lockdown's allow-list
 *   5  emergency grant     the daily 2-minute Escape Hatch
 *
 * Lights Off sits ABOVE the per-site rules because it OVERRIDES them, and above the pause
 * grant because a 10-minute grant earned at 21:58 must not punch a hole through a lockdown
 * that starts at 22:00. The Escape Hatch sits above Lights Off because Anti's locked decision
 * is that the rationed 2-minute valve stays usable during a lockdown (a rationed escape beats
 * a catastrophic lock; turning on the Commitment Lock is how you remove it). Without rung 5
 * the Escape Hatch button would render during Lights Off and silently do nothing.
 *
 * Chrome's documented tie-break for equal priorities is `allow`/`allowAllRequests` > `block` >
 * `upgradeScheme` > `redirect`, so the allow rungs would win even at parity — the explicit
 * numbers just make the intent readable rather than relying on that.
 */

import { extractDomain, normalizeToBaseDomain } from '../core/domainMatcher';
import { resolveLightsOff } from '../core/lightsOff';
import type { NudgeSettings } from '../core/settingsSchema';

/** Redirect rules occupy 1..N; session allow rules start well above them. */
const BLOCK_RULE_ID_BASE = 1;
const ALLOW_RULE_ID_BASE = 10_000;

/**
 * Lights Off gets its own DYNAMIC id range, far clear of the per-site block rules, so a
 * user with a lot of sites can never collide with it.
 */
export const LIGHTS_OFF_RULE_ID_BASE = 20_000;
const LIGHTS_OFF_CATCH_ALL_ID = LIGHTS_OFF_RULE_ID_BASE;
const LIGHTS_OFF_SUBFRAME_BLOCK_ID = LIGHTS_OFF_RULE_ID_BASE + 1;
const LIGHTS_OFF_ALLOW_ID_BASE = LIGHTS_OFF_RULE_ID_BASE + 100;
const LIGHTS_OFF_ALLOW_ALL_ID_BASE = LIGHTS_OFF_RULE_ID_BASE + 1_100;

/**
 * Cap on the allow-list, so the two per-domain id sub-ranges above can never overlap.
 * Far beyond any real use (the cost is 1 or 2 rules per entry against a 30,000 dynamic-rule
 * budget) — it exists so the id arithmetic is provably safe rather than probably safe.
 */
export const LIGHTS_OFF_MAX_ALLOWED_DOMAINS = 1_000;

const BLOCK_PRIORITY = 1;
/** Strictly greater than BLOCK_PRIORITY so a temporary grant always wins. */
const ALLOW_PRIORITY = 2;
/** Above both, so a lockdown overrides per-site rules AND an in-flight pause grant. */
const LIGHTS_OFF_BLOCK_PRIORITY = 3;
const LIGHTS_OFF_ALLOW_PRIORITY = 4;
/** Above everything: the Escape Hatch is the one grant that outranks Lights Off. */
const EMERGENCY_ALLOW_PRIORITY = 5;

/**
 * Every http(s) main-frame navigation, anchored so `\0` is the whole URL.
 *
 * `.*` rather than a host pattern because the whole point is that Lights Off does not know
 * or care what the site is — the allow-list, at a higher priority, is what carves out the
 * exceptions. Non-http(s) schemes never match, which is what makes the extension's own pages,
 * `chrome://`, `about:` and `file://` exempt BY CONSTRUCTION rather than by an allow-rule
 * someone could get wrong (design §3a).
 */
const LIGHTS_OFF_CATCH_ALL_REGEX = '^https?://.*$';

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
 * The per-site redirect rules.
 *
 * `main_frame` only — blocking subresources would break unrelated sites that merely embed
 * something from a blocked domain, and the product blocks *browsing*, not requests.
 */
function compileSiteRules(settings: NudgeSettings): chrome.declarativeNetRequest.Rule[] {
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

/**
 * The rules that implement an ACTIVE Lights Off window. Empty when no window is active.
 *
 * ONE catch-all redirect plus one allow rule per allowed domain — the whole inversion, in
 * 1 + W rules. The alternative (enumerating what to block) is not expressible: the set of
 * sites that exist is unbounded, which is precisely why per-site rules leak and this does not.
 *
 * STRICT additionally blocks `sub_frame` and upgrades each allow to `allowAllRequests`
 * (which, per the DNR reference, exempts "all requests within a frame hierarchy, including
 * the frame request itself") so an allowed page's own embeds keep working while everything
 * else's are cut. The plain `allow` is kept alongside it so the main-frame behaviour is
 * byte-identical in both strictness modes and cannot regress if `allowAllRequests` ever
 * surprises us.
 */
export function compileLightsOffRules(
  settings: NudgeSettings,
  now: Date,
): chrome.declarativeNetRequest.Rule[] {
  const state = resolveLightsOff(settings, now);
  if (!state.active) return [];

  const rules: chrome.declarativeNetRequest.Rule[] = [
    {
      id: LIGHTS_OFF_CATCH_ALL_ID,
      priority: LIGHTS_OFF_BLOCK_PRIORITY,
      action: {
        type: 'redirect' as const,
        redirect: { regexSubstitution: blockedPageSubstitution() },
      },
      condition: {
        regexFilter: LIGHTS_OFF_CATCH_ALL_REGEX,
        resourceTypes: ['main_frame' as const],
      },
    },
  ];

  if (state.strictness === 'STRICT') {
    rules.push({
      id: LIGHTS_OFF_SUBFRAME_BLOCK_ID,
      priority: LIGHTS_OFF_BLOCK_PRIORITY,
      // `block`, not `redirect`: an interstitial rendered inside a 300px ad slot is worse
      // than the frame simply not loading.
      action: { type: 'block' as const },
      condition: {
        regexFilter: LIGHTS_OFF_CATCH_ALL_REGEX,
        resourceTypes: ['sub_frame' as const],
      },
    });
  }

  state.allowedDomains
    .slice(0, LIGHTS_OFF_MAX_ALLOWED_DOMAINS)
    .forEach((domain, index) => {
      rules.push({
        id: LIGHTS_OFF_ALLOW_ID_BASE + index,
        priority: LIGHTS_OFF_ALLOW_PRIORITY,
        action: { type: 'allow' as const },
        condition: {
          regexFilter: domainRegexFilter(domain),
          resourceTypes: ['main_frame' as const],
        },
      });
      if (state.strictness === 'STRICT') {
        rules.push({
          id: LIGHTS_OFF_ALLOW_ALL_ID_BASE + index,
          priority: LIGHTS_OFF_ALLOW_PRIORITY,
          action: { type: 'allowAllRequests' as const },
          condition: {
            regexFilter: domainRegexFilter(domain),
            // allowAllRequests accepts ONLY these two resource types (DNR reference).
            resourceTypes: ['main_frame' as const, 'sub_frame' as const],
          },
        });
      }
    });

  return rules;
}

/**
 * Compile settings into the full dynamic rule set.
 *
 * `now` is a parameter because Lights Off is the FIRST feature whose rule PRESENCE depends on
 * wall-clock time rather than on settings alone. Everything else here is a pure function of
 * settings, so it used to be safe to recompile only on a settings change; a time-gated rule
 * additionally needs the boundary alarms and the heartbeat backstop (see alarmsHub).
 */
export function compileRules(
  settings: NudgeSettings,
  now: Date = new Date(),
): chrome.declarativeNetRequest.Rule[] {
  return [...compileSiteRules(settings), ...compileLightsOffRules(settings, now)];
}

/** Replace the entire dynamic rule set with the one implied by `settings` at `now`. */
export async function applyRules(
  settings: NudgeSettings,
  now: Date = new Date(),
): Promise<void> {
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: existing.map((rule) => rule.id),
    addRules: compileRules(settings, now),
  });
}

/**
 * Re-derive the Lights Off rules and rewrite DNR only when reality disagrees.
 *
 * This is the self-healing backstop for the one thing alarms cannot promise: a boundary
 * crossed while the browser was asleep, or an alarm starved by a busy machine. It is called
 * every heartbeat (1 minute), so it MUST be a no-op in the common case — dynamic rules are
 * persisted to disk, and rewriting them 1,440 times a day for nothing is exactly the kind of
 * cost that gets an extension a reputation.
 *
 * Compares PRESENCE and COUNT rather than content: content is a pure function of settings,
 * and a settings change already forces a full `applyRules`. Returns true when it recompiled,
 * so callers can decide whether follow-up work (badge refresh) is worth doing.
 */
export async function reconcileLightsOff(
  settings: NudgeSettings,
  now: Date = new Date(),
): Promise<boolean> {
  const desired = compileLightsOffRules(settings, now);
  const installed = (await chrome.declarativeNetRequest.getDynamicRules()).filter(
    (rule) => rule.id >= LIGHTS_OFF_RULE_ID_BASE,
  );
  if (installed.length === desired.length) return false;
  await applyRules(settings, now);
  return true;
}

/**
 * A live temporary grant, as the network layer needs to see it.
 *
 * The TIER is what decides the rule's priority, and therefore whether the grant survives a
 * Lights Off lockdown — see the priority ladder at the top of this file.
 */
export interface TempAllowGrant {
  domain: string;
  tier: 'PAUSE' | 'EMERGENCY';
}

/**
 * Rewrite the session allow-rules so exactly the grants in `grants` are permitted.
 * Called on every grant and every expiry, so the rule set is always derived from state
 * rather than incrementally patched (no drift after a service-worker restart).
 */
export async function applyTempAllows(grants: readonly TempAllowGrant[]): Promise<void> {
  const existing = await chrome.declarativeNetRequest.getSessionRules();

  // One rule per domain, deterministically ordered so ids are stable across calls. When both
  // tiers somehow name the same domain the EMERGENCY one wins — it is the stronger grant, and
  // silently downgrading it would break the Escape Hatch during a lockdown.
  const byDomain = new Map<string, TempAllowGrant['tier']>();
  for (const grant of grants) {
    const domain = normalizeToBaseDomain(grant.domain);
    if (grant.tier === 'EMERGENCY' || !byDomain.has(domain)) byDomain.set(domain, grant.tier);
  }

  const addRules: chrome.declarativeNetRequest.Rule[] = [...byDomain.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([domain, tier], index) =>
      tier === 'EMERGENCY'
        ? {
            id: ALLOW_RULE_ID_BASE + index,
            priority: EMERGENCY_ALLOW_PRIORITY,
            // The whole frame hierarchy, so the 2-minute window is actually usable even
            // under STRICT (where a plain `allow` would leave the page's iframes blocked).
            action: { type: 'allowAllRequests' as const },
            condition: {
              regexFilter: domainRegexFilter(domain),
              resourceTypes: ['main_frame' as const, 'sub_frame' as const],
            },
          }
        : {
            id: ALLOW_RULE_ID_BASE + index,
            priority: ALLOW_PRIORITY,
            action: { type: 'allow' as const },
            condition: {
              regexFilter: domainRegexFilter(domain),
              resourceTypes: ['main_frame' as const],
            },
          },
    );

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
