import { describe, expect, it } from 'vitest';

import {
  appliedBlockMode,
  gateAppliesNow,
  resolveSiteMode,
  siteRuleAppliesNow,
} from '../../src/core/applies';
import type { BlockMode, SiteMode } from '../../src/core/types';
import { gateSetting, scheduleOverride, siteRule } from '../helpers/rules';

/**
 * `siteRuleAppliesNow` is the single answer to "is this rule in force right now", read by
 * DNR, the engine's resolver, the popup and the badge. These cases are written as
 * USER-VISIBLE outcomes ("the site is blocked" / "the site opens normally"), never as the
 * branch taken — the repo has already paid once for tests that encoded an implementation
 * detail and so happily asserted a bug (see the redirect-loop lesson in CLAUDE.md).
 */

/** 1-based month, matching how humans read dates. */
function at(year: number, month: number, day: number, hour: number, minute: number): Date {
  return new Date(year, month - 1, day, hour, minute, 0, 0);
}

const MINUTES = 60_000;
const NOON = at(2026, 1, 7, 12, 0); // a Wednesday

describe('siteRuleAppliesNow: the mode x limit x usage matrix', () => {
  const blockModes: BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];

  for (const mode of blockModes) {
    it(`${mode} with no limit is in force`, () => {
      const rule = siteRule({ mode, dailyLimitMinutes: null });
      expect(siteRuleAppliesNow(rule, 0, NOON).applies).toBe(true);
    });

    it(`${mode} under its limit is still in force (a block mode blocks regardless)`, () => {
      const rule = siteRule({ mode, dailyLimitMinutes: 30 });
      expect(siteRuleAppliesNow(rule, 5 * MINUTES, NOON).applies).toBe(true);
    });

    it(`${mode} over its limit is in force`, () => {
      const rule = siteRule({ mode, dailyLimitMinutes: 30 });
      expect(siteRuleAppliesNow(rule, 45 * MINUTES, NOON).applies).toBe(true);
    });
  }

  it('ALLOW with no limit never blocks — the rule exists for grayscale or features alone', () => {
    const rule = siteRule({ mode: 'ALLOW', dailyLimitMinutes: null });
    const verdict = siteRuleAppliesNow(rule, 10 * 60 * MINUTES, NOON);
    expect(verdict.applies).toBe(false);
    expect(verdict.reason).toBe('allowed');
  });

  it('ALLOW under its limit lets the site open normally', () => {
    const rule = siteRule({ mode: 'ALLOW', dailyLimitMinutes: 30 });
    expect(siteRuleAppliesNow(rule, 29 * MINUTES, NOON).applies).toBe(false);
  });

  it('ALLOW exactly at its limit blocks — at the limit already counts as spent', () => {
    const rule = siteRule({ mode: 'ALLOW', dailyLimitMinutes: 30 });
    const verdict = siteRuleAppliesNow(rule, 30 * MINUTES, NOON);
    expect(verdict.applies).toBe(true);
    expect(verdict.reason).toBe('limit-exhausted');
  });

  it('ALLOW past its limit blocks', () => {
    const rule = siteRule({ mode: 'ALLOW', dailyLimitMinutes: 30 });
    expect(siteRuleAppliesNow(rule, 31 * MINUTES, NOON).applies).toBe(true);
  });

  it('a disabled rule is never in force, whatever its mode or usage', () => {
    for (const mode of [...blockModes, 'ALLOW'] as SiteMode[]) {
      const rule = siteRule({ mode, enabled: false, dailyLimitMinutes: 1 });
      const verdict = siteRuleAppliesNow(rule, 99 * MINUTES, NOON);
      expect(verdict.applies, `${mode} disabled`).toBe(false);
      expect(verdict.reason).toBe('disabled');
    }
  });
});

describe('siteRuleAppliesNow: the schedule window', () => {
  it('blocks only inside the window for an ALLOW rule — "block during work hours"', () => {
    const rule = siteRule({
      mode: 'ALLOW',
      schedule: scheduleOverride({ mode: 'HARD_BLOCK', startMinute: 9 * 60, endMinute: 17 * 60 }),
    });
    expect(siteRuleAppliesNow(rule, 0, at(2026, 1, 7, 12, 0)).applies).toBe(true);
    expect(siteRuleAppliesNow(rule, 0, at(2026, 1, 7, 20, 0)).applies).toBe(false);
  });

  it('opens only inside the window for a blocking rule — "allow at lunchtime"', () => {
    const rule = siteRule({
      mode: 'HARD_BLOCK',
      schedule: scheduleOverride({ mode: 'ALLOW', startMinute: 12 * 60, endMinute: 13 * 60 }),
    });
    expect(siteRuleAppliesNow(rule, 0, at(2026, 1, 7, 12, 30)).applies).toBe(false);
    expect(siteRuleAppliesNow(rule, 0, at(2026, 1, 7, 15, 0)).applies).toBe(true);
  });

  it('still enforces the daily limit inside an ALLOW window', () => {
    // The limit belongs to the SITE, not to the window — an "allow at lunchtime" exception
    // is an exception to the block, not a fresh unlimited hour.
    const rule = siteRule({
      mode: 'HARD_BLOCK',
      dailyLimitMinutes: 20,
      schedule: scheduleOverride({ mode: 'ALLOW', startMinute: 12 * 60, endMinute: 13 * 60 }),
    });
    const inWindow = at(2026, 1, 7, 12, 30);
    expect(siteRuleAppliesNow(rule, 5 * MINUTES, inWindow).applies).toBe(false);
    expect(siteRuleAppliesNow(rule, 25 * MINUTES, inWindow).applies).toBe(true);
  });

  it('ignores a disabled schedule and falls back to the default behaviour', () => {
    const rule = siteRule({
      mode: 'ALLOW',
      schedule: scheduleOverride({ enabled: false, mode: 'HARD_BLOCK' }),
    });
    expect(siteRuleAppliesNow(rule, 0, NOON).applies).toBe(false);
  });

  it('respects the schedule days', () => {
    const rule = siteRule({
      mode: 'ALLOW',
      // Monday only.
      schedule: scheduleOverride({ mode: 'HARD_BLOCK', days: [1] }),
    });
    expect(siteRuleAppliesNow(rule, 0, at(2026, 1, 5, 12, 0)).applies).toBe(true); // Monday
    expect(siteRuleAppliesNow(rule, 0, at(2026, 1, 7, 12, 0)).applies).toBe(false); // Wednesday
  });
});

describe('resolveSiteMode', () => {
  it('reports the default mode and delay outside any window', () => {
    const rule = siteRule({ mode: 'DELAY', delaySeconds: 15 });
    expect(resolveSiteMode(rule, NOON)).toEqual({ mode: 'DELAY', delaySeconds: 15 });
  });

  it('reports the window mode and delay inside one', () => {
    const rule = siteRule({
      mode: 'DELAY',
      delaySeconds: 15,
      schedule: scheduleOverride({ mode: 'BREATHING', delaySeconds: 40 }),
    });
    expect(resolveSiteMode(rule, NOON)).toEqual({ mode: 'BREATHING', delaySeconds: 40 });
  });
});

describe('appliedBlockMode', () => {
  it('is null when the rule is not in force, so it can never be read as a block', () => {
    const rule = siteRule({ mode: 'ALLOW' });
    expect(appliedBlockMode(siteRuleAppliesNow(rule, 0, NOON))).toBeNull();
  });

  it('is the rule’s own mode when a block mode is in force', () => {
    const rule = siteRule({ mode: 'BREATHING' });
    expect(appliedBlockMode(siteRuleAppliesNow(rule, 0, NOON))).toBe('BREATHING');
  });

  it('is a Hard Block for an exhausted ALLOW rule — a spent budget cannot be waited out', () => {
    const rule = siteRule({ mode: 'ALLOW', dailyLimitMinutes: 10 });
    expect(appliedBlockMode(siteRuleAppliesNow(rule, 20 * MINUTES, NOON))).toBe('HARD_BLOCK');
  });
});

describe('gateAppliesNow', () => {
  // `usageCount` is a required third argument (v0.3) — a default of 0 would silently mean
  // "the count is never spent" at any call site that forgot it, so every case here passes
  // it explicitly, even the ones that are only exercising the minute axis.
  it('does not gate a surface that is off and under (or without) a budget', () => {
    expect(gateAppliesNow(gateSetting({ mode: 'OFF' }), 0, 0).applies).toBe(false);
    expect(
      gateAppliesNow(gateSetting({ mode: 'OFF', dailyLimitMinutes: 10 }), 5 * MINUTES, 0).applies,
    ).toBe(false);
  });

  it('gates a surface whose mode is set, whatever its usage', () => {
    const verdict = gateAppliesNow(gateSetting({ mode: 'DELAY', delaySeconds: 20 }), 0, 0);
    expect(verdict.applies).toBe(true);
    expect(verdict.mode).toBe('DELAY');
    expect(verdict.delaySeconds).toBe(20);
  });

  it('gates an OFF surface once its own budget is spent — "10 minutes of Shorts a day"', () => {
    const verdict = gateAppliesNow(
      gateSetting({ mode: 'OFF', dailyLimitMinutes: 10 }),
      10 * MINUTES,
      0,
    );
    expect(verdict.applies).toBe(true);
    expect(verdict.reason).toBe('limit-exhausted');
    expect(verdict.mode).toBe('HARD_BLOCK');
  });

  it('escalates a spent budget to a Hard Block even on a Delay gate', () => {
    // Nothing is left to wait for today, so offering a countdown would be a lie. This used
    // to read 'mode-blocks' with a real DELAY countdown before the caps were checked before
    // the mode (see core/applies.ts's ordering comment) — the block page's engine had
    // already escalated the same gate to HARD_BLOCK independently, so the in-page overlay
    // was offering a pause that bought access the block page would have refused. Now one
    // predicate answers for both layers.
    const verdict = gateAppliesNow(
      gateSetting({ mode: 'OFF', dailyLimitMinutes: 5 }),
      60 * MINUTES,
      0,
    );
    expect(verdict.reason).toBe('limit-exhausted');
    expect(appliedBlockMode(verdict)).toBe('HARD_BLOCK');
  });

  it('a DELAY gate whose minute budget is spent resolves to a Hard Block, not a Delay countdown', () => {
    // Same case as above, stated directly against a gate whose OWN mode is DELAY (not OFF)
    // — this is exactly the shape the redirect-loop-class lesson in CLAUDE.md warns about:
    // a test written from a spec sentence can encode the bug. The user-visible outcome is
    // "the surface is gated, with no countdown", not "the DELAY branch ran".
    const verdict = gateAppliesNow(
      gateSetting({ mode: 'DELAY', delaySeconds: 20, dailyLimitMinutes: 10 }),
      10 * MINUTES,
      0,
    );
    expect(verdict.applies).toBe(true);
    expect(verdict.reason).toBe('limit-exhausted');
    expect(verdict.mode).toBe('HARD_BLOCK');
    expect(verdict.delaySeconds).toBe(0);
  });
});

/**
 * The independent COUNT axis (v0.3): "20 Shorts a day, then the gate." Mirrors the minute
 * axis's own describe block above, case for case, because the two are meant to behave
 * identically except for their unit.
 */
describe('gateAppliesNow: the COUNT axis (v0.3)', () => {
  it('gates a surface once its item count is spent, even though its mode is OFF', () => {
    const verdict = gateAppliesNow(gateSetting({ mode: 'OFF', dailyLimitCount: 20 }), 0, 20);
    expect(verdict.applies).toBe(true);
    expect(verdict.reason).toBe('count-exhausted');
    expect(verdict.mode).toBe('HARD_BLOCK');
  });

  it('does not gate while strictly under the count', () => {
    const verdict = gateAppliesNow(gateSetting({ mode: 'OFF', dailyLimitCount: 20 }), 0, 19);
    expect(verdict.applies).toBe(false);
  });

  it('escalates a spent count to a Hard Block even on a Delay gate — nothing left to wait for today', () => {
    const verdict = gateAppliesNow(
      gateSetting({ mode: 'DELAY', delaySeconds: 20, dailyLimitCount: 5 }),
      0,
      5,
    );
    expect(verdict.reason).toBe('count-exhausted');
    expect(appliedBlockMode(verdict)).toBe('HARD_BLOCK');
    expect(verdict.delaySeconds).toBe(0);
  });

  it('minutes and count are independent — either alone is enough to gate the surface', () => {
    const minutesOnly = gateAppliesNow(
      gateSetting({ mode: 'OFF', dailyLimitMinutes: 10, dailyLimitCount: null }),
      10 * MINUTES,
      0,
    );
    expect(minutesOnly.applies).toBe(true);
    expect(minutesOnly.reason).toBe('limit-exhausted');

    const countOnly = gateAppliesNow(
      gateSetting({ mode: 'OFF', dailyLimitMinutes: null, dailyLimitCount: 5 }),
      0,
      5,
    );
    expect(countOnly.applies).toBe(true);
    expect(countOnly.reason).toBe('count-exhausted');
  });

  it('reports minutes first when BOTH budgets are spent at once — but the verdict is identical either way', () => {
    // core/applies.ts's ordering comment: minutes are checked first only so ONE reason has
    // to win; applies/mode/delaySeconds are the same regardless of which axis "wins" the
    // reason string.
    const verdict = gateAppliesNow(
      gateSetting({ mode: 'OFF', dailyLimitMinutes: 10, dailyLimitCount: 5 }),
      10 * MINUTES,
      5,
    );
    expect(verdict.reason).toBe('limit-exhausted');
    expect(verdict.applies).toBe(true);
    expect(verdict.mode).toBe('HARD_BLOCK');
  });
});

/**
 * The full mode x minutes x count matrix, asserting the USER-VISIBLE outcome rather than
 * the internal branch — per the redirect-loop lesson in CLAUDE.md, a test written from a
 * spec sentence ("a Hard Block with a spent budget is unconditional") can encode exactly
 * the bug it meant to prevent. So every case here states only two things a user can
 * observe: whether the surface is gated, and — whenever some cap is spent — that it
 * resolves to a Hard Block rather than a countdown.
 */
describe('gateAppliesNow: the mode x minutes x count matrix', () => {
  type CapState = 'none' | 'under' | 'spent';
  const CAP_STATES: readonly CapState[] = ['none', 'under', 'spent'];
  const MODES: readonly ('OFF' | 'DELAY' | 'HARD_BLOCK')[] = ['OFF', 'DELAY', 'HARD_BLOCK'];

  function minutesFor(state: CapState): { dailyLimitMinutes: number | null; usageMs: number } {
    switch (state) {
      case 'none':
        return { dailyLimitMinutes: null, usageMs: 0 };
      case 'under':
        return { dailyLimitMinutes: 10, usageMs: 5 * MINUTES };
      case 'spent':
        return { dailyLimitMinutes: 10, usageMs: 10 * MINUTES };
    }
  }

  function countFor(state: CapState): { dailyLimitCount: number | null; usageCount: number } {
    switch (state) {
      case 'none':
        return { dailyLimitCount: null, usageCount: 0 };
      case 'under':
        return { dailyLimitCount: 5, usageCount: 2 };
      case 'spent':
        return { dailyLimitCount: 5, usageCount: 5 };
    }
  }

  for (const mode of MODES) {
    for (const minuteState of CAP_STATES) {
      for (const countState of CAP_STATES) {
        const { dailyLimitMinutes, usageMs } = minutesFor(minuteState);
        const { dailyLimitCount, usageCount } = countFor(countState);
        const anyCapSpent = minuteState === 'spent' || countState === 'spent';
        const expectedApplies = anyCapSpent || mode !== 'OFF';

        it(`mode=${mode} minutes=${minuteState} count=${countState} -> applies=${expectedApplies}`, () => {
          const gate = gateSetting({
            mode,
            delaySeconds: 20,
            dailyLimitMinutes,
            dailyLimitCount,
          });
          const verdict = gateAppliesNow(gate, usageMs, usageCount);

          expect(verdict.applies).toBe(expectedApplies);
          if (anyCapSpent) {
            // Whichever cap is spent (or both), nothing is left to wait for today: the
            // surface must resolve to an unconditional Hard Block, never a countdown.
            expect(appliedBlockMode(verdict)).toBe('HARD_BLOCK');
            expect(verdict.delaySeconds).toBe(0);
          }
        });
      }
    }
  }
});
