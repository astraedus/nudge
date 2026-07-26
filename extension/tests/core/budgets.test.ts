import { describe, expect, it } from 'vitest';
import {
  crossesLimit,
  isOverBudget,
  limitMs,
  remainingFraction,
  remainingMs,
  tightestLimit,
} from '../../src/core/budgets';
import type { SiteRule } from '../../src/core/settingsSchema';

function makeRule(overrides: Partial<SiteRule> = {}): SiteRule {
  return {
    id: overrides.id ?? 'rule-1',
    domain: overrides.domain ?? 'example.com',
    mode: overrides.mode ?? 'HARD_BLOCK',
    delaySeconds: overrides.delaySeconds ?? 15,
    dailyLimitMinutes: overrides.dailyLimitMinutes ?? null,
    enabled: overrides.enabled ?? true,
    createdAt: overrides.createdAt ?? 0,
    showTimeRemaining: overrides.showTimeRemaining ?? false,
    schedule: overrides.schedule ?? null,
  };
}

describe('limitMs', () => {
  it('converts minutes to milliseconds', () => {
    expect(limitMs(0)).toBe(0);
    expect(limitMs(1)).toBe(60_000);
    expect(limitMs(30)).toBe(1_800_000);
  });
});

describe('remainingMs', () => {
  it('is null when there is no limit', () => {
    expect(remainingMs(null, 0)).toBeNull();
    expect(remainingMs(null, 999_999)).toBeNull();
  });

  it('computes the remaining budget under the limit', () => {
    expect(remainingMs(30, 0)).toBe(1_800_000);
    expect(remainingMs(30, 600_000)).toBe(1_200_000);
  });

  it('floors at 0 and never goes negative once usage overshoots the limit', () => {
    expect(remainingMs(30, 1_800_000)).toBe(0);
    expect(remainingMs(30, 5_000_000)).toBe(0);
    expect(remainingMs(1, 10_000_000)).not.toBeLessThan(0);
  });
});

describe('isOverBudget', () => {
  it('is false when there is no limit', () => {
    expect(isOverBudget(null, 0)).toBe(false);
    expect(isOverBudget(null, 999_999_999)).toBe(false);
  });

  it('is false while strictly under the limit', () => {
    expect(isOverBudget(30, limitMs(30) - 1)).toBe(false);
  });

  it('is true exactly AT the limit boundary (>=, matches blockEngine.ts)', () => {
    expect(isOverBudget(30, limitMs(30))).toBe(true);
  });

  it('is true once past the limit', () => {
    expect(isOverBudget(30, limitMs(30) + 1)).toBe(true);
  });
});

describe('remainingFraction', () => {
  it('is null when there is no limit', () => {
    expect(remainingFraction(null, 0)).toBeNull();
  });

  it('is 1 at zero usage and 0 once usage reaches the limit', () => {
    expect(remainingFraction(30, 0)).toBe(1);
    expect(remainingFraction(30, limitMs(30))).toBe(0);
  });

  it('is a fraction in between, and never negative past the limit', () => {
    expect(remainingFraction(30, limitMs(30) / 2)).toBeCloseTo(0.5);
    const past = remainingFraction(30, limitMs(30) * 2);
    expect(past).not.toBeNull();
    expect(past as number).toBeGreaterThanOrEqual(0);
    expect(past as number).toBeLessThanOrEqual(1);
  });
});

describe('tightestLimit', () => {
  it('is null for an empty list', () => {
    expect(tightestLimit([])).toBeNull();
  });

  it('is null when every rule has no limit', () => {
    expect(
      tightestLimit([makeRule({ dailyLimitMinutes: null }), makeRule({ dailyLimitMinutes: null })]),
    ).toBeNull();
  });

  it('picks the minimum non-null limit and ignores nulls', () => {
    expect(
      tightestLimit([
        makeRule({ dailyLimitMinutes: null }),
        makeRule({ dailyLimitMinutes: 60 }),
        makeRule({ dailyLimitMinutes: 15 }),
        makeRule({ dailyLimitMinutes: 30 }),
      ]),
    ).toBe(15);
  });

  it('works with a single rule', () => {
    expect(tightestLimit([makeRule({ dailyLimitMinutes: 45 })])).toBe(45);
  });
});

describe('crossesLimit', () => {
  it('is false when there is no limit', () => {
    expect(crossesLimit(null, 0, 999_999_999)).toBe(false);
  });

  it('is false while still under the limit after the tick', () => {
    expect(crossesLimit(30, 0, limitMs(30) - 1)).toBe(false);
  });

  it('is true only on the exact tick that crosses INTO the limit boundary (>=)', () => {
    expect(crossesLimit(30, limitMs(30) - 1, limitMs(30))).toBe(true);
  });

  it('is true on the tick that jumps straight past the limit', () => {
    expect(crossesLimit(30, limitMs(30) - 1, limitMs(30) + 5_000)).toBe(true);
  });

  it('is false when usage was ALREADY at/over the limit before this tick', () => {
    expect(crossesLimit(30, limitMs(30), limitMs(30) + 1_000)).toBe(false);
    expect(crossesLimit(30, limitMs(30) + 1_000, limitMs(30) + 2_000)).toBe(false);
  });

  it('is false for a tick that stays flat exactly at the limit', () => {
    expect(crossesLimit(30, limitMs(30), limitMs(30))).toBe(false);
  });
});
