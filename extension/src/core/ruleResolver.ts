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

import { isScheduleActiveAt } from './scheduleEvaluator';
import type { SiteRule } from './settingsSchema';
import type { ActiveRule } from './types';

/** All enabled rules whose domain matches `domain` (already normalized). */
export function rulesForDomain(
  rules: readonly SiteRule[],
  domain: string,
): SiteRule[] {
  return rules.filter((rule) => rule.enabled && rule.domain === domain);
}

/** Resolve one stored rule into the rule in effect at `now`. */
export function resolveRule(rule: SiteRule, now: Date): ActiveRule {
  const schedule = rule.schedule;
  const scheduleActive =
    schedule !== null &&
    schedule.enabled &&
    isScheduleActiveAt(
      {
        days: schedule.days,
        startMinute: schedule.startMinute,
        endMinute: schedule.endMinute,
      },
      now,
    );

  return {
    mode: scheduleActive ? schedule.mode : rule.mode,
    delaySeconds: scheduleActive ? schedule.delaySeconds : rule.delaySeconds,
    dailyLimitMinutes: rule.dailyLimitMinutes,
    enabled: true,
    scheduleDays: null,
    scheduleStartMinute: null,
    scheduleEndMinute: null,
    ruleName: rule.domain,
  };
}

/** Resolve every rule matching `domain` into engine input. */
export function resolveActiveRules(
  rules: readonly SiteRule[],
  domain: string,
  now: Date,
): ActiveRule[] {
  return rulesForDomain(rules, domain).map((rule) => resolveRule(rule, now));
}
