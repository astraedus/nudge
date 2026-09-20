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
  it('does not gate a surface that is off and under (or without) a budget', () => {
    expect(gateAppliesNow(gateSetting({ mode: 'OFF' }), 0).applies).toBe(false);
    expect(
      gateAppliesNow(gateSetting({ mode: 'OFF', dailyLimitMinutes: 10 }), 5 * MINUTES).applies,
    ).toBe(false);
  });

  it('gates a surface whose mode is set, whatever its usage', () => {
    const verdict = gateAppliesNow(gateSetting({ mode: 'DELAY', delaySeconds: 20 }), 0);
    expect(verdict.applies).toBe(true);
    expect(verdict.mode).toBe('DELAY');
    expect(verdict.delaySeconds).toBe(20);
  });

  it('gates an OFF surface once its own budget is spent — "10 minutes of Shorts a day"', () => {
    const verdict = gateAppliesNow(
      gateSetting({ mode: 'OFF', dailyLimitMinutes: 10 }),
      10 * MINUTES,
    );
    expect(verdict.applies).toBe(true);
    expect(verdict.reason).toBe('limit-exhausted');
    expect(verdict.mode).toBe('HARD_BLOCK');
  });

  it('escalates a spent budget to a Hard Block even on a Delay gate', () => {
    // Nothing is left to wait for today, so offering a countdown would be a lie.
    const verdict = gateAppliesNow(
      gateSetting({ mode: 'OFF', dailyLimitMinutes: 5 }),
      60 * MINUTES,
    );
    expect(appliedBlockMode(verdict)).toBe('HARD_BLOCK');
  });
});
