import { describe, expect, it } from 'vitest';

import { evaluate, LIMIT_REACHED_SUFFIX } from '../../src/core/blockEngine';
import type { ActiveRule } from '../../src/core/types';

/**
 * Port of app/src/test/java/.../BlockEngineTest.kt, plus the priority-ladder and
 * budget-boundary edge cases the extension spec calls out explicitly.
 *
 * Note: unlike the Kotlin engine, this TS port's `ActiveRule` has no `inAppFeatures` /
 * feature-scoping — that concept doesn't exist in src/core/types.ts, so the Kotlin
 * feature-scoped-rule tests have no analog here and are intentionally not ported.
 */

function activeRule(overrides: Partial<ActiveRule> = {}): ActiveRule {
  return {
    mode: 'HARD_BLOCK',
    delaySeconds: 15,
    dailyLimitMinutes: null,
    enabled: true,
    scheduleDays: null,
    scheduleStartMinute: null,
    scheduleEndMinute: null,
    ruleName: 'Test Rule',
    ...overrides,
  };
}

/** 1-based month, matching how humans read dates ("January" = 1), unlike raw `Date`. */
function at(year: number, month: number, day: number, hour: number, minute: number): Date {
  return new Date(year, month - 1, day, hour, minute, 0, 0);
}

describe('evaluate — empty / disabled inputs', () => {
  it('an empty rule list allows', () => {
    expect(evaluate([], 0)).toEqual({ type: 'ALLOW' });
  });

  it('all-disabled rules allow, even an otherwise-unconditional HARD_BLOCK', () => {
    const rules = [activeRule({ mode: 'HARD_BLOCK', dailyLimitMinutes: null, enabled: false })];
    expect(evaluate(rules, 0)).toEqual({ type: 'ALLOW' });
  });
});

describe('evaluate — priority ladder: unconditional HARD_BLOCK beats everything', () => {
  it('beats a DELAY rule in the same list', () => {
    const rules = [
      activeRule({ mode: 'DELAY', delaySeconds: 30, ruleName: 'Delay Rule' }),
      activeRule({ mode: 'HARD_BLOCK', dailyLimitMinutes: null, ruleName: 'Hard Rule' }),
    ];
    const decision = evaluate(rules, 0);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.mode).toBe('HARD_BLOCK');
      expect(decision.ruleName).toBe('Hard Rule');
    }
  });

  it('beats a BREATHING rule in the same list', () => {
    const rules = [
      activeRule({ mode: 'BREATHING', delaySeconds: 20, ruleName: 'Breathing Rule' }),
      activeRule({ mode: 'HARD_BLOCK', dailyLimitMinutes: null, ruleName: 'Hard Rule' }),
    ];
    const decision = evaluate(rules, 0);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.mode).toBe('HARD_BLOCK');
      expect(decision.ruleName).toBe('Hard Rule');
    }
  });

  it('beats a budget-exceeded rule present in the same list', () => {
    const rules = [
      // This rule alone would trigger the budget-exceeded branch...
      activeRule({ mode: 'DELAY', delaySeconds: 5, dailyLimitMinutes: 10, ruleName: 'Budget Rule' }),
      // ...but the unconditional HARD_BLOCK short-circuits before that branch is reached.
      activeRule({ mode: 'HARD_BLOCK', dailyLimitMinutes: null, ruleName: 'Hard Rule' }),
    ];
    const usageMs = 11 * 60_000; // over Budget Rule's 10-minute limit
    const decision = evaluate(rules, usageMs);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.mode).toBe('HARD_BLOCK');
      expect(decision.ruleName).toBe('Hard Rule');
      expect(decision.limitReached).toBe(false);
    }
  });
});

describe('evaluate — budget-exceeded escalation', () => {
  it('forces HARD_BLOCK, suffixes the rule name, and sets limitReached', () => {
    const rules = [
      activeRule({ mode: 'DELAY', delaySeconds: 20, dailyLimitMinutes: 30, ruleName: 'YouTube' }),
    ];
    const decision = evaluate(rules, 30 * 60_000); // exactly at the limit
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.mode).toBe('HARD_BLOCK');
      expect(decision.ruleName).toBe(`YouTube${LIMIT_REACHED_SUFFIX}`);
      expect(decision.limitReached).toBe(true);
    }
  });

  it('a null rule name stays null — never "null (limit reached)"', () => {
    const rules = [
      activeRule({ mode: 'HARD_BLOCK', dailyLimitMinutes: 30, ruleName: null }),
    ];
    const decision = evaluate(rules, 30 * 60_000);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.ruleName).toBeNull();
    }
  });
});

describe('evaluate — daily budget boundary is >=, not >', () => {
  const limitMinutes = 30;
  function rules(): ActiveRule[] {
    return [activeRule({ mode: 'HARD_BLOCK', dailyLimitMinutes: limitMinutes, ruleName: 'Site' })];
  }

  it('1ms under the limit allows (rule is conditional, not unconditional)', () => {
    const usageMs = limitMinutes * 60_000 - 1;
    expect(evaluate(rules(), usageMs).type).toBe('ALLOW');
  });

  it('exactly at the limit blocks', () => {
    const usageMs = limitMinutes * 60_000;
    const decision = evaluate(rules(), usageMs);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.mode).toBe('HARD_BLOCK');
      expect(decision.limitReached).toBe(true);
    }
  });

  it('over the limit blocks', () => {
    const usageMs = limitMinutes * 60_000 + 5_000;
    const decision = evaluate(rules(), usageMs);
    expect(decision.type).toBe('BLOCK');
  });
});

describe('evaluate — DELAY beats BREATHING', () => {
  it('DELAY is chosen over BREATHING when both are present, and its delaySeconds propagates', () => {
    const rules = [
      activeRule({ mode: 'BREATHING', delaySeconds: 45, ruleName: 'Breathing Rule' }),
      activeRule({ mode: 'DELAY', delaySeconds: 12, ruleName: 'Delay Rule' }),
    ];
    const decision = evaluate(rules, 0);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.mode).toBe('DELAY');
      expect(decision.delaySeconds).toBe(12);
      expect(decision.ruleName).toBe('Delay Rule');
    }
  });
});

describe('evaluate — dailyTimeRemainingMs', () => {
  it('is null when no applicable rule sets a daily limit', () => {
    const rules = [activeRule({ mode: 'DELAY', dailyLimitMinutes: null })];
    const decision = evaluate(rules, 5_000);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') expect(decision.dailyTimeRemainingMs).toBeNull();
  });

  it('is floored at zero, never negative, once usage exceeds the limit', () => {
    const rules = [activeRule({ mode: 'DELAY', dailyLimitMinutes: 10 })];
    const usageMs = 10 * 60_000 + 999_999; // way over
    const decision = evaluate(rules, usageMs);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') expect(decision.dailyTimeRemainingMs).toBe(0);
  });

  it('uses the MINIMUM limit across several applicable rules with different limits', () => {
    const rules = [
      activeRule({ mode: 'BREATHING', dailyLimitMinutes: 60, ruleName: 'Loose' }),
      activeRule({ mode: 'BREATHING', dailyLimitMinutes: 20, ruleName: 'Tight' }),
    ];
    const usageMs = 5 * 60_000; // 5 minutes used
    const decision = evaluate(rules, usageMs);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.dailyLimitMinutes).toBe(20);
      expect(decision.dailyTimeRemainingMs).toBe(15 * 60_000); // 20 - 5 = 15 min remaining
    }
  });
});

describe('evaluate — a HARD_BLOCK rule with a daily limit is NOT unconditional', () => {
  it('under budget it does not hard-block via the unconditional branch', () => {
    const rules = [activeRule({ mode: 'HARD_BLOCK', dailyLimitMinutes: 60 })];
    const usageMs = 30 * 60_000; // 30 of 60 minutes used
    expect(evaluate(rules, usageMs).type).toBe('ALLOW');
  });
});

describe('evaluate — schedule filtering (explicit `now`)', () => {
  it('scheduled rules outside their window are filtered out entirely', () => {
    const now = at(2026, 1, 7, 20, 0); // Wed 20:00 — outside 9:00-17:00
    const rules = [
      activeRule({
        mode: 'HARD_BLOCK',
        dailyLimitMinutes: null,
        scheduleStartMinute: 9 * 60,
        scheduleEndMinute: 17 * 60,
      }),
    ];
    expect(evaluate(rules, 0, now).type).toBe('ALLOW');
  });

  it('scheduled rules inside their window are applied', () => {
    const now = at(2026, 1, 7, 10, 0); // Wed 10:00 — inside 9:00-17:00
    const rules = [
      activeRule({
        mode: 'HARD_BLOCK',
        dailyLimitMinutes: null,
        scheduleStartMinute: 9 * 60,
        scheduleEndMinute: 17 * 60,
      }),
    ];
    expect(evaluate(rules, 0, now).type).toBe('BLOCK');
  });
});

describe('evaluate — Android-parity sanity checks (ported from BlockEngineTest.kt)', () => {
  it('a single HARD_BLOCK rule blocks with mode HARD_BLOCK', () => {
    const rules = [activeRule({ mode: 'HARD_BLOCK', dailyLimitMinutes: null })];
    const decision = evaluate(rules, 0);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') expect(decision.mode).toBe('HARD_BLOCK');
  });

  it('a DELAY rule blocks with the configured delaySeconds', () => {
    const rules = [activeRule({ mode: 'DELAY', delaySeconds: 30, dailyLimitMinutes: null })];
    const decision = evaluate(rules, 0);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.mode).toBe('DELAY');
      expect(decision.delaySeconds).toBe(30);
    }
  });

  it('a BREATHING rule blocks with the configured delaySeconds', () => {
    const rules = [activeRule({ mode: 'BREATHING', delaySeconds: 20, dailyLimitMinutes: null })];
    const decision = evaluate(rules, 0);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.mode).toBe('BREATHING');
      expect(decision.delaySeconds).toBe(20);
    }
  });

  it('multiple DELAY rules use the first matching rule\'s delaySeconds', () => {
    const rules = [
      activeRule({ mode: 'DELAY', delaySeconds: 10, ruleName: 'First' }),
      activeRule({ mode: 'DELAY', delaySeconds: 30, ruleName: 'Second' }),
    ];
    const decision = evaluate(rules, 0);
    expect(decision.type).toBe('BLOCK');
    if (decision.type === 'BLOCK') {
      expect(decision.delaySeconds).toBe(10);
      expect(decision.ruleName).toBe('First');
    }
  });
});
