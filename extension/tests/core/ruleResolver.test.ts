import { describe, expect, it } from 'vitest';

import { resolveActiveRules, resolveRule, rulesForDomain } from '../../src/core/ruleResolver';
import type { ScheduleOverride, SiteRule } from '../../src/core/settingsSchema';

/**
 * ruleResolver bridges stored SiteRule settings to engine ActiveRule input. The core
 * semantic under test is "Scheduled Override": inside an active window the schedule's
 * mode+delay REPLACE the rule's default, even when the scheduled mode is weaker than the
 * default — that's deliberate (a user setting up "gentler at night" wants the gentler
 * mode to actually apply, not just be advisory).
 */

function siteRule(overrides: Partial<SiteRule> = {}): SiteRule {
  return {
    id: 'rule-1',
    domain: 'youtube.com',
    mode: 'HARD_BLOCK',
    delaySeconds: 15,
    dailyLimitMinutes: null,
    enabled: true,
    createdAt: 0,
    showTimeRemaining: false,
    schedule: null,
    ...overrides,
  };
}

function scheduleOverride(overrides: Partial<ScheduleOverride> = {}): ScheduleOverride {
  return {
    enabled: true,
    days: null,
    startMinute: null,
    endMinute: null,
    mode: 'BREATHING',
    delaySeconds: 20,
    ...overrides,
  };
}

/** 1-based month, matching how humans read dates ("January" = 1), unlike raw `Date`. */
function at(year: number, month: number, day: number, hour: number, minute: number): Date {
  return new Date(year, month - 1, day, hour, minute, 0, 0);
}

describe('rulesForDomain', () => {
  it('filters out disabled rules and rules for a different domain', () => {
    const rules = [
      siteRule({ id: 'a', domain: 'youtube.com', enabled: true }),
      siteRule({ id: 'b', domain: 'youtube.com', enabled: false }),
      siteRule({ id: 'c', domain: 'instagram.com', enabled: true }),
    ];
    const result = rulesForDomain(rules, 'youtube.com');
    expect(result.map((r) => r.id)).toEqual(['a']);
  });

  it('returns an empty array when nothing matches', () => {
    expect(rulesForDomain([siteRule({ domain: 'instagram.com' })], 'youtube.com')).toEqual([]);
  });
});

describe('resolveRule', () => {
  it('outside the schedule window, uses the rule\'s default mode + delaySeconds', () => {
    const rule = siteRule({
      mode: 'HARD_BLOCK',
      delaySeconds: 15,
      schedule: scheduleOverride({
        startMinute: 22 * 60,
        endMinute: 23 * 60,
        mode: 'BREATHING',
        delaySeconds: 45,
      }),
    });
    const outsideWindow = at(2026, 1, 7, 10, 0); // 10:00, window is 22:00-23:00
    const active = resolveRule(rule, outsideWindow);
    expect(active.mode).toBe('HARD_BLOCK');
    expect(active.delaySeconds).toBe(15);
  });

  it('inside the schedule window, the scheduled mode+delay REPLACE the default — even when WEAKER', () => {
    // Deliberate "override replaces default" semantic: default is HARD_BLOCK, the
    // schedule downgrades to BREATHING inside the window, and BREATHING must win.
    const rule = siteRule({
      mode: 'HARD_BLOCK',
      delaySeconds: 15,
      schedule: scheduleOverride({
        startMinute: 22 * 60,
        endMinute: 23 * 60,
        mode: 'BREATHING',
        delaySeconds: 45,
      }),
    });
    const insideWindow = at(2026, 1, 7, 22, 30);
    const active = resolveRule(rule, insideWindow);
    expect(active.mode).toBe('BREATHING');
    expect(active.delaySeconds).toBe(45);
  });

  it('schedule.enabled === false means the schedule is ignored even when `now` is inside the window', () => {
    const rule = siteRule({
      mode: 'HARD_BLOCK',
      delaySeconds: 15,
      schedule: scheduleOverride({
        enabled: false,
        startMinute: 22 * 60,
        endMinute: 23 * 60,
        mode: 'BREATHING',
        delaySeconds: 45,
      }),
    });
    const insideWindow = at(2026, 1, 7, 22, 30);
    const active = resolveRule(rule, insideWindow);
    expect(active.mode).toBe('HARD_BLOCK');
    expect(active.delaySeconds).toBe(15);
  });

  it('the daily limit applies in BOTH cases — inside and outside the window', () => {
    const rule = siteRule({
      dailyLimitMinutes: 30,
      schedule: scheduleOverride({ startMinute: 22 * 60, endMinute: 23 * 60 }),
    });
    const outside = resolveRule(rule, at(2026, 1, 7, 10, 0));
    const inside = resolveRule(rule, at(2026, 1, 7, 22, 30));
    expect(outside.dailyLimitMinutes).toBe(30);
    expect(inside.dailyLimitMinutes).toBe(30);
  });

  it('the resolved ActiveRule carries no schedule of its own', () => {
    const rule = siteRule({
      schedule: scheduleOverride({ days: [1, 2], startMinute: 0, endMinute: 60 }),
    });
    const active = resolveRule(rule, at(2026, 1, 7, 10, 0));
    expect(active.scheduleDays).toBeNull();
    expect(active.scheduleStartMinute).toBeNull();
    expect(active.scheduleEndMinute).toBeNull();
  });

  it('resolves correctly at 02:00 inside an overnight scheduled window', () => {
    const rule = siteRule({
      mode: 'HARD_BLOCK',
      delaySeconds: 15,
      schedule: scheduleOverride({
        startMinute: 23 * 60,
        endMinute: 6 * 60,
        mode: 'DELAY',
        delaySeconds: 60,
      }),
    });
    const at2am = at(2026, 1, 7, 2, 0);
    const active = resolveRule(rule, at2am);
    expect(active.mode).toBe('DELAY');
    expect(active.delaySeconds).toBe(60);
  });

  it('a null schedule always resolves to the default, regardless of `now`', () => {
    const rule = siteRule({ mode: 'DELAY', delaySeconds: 25, schedule: null });
    const active = resolveRule(rule, at(2026, 1, 7, 22, 30));
    expect(active.mode).toBe('DELAY');
    expect(active.delaySeconds).toBe(25);
  });
});

describe('resolveActiveRules', () => {
  it('resolves every enabled rule matching the domain, in order', () => {
    const rules = [
      siteRule({ id: 'a', domain: 'youtube.com', mode: 'HARD_BLOCK' }),
      siteRule({ id: 'b', domain: 'youtube.com', enabled: false }),
      siteRule({ id: 'c', domain: 'instagram.com' }),
    ];
    const result = resolveActiveRules(rules, 'youtube.com', at(2026, 1, 7, 10, 0));
    expect(result).toHaveLength(1);
    expect(result[0]?.ruleName).toBe('youtube.com');
    expect(result[0]?.mode).toBe('HARD_BLOCK');
  });
});
