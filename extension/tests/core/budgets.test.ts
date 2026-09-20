import { describe, expect, it } from 'vitest';
import {
  crossesCount,
  crossesLimit,
  isOverBudget,
  isOverCount,
  limitMs,
  remainingCount,
  remainingFraction,
  remainingMs,
  tightestGateCount,
  tightestGateMinutes,
  tightestLimit,
} from '../../src/core/budgets';
import type { SiteRule } from '../../src/core/settingsSchema';
import { featuresWith, siteRule as makeRule } from '../helpers/rules';

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

/* ============================================================ count budgets (v0.3) */

describe('isOverCount', () => {
  it('is false when there is no limit', () => {
    expect(isOverCount(null, 0)).toBe(false);
    expect(isOverCount(null, 999)).toBe(false);
  });

  it('is false while strictly under the limit', () => {
    expect(isOverCount(20, 19)).toBe(false);
  });

  it('is true exactly AT the limit boundary (>=, matches isOverBudget’s convention)', () => {
    expect(isOverCount(20, 20)).toBe(true);
  });

  it('is true once past the limit', () => {
    expect(isOverCount(20, 21)).toBe(true);
  });

  it('is true at 0/0 only when the limit is also 0 — an edge a real preset never produces, but the boundary must still hold', () => {
    expect(isOverCount(0, 0)).toBe(true);
  });
});

describe('remainingCount', () => {
  it('is null when there is no limit', () => {
    expect(remainingCount(null, 0)).toBeNull();
    expect(remainingCount(null, 999)).toBeNull();
  });

  it('computes the remaining items under the limit', () => {
    expect(remainingCount(20, 0)).toBe(20);
    expect(remainingCount(20, 12)).toBe(8);
  });

  it('floors at 0 and never goes negative once usage overshoots the limit', () => {
    expect(remainingCount(20, 20)).toBe(0);
    expect(remainingCount(20, 500)).toBe(0);
  });
});

describe('crossesCount', () => {
  it('is false when there is no limit', () => {
    expect(crossesCount(null, 0, 999)).toBe(false);
  });

  it('is false while still under the limit after the increment', () => {
    expect(crossesCount(20, 5, 19)).toBe(false);
  });

  it('is true only on the increment that crosses INTO the limit boundary (>=)', () => {
    expect(crossesCount(20, 19, 20)).toBe(true);
  });

  it('is true on an increment that jumps straight past the limit', () => {
    expect(crossesCount(20, 15, 25)).toBe(true);
  });

  it('is false when the count was ALREADY at/over the limit before this increment', () => {
    expect(crossesCount(20, 20, 21)).toBe(false);
    expect(crossesCount(20, 25, 26)).toBe(false);
  });

  it('is false for an increment that stays flat exactly at the limit', () => {
    expect(crossesCount(20, 20, 20)).toBe(false);
  });
});

describe('tightestGateMinutes / tightestGateCount', () => {
  const GATE_ID = 'shorts';

  function ruleWithShortsGate(overrides: {
    dailyLimitMinutes?: number | null;
    dailyLimitCount?: number | null;
  }): SiteRule {
    return makeRule({
      domain: 'youtube.com',
      features: featuresWith('youtube', { gates: { [GATE_ID]: overrides } }),
    });
  }

  it('is null for an empty rule list', () => {
    expect(tightestGateMinutes([], GATE_ID)).toBeNull();
    expect(tightestGateCount([], GATE_ID)).toBeNull();
  });

  it('is null when no rule sets a cap on that axis for this gate', () => {
    const rules = [ruleWithShortsGate({}), ruleWithShortsGate({})];
    expect(tightestGateMinutes(rules, GATE_ID)).toBeNull();
    expect(tightestGateCount(rules, GATE_ID)).toBeNull();
  });

  it('picks the tightest (minimum) cap on each axis independently, ignoring nulls', () => {
    const rules = [
      ruleWithShortsGate({ dailyLimitMinutes: 30, dailyLimitCount: 50 }),
      ruleWithShortsGate({ dailyLimitMinutes: 10, dailyLimitCount: null }),
      ruleWithShortsGate({ dailyLimitMinutes: null, dailyLimitCount: 5 }),
    ];
    // The tightest minute cap (10) and the tightest count cap (5) come from DIFFERENT
    // rules — each axis is its own minimum across the set, not tied to one "winning" rule.
    expect(tightestGateMinutes(rules, GATE_ID)).toBe(10);
    expect(tightestGateCount(rules, GATE_ID)).toBe(5);
  });

  it('ignores a rule with no features block, or one whose features simply have no cap on this gate', () => {
    const noFeatures = makeRule({ domain: 'example.com', features: null });
    const noCapSet = makeRule({
      domain: 'youtube.com',
      features: featuresWith('youtube', {}), // shorts gate exists (materialized), but bare
    });
    const rules = [noFeatures, noCapSet, ruleWithShortsGate({ dailyLimitMinutes: 15 })];
    expect(tightestGateMinutes(rules, GATE_ID)).toBe(15);
  });

  it('is null for a gate id none of the rules configure, even when other gates on the same rule have caps', () => {
    const rules = [ruleWithShortsGate({ dailyLimitMinutes: 15, dailyLimitCount: 5 })];
    expect(tightestGateMinutes(rules, 'explore')).toBeNull();
    expect(tightestGateCount(rules, 'explore')).toBeNull();
  });

  it('works with a single rule', () => {
    const rules = [ruleWithShortsGate({ dailyLimitMinutes: 45, dailyLimitCount: 12 })];
    expect(tightestGateMinutes(rules, GATE_ID)).toBe(45);
    expect(tightestGateCount(rules, GATE_ID)).toBe(12);
  });
});
