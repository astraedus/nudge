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

  return ALLOW;
}
