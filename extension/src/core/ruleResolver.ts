/**
 * Bridges stored settings (`SiteRule`) to engine input (`ActiveRule`). PURE.
 *
 * This is where the "Scheduled Override" semantics live: inside an active schedule window
 * the scheduled mode+delay REPLACE the rule's default behavior; outside it, the rule's
 * "Default Behavior" applies. The daily limit is a property of the site, so it applies in
 * both cases.
 *
 * Because the schedule is resolved here, the `ActiveRule` handed to the engine carries no
 * schedule of its own — the engine's own schedule filter then trivially passes. (The engine
 * keeps that filter so it remains a faithful port of the Kotlin original, which is fed
 * pre-mirrored scheduled rows.)
 */

import { appliedBlockMode, siteRuleAppliesNow } from './applies';
import type { SiteRule } from './settingsSchema';
import type { ActiveRule } from './types';

/** All enabled rules whose domain matches `domain` (already normalized). */
export function rulesForDomain(
  rules: readonly SiteRule[],
  domain: string,
): SiteRule[] {
  return rules.filter((rule) => rule.enabled && rule.domain === domain);
}

/**
 * Resolve one stored rule into the rule in effect at `now`, or `null` when it is not in
 * force — an ALLOW rule still inside its daily budget, or a rule whose schedule has put it
 * in an ALLOW window.
 *
 * Returning null (rather than an ActiveRule the engine would then have to reject) is what
 * keeps the ENGINE INVARIANT true: the engine only ever receives rules that must produce a
 * BLOCK, so "some rule applied but the verdict was ALLOW" remains impossible by
 * construction rather than by an extra branch someone could forget to add.
 *
 * `usageMs` is a REQUIRED parameter with no default. An ALLOW rule's whole behaviour turns
 * on it, so a default of 0 would silently mean "budget never exhausted" at any call site
 * that forgot to pass it — a limit-only rule that quietly never blocks is precisely the
 * failure this feature exists to avoid.
 */
export function resolveRule(rule: SiteRule, now: Date, usageMs: number): ActiveRule | null {
  const verdict = siteRuleAppliesNow(rule, usageMs, now);
  const mode = appliedBlockMode(verdict);
  if (mode === null) return null;

  return {
    mode,
    delaySeconds: verdict.delaySeconds,
    dailyLimitMinutes: rule.dailyLimitMinutes,
    enabled: true,
    // The schedule has already been applied by `siteRuleAppliesNow`, so the engine's own
    // schedule filter trivially passes. (It stays in the engine because the engine is a
    // faithful port of the Kotlin original, which is fed pre-mirrored scheduled rows.)
    scheduleDays: null,
    scheduleStartMinute: null,
    scheduleEndMinute: null,
    ruleName: rule.domain,
  };
}

/** Resolve every in-force rule matching `domain` into engine input. */
export function resolveActiveRules(
  rules: readonly SiteRule[],
  domain: string,
  now: Date,
  usageMs: number,
): ActiveRule[] {
  return rulesForDomain(rules, domain)
    .map((rule) => resolveRule(rule, now, usageMs))
    .filter((rule): rule is ActiveRule => rule !== null);
}
