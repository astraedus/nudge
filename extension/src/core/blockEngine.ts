/**
 * The block decision engine. PURE.
 *
 * Direct port of Android's `domain/engine/BlockEngine.kt`, preserving its priority order
 * exactly:
 *
 *   unconditional HARD_BLOCK  >  daily budget exceeded (forces HARD_BLOCK)
 *                             >  DELAY  >  BREATHING  >  Allow
 *
 * "Unconditional" means a HARD_BLOCK rule carrying no daily limit — it can never be
 * satisfied by waiting, so it outranks everything. A budget-exceeded rule is escalated to
 * HARD_BLOCK and its name suffixed "(limit reached)" (naming parity with Android).
 *
 * INVARIANT: if ANY rule applies, the verdict is a BLOCK. ALLOW is reserved for "no rule
 * applies here". The browser makes this stricter than it is on Android: DNR has already
 * redirected the navigation by the time the engine runs, so an ALLOW while a rule still
 * applies bounces the user back to the site and straight into the redirect again — an
 * infinite loop, not a no-op. Any new mode or qualifier MUST get its own branch below.
 */

import { isScheduleActiveAt } from './scheduleEvaluator';
import { ALLOW, block, type ActiveRule, type BlockDecision } from './types';

/** Suffix appended to a rule's name when the block is caused by an exhausted budget. */
export const LIMIT_REACHED_SUFFIX = ' (limit reached)';

export function evaluate(
  activeRules: readonly ActiveRule[],
  dailyUsageMs: number,
  now: Date = new Date(),
): BlockDecision {
  const applicable = activeRules.filter(
    (rule) =>
      rule.enabled &&
      isScheduleActiveAt(
        {
          days: rule.scheduleDays,
          startMinute: rule.scheduleStartMinute,
          endMinute: rule.scheduleEndMinute,
        },
        now,
      ),
  );

  if (applicable.length === 0) return ALLOW;

  // The tightest budget among applicable rules drives the remaining-time readout.
  const limits = applicable
    .map((r) => r.dailyLimitMinutes)
    .filter((l): l is number => l !== null);
  const minDailyLimit = limits.length > 0 ? Math.min(...limits) : null;
  const dailyTimeRemainingMs =
    minDailyLimit !== null
      ? Math.max(0, minDailyLimit * 60_000 - dailyUsageMs)
      : null;

  const unconditionalHardBlock = applicable.find(
    (r) => r.mode === 'HARD_BLOCK' && r.dailyLimitMinutes === null,
  );
  if (unconditionalHardBlock) {
    return block({
      mode: 'HARD_BLOCK',
      ruleName: unconditionalHardBlock.ruleName,
      dailyTimeRemainingMs,
      dailyLimitMinutes: minDailyLimit,
    });
  }

  const budgetExceeded = applicable.find(
    (r) => r.dailyLimitMinutes !== null && dailyUsageMs >= r.dailyLimitMinutes * 60_000,
  );
  if (budgetExceeded) {
    return block({
      mode: 'HARD_BLOCK',
      ruleName:
        budgetExceeded.ruleName === null
          ? null
          : `${budgetExceeded.ruleName}${LIMIT_REACHED_SUFFIX}`,
      dailyTimeRemainingMs,
      dailyLimitMinutes: minDailyLimit,
      limitReached: true,
    });
  }

  // A Hard Block rule that ALSO carries a daily limit, while still under that limit.
  //
  // A daily limit is meaningless on a Hard Block — the site is barred outright, so there is
  // no browsing time to budget. Without this branch such a rule matched nothing above (it is
  // not "unconditional", not over budget, and not DELAY/BREATHING) and fell through to
  // ALLOW, while DNR kept redirecting the domain: block page -> ALLOW -> back to the site ->
  // redirect, an infinite loop that also hammered the service worker.
  const hardBlockWithLimit = applicable.find((r) => r.mode === 'HARD_BLOCK');
  if (hardBlockWithLimit) {
    return block({
      mode: 'HARD_BLOCK',
      ruleName: hardBlockWithLimit.ruleName,
      dailyTimeRemainingMs,
      dailyLimitMinutes: minDailyLimit,
    });
  }

  const delayRule = applicable.find((r) => r.mode === 'DELAY');
  if (delayRule) {
    return block({
      mode: 'DELAY',
      delaySeconds: delayRule.delaySeconds,
      ruleName: delayRule.ruleName,
      dailyTimeRemainingMs,
      dailyLimitMinutes: minDailyLimit,
    });
  }

  const breathingRule = applicable.find((r) => r.mode === 'BREATHING');
  if (breathingRule) {
    return block({
      mode: 'BREATHING',
      delaySeconds: breathingRule.delaySeconds,
      ruleName: breathingRule.ruleName,
      dailyTimeRemainingMs,
      dailyLimitMinutes: minDailyLimit,
    });
  }

  // UNREACHABLE for a non-empty `applicable` list, and that is a load-bearing invariant:
  // DNR redirects the domain regardless of mode, so an ALLOW returned while a rule still
  // applies produces an infinite block-page <-> site redirect loop rather than a harmless
  // "no block". Every BlockMode now has a branch above, so the only way here is
  // `applicable.length === 0`. tests/core/blockEngine.test.ts drives the full
  // mode x limit x usage matrix to keep it that way.
  return ALLOW;
}
