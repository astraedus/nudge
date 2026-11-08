/**
 * THE predicate: "is this rule in force right now?" PURE.
 *
 * Before v0.2 the answer was trivially "yes, if the rule is enabled" — every rule blocked
 * in some mode, so the existence of a rule and the enforcement of a rule were the same
 * fact. The Allow mode breaks that: an ALLOW rule exists (to carry a daily limit, a
 * grayscale toggle or feature gates) without necessarily blocking anything, and it starts
 * blocking the moment its budget runs out.
 *
 * That is one question with at least four consumers — DNR compilation, the engine's rule
 * resolver, the popup status line and the toolbar badge — and if any one of them answers
 * it differently the user gets a contradiction they cannot explain: a block page for a
 * site the popup calls "Allowed", or a badge counting down a budget the network layer is
 * already enforcing. So it lives here once, and everything calls it.
 *
 * The engine invariant survives untouched precisely because this filter runs BEFORE
 * `blockEngine.evaluate`: an ALLOW rule under budget is simply never handed to the engine,
 * so the engine still has the property that a non-empty rule list always yields a BLOCK.
 * (Adding an ALLOW branch inside `evaluate` instead would have broken it — see the ENGINE
 * INVARIANT comment there for why that costs an infinite redirect loop rather than a
 * harmless no-op.)
 */

import { isOverBudget, isOverCount } from './budgets';
import { isScheduleActiveAt } from './scheduleEvaluator';
import type {
  GateSetting,
  ScheduleChannelOverride,
  ScheduleOverride,
  SiteRule,
  YoutubeFeatureSettings,
} from './settingsSchema';
import { isBlockMode, type BlockMode, type SiteMode } from './types';

/**
 * Why a rule is or is not in force. Carried alongside the boolean so callers can explain
 * themselves to the user ("Allowed · 12m left" vs "Blocked · limit reached") instead of
 * re-deriving the reason and drifting from it.
 */
export type AppliesReason =
  /** The rule is switched off. */
  | 'disabled'
  /** The resolved mode at this moment is a block mode. */
  | 'mode-blocks'
  /** The mode allows, but the daily TIME budget is spent. */
  | 'limit-exhausted'
  /** The mode allows, but the daily ITEM COUNT for this surface is spent (v0.3). */
  | 'count-exhausted'
  /** In force in no way right now. */
  | 'allowed';

export interface AppliesResult {
  applies: boolean;
  reason: AppliesReason;
  /** The mode in effect at `now`, schedule already applied. */
  mode: SiteMode;
  /** The delay in effect at `now`, schedule already applied. */
  delaySeconds: number;
}

/**
 * The rule's mode and delay at `now`, with the Scheduled Override applied.
 *
 * Both directions are legal since v0.2: a Hard Block rule that becomes Allow inside a
 * window, and — the case users kept asking for and v0.1 could not express — an Allow rule
 * that becomes a Hard Block inside a window ("block only during work hours").
 */
export function resolveSiteMode(
  rule: SiteRule,
  now: Date,
): { mode: SiteMode; delaySeconds: number } {
  const schedule = activeSchedule(rule, now);
  return schedule !== null
    ? { mode: schedule.mode, delaySeconds: schedule.delaySeconds }
    : { mode: rule.mode, delaySeconds: rule.delaySeconds };
}

/** Whether the rule's schedule is switched on and `now` is inside its window. */
export function isScheduleInForce(rule: SiteRule, now: Date): boolean {
  return activeSchedule(rule, now) !== null;
}

/** The rule's schedule when it is switched on and `now` is inside its window, else null. */
function activeSchedule(rule: SiteRule, now: Date): ScheduleOverride | null {
  const schedule = rule.schedule;
  if (schedule === null || !schedule.enabled) return null;
  const inWindow = isScheduleActiveAt(
    { days: schedule.days, startMinute: schedule.startMinute, endMinute: schedule.endMinute },
    now,
  );
  return inWindow ? schedule : null;
}

/**
 * The rule's YouTube settings at `now`, with the schedule window's channel list applied.
 *
 * THE one place a scheduled channel list (v0.8) takes effect. Inside an active window whose
 * `channels` is set, its mode, list and subscriptions toggle replace the rule's own; every
 * other field (the block mode for a disallowed channel, autoplay, creator mode, the synced
 * subscriptions) stays the rule's. Outside the window, or with "same as default" (null),
 * this returns the rule's own settings unchanged.
 *
 * Every enforcement consumer reads the list through this: DNR compile, the site config the
 * content scripts get, the limit exemption for whitelisted videos and the popup status. A
 * consumer that read `rule.features.youtube` raw would enforce the default list while the
 * page showed the window's, which is the contradiction `resolveSiteMode` exists to prevent
 * for the site mode. The dashboard editor is the one place that edits the raw fields.
 *
 * Undefined when the rule has no YouTube settings at all.
 */
export function resolveYoutubeFeatures(
  rule: SiteRule,
  now: Date,
): YoutubeFeatureSettings | undefined {
  const youtube = rule.features?.youtube;
  if (youtube === undefined) return undefined;
  return withChannelOverride(youtube, activeSchedule(rule, now)?.channels ?? null);
}

/**
 * `youtube` with a window's channel list in place of its own; `youtube` itself for null
 * ("same as default"). The one substitution, shared by `resolveYoutubeFeatures` and the
 * Commitment Lock's "what does the window allow" comparison, so the two cannot disagree on
 * which fields a window overrides.
 */
export function withChannelOverride(
  youtube: YoutubeFeatureSettings,
  override: ScheduleChannelOverride | null,
): YoutubeFeatureSettings {
  if (override === null) return youtube;
  return {
    ...youtube,
    channelMode: override.channelMode,
    channels: override.channels,
    allowSubscriptions: override.allowSubscriptions,
  };
}

/**
 * Whether `rule` is in force right now, given today's usage of its domain.
 *
 * "In force" means exactly: the network layer must redirect this domain, and the engine
 * must return a BLOCK for it. Nothing else should be deciding that.
 */
export function siteRuleAppliesNow(
  rule: SiteRule,
  usageMs: number,
  now: Date,
): AppliesResult {
  const { mode, delaySeconds } = resolveSiteMode(rule, now);

  if (!rule.enabled) {
    return { applies: false, reason: 'disabled', mode, delaySeconds };
  }
  if (isBlockMode(mode)) {
    return { applies: true, reason: 'mode-blocks', mode, delaySeconds };
  }
  if (isOverBudget(rule.dailyLimitMinutes, usageMs)) {
    return { applies: true, reason: 'limit-exhausted', mode, delaySeconds };
  }
  return { applies: false, reason: 'allowed', mode, delaySeconds };
}

/**
 * The same question for one feature gate, given that surface's own usage today.
 *
 * A gate has no schedule of its own (it inherits the site's rhythm by living inside the
 * site rule) but it does have the OFF/budget split: a gate can be set to OFF ("don't gate
 * this surface") and still carry a daily budget, which is the "unlimited Shorts until
 * you've spent 10 minutes on them" shape. So OFF alone is not the same as "not in force".
 *
 * `usageCount` is the surface's ITEM count today (v0.3) and is a REQUIRED parameter with no
 * default, for the same reason `resolveRule`'s `usageMs` is: a default of 0 would silently
 * mean "the count is never spent" at any call site that forgot to pass it, and a count-only
 * gate that quietly never blocks is precisely the failure this feature exists to avoid.
 * Making it required turns "did every consumer get updated" into a compile error rather
 * than a grep somebody has to remember to run.
 */
export function gateAppliesNow(
  gate: GateSetting,
  usageMs: number,
  usageCount: number,
): AppliesResult {
  // BOTH budget axes are checked BEFORE the mode, and that order is load-bearing.
  //
  // An exhausted budget is unconditional by nature (there is nothing left to wait for
  // today), so it resolves to a Hard Block exactly as it does for a site rule. Asking the
  // mode first meant a DELAY gate whose budget was already spent answered 'mode-blocks'
  // with a real pause here, while the block page's engine independently escalated the same
  // gate to HARD_BLOCK: two layers describing one surface differently, so the in-page
  // overlay offered a countdown that bought access the block page would have refused.
  // Checking the caps first makes the one predicate answer for both, and it is the
  // stronger answer, never the weaker one.
  if (isOverBudget(gate.dailyLimitMinutes, usageMs)) {
    return { applies: true, reason: 'limit-exhausted', mode: 'HARD_BLOCK', delaySeconds: 0 };
  }
  // Minutes and count are INDEPENDENT: either, both or neither may be set, and the gate
  // applies once EITHER is spent. Minutes are reported first only so one reason has to win
  // when both are spent; the verdict is identical either way.
  if (isOverCount(gate.dailyLimitCount, usageCount)) {
    return { applies: true, reason: 'count-exhausted', mode: 'HARD_BLOCK', delaySeconds: 0 };
  }
  if (gate.mode !== 'OFF') {
    return {
      applies: true,
      reason: 'mode-blocks',
      mode: gate.mode,
      delaySeconds: gate.delaySeconds,
    };
  }
  return { applies: false, reason: 'allowed', mode: 'ALLOW', delaySeconds: gate.delaySeconds };
}

/**
 * The block mode an applicable verdict resolves to.
 *
 * The only way an ALLOW rule can be applicable is an exhausted budget, and an exhausted
 * budget cannot be waited out before midnight — so it is a Hard Block, matching what the
 * engine independently decides for a budget-exceeded rule (and what Android does).
 * Returns null for a verdict that does not apply, so a caller cannot accidentally turn a
 * non-applicable rule into a block by reading this field.
 */
export function appliedBlockMode(verdict: AppliesResult): BlockMode | null {
  if (!verdict.applies) return null;
  return isBlockMode(verdict.mode) ? verdict.mode : 'HARD_BLOCK';
}
