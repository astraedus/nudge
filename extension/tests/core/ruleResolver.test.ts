import { describe, expect, it } from 'vitest';

import {
  resolveActiveRules,
  resolveRule,
  ruleForHost,
  rulesForDomain,
  usageKeyForHost,
} from '../../src/core/ruleResolver';
import type { ActiveRule } from '../../src/core/types';
import { scheduleOverride, siteRule } from '../helpers/rules';

/** 1-based month, matching how humans read dates ("January" = 1), unlike raw `Date`. */
function at(year: number, month: number, day: number, hour: number, minute: number): Date {
  return new Date(year, month - 1, day, hour, minute, 0, 0);
}

/**
 * `resolveRule` returns null for a rule that is not in force. These cases all pass a rule
 * that IS in force, so the null is a test-setup error, not an expected outcome, assert it
 * away once here rather than at every call site.
 */
function resolved(rule: Parameters<typeof resolveRule>[0], now: Date, usageMs = 0): ActiveRule {
  const active = resolveRule(rule, now, usageMs);
  if (active === null) throw new Error('expected the rule to be in force');
  return active;
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
    const active = resolved(rule, outsideWindow);
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
    const active = resolved(rule, insideWindow);
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
    const active = resolved(rule, insideWindow);
    expect(active.mode).toBe('HARD_BLOCK');
    expect(active.delaySeconds).toBe(15);
  });

  it('the daily limit applies in BOTH cases — inside and outside the window', () => {
    const rule = siteRule({
      dailyLimitMinutes: 30,
      schedule: scheduleOverride({ startMinute: 22 * 60, endMinute: 23 * 60 }),
    });
    const outside = resolved(rule, at(2026, 1, 7, 10, 0));
    const inside = resolved(rule, at(2026, 1, 7, 22, 30));
    expect(outside.dailyLimitMinutes).toBe(30);
    expect(inside.dailyLimitMinutes).toBe(30);
  });

  it('the resolved ActiveRule carries no schedule of its own', () => {
    const rule = siteRule({
      schedule: scheduleOverride({ days: [1, 2], startMinute: 0, endMinute: 60 }),
    });
    const active = resolved(rule, at(2026, 1, 7, 10, 0));
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
    const active = resolved(rule, at2am);
    expect(active.mode).toBe('DELAY');
    expect(active.delaySeconds).toBe(60);
  });

  it('a null schedule always resolves to the default, regardless of `now`', () => {
    const rule = siteRule({ mode: 'DELAY', delaySeconds: 25, schedule: null });
    const active = resolved(rule, at(2026, 1, 7, 22, 30));
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
    const result = resolveActiveRules(rules, 'youtube.com', at(2026, 1, 7, 10, 0), 0);
    expect(result).toHaveLength(1);
    expect(result[0]?.ruleName).toBe('youtube.com');
    expect(result[0]?.mode).toBe('HARD_BLOCK');
  });
});

describe('a rule covers its subdomains, exactly as the network layer always did', () => {
  /**
   * Live QA 2026-09-20. `dnrUrlFilter` compiles `||domain^` and matches every subdomain, but
   * everything that read a rule by exact string did not, so on en.wikipedia.org the daily
   * limit never fired, the popup said "Not blocked" and offered to block an already-ruled
   * site, grayscale answered `no-rule`, and the badge showed nothing. One resolver now
   * answers "which rule covers this host" for all of them.
   */
  const wikipedia = siteRule({ id: 'w', domain: 'wikipedia.org', mode: 'ALLOW' });
  const google = siteRule({ id: 'g', domain: 'google.com', mode: 'ALLOW' });
  const docs = siteRule({ id: 'd', domain: 'docs.google.com', mode: 'HARD_BLOCK' });

  it('matches a subdomain of the rule domain', () => {
    expect(ruleForHost([wikipedia], 'en.wikipedia.org')?.id).toBe('w');
    expect(ruleForHost([wikipedia], 'wikipedia.org')?.id).toBe('w');
    expect(ruleForHost([siteRule({ domain: 'reddit.com' })], 'old.reddit.com')).not.toBeNull();
  });

  it('does not match a different site that merely ends the same way', () => {
    // "notwikipedia.org" is a different company, not a subdomain.
    expect(ruleForHost([wikipedia], 'notwikipedia.org')).toBeNull();
    expect(ruleForHost([wikipedia], 'wikipedia.org.evil.com')).toBeNull();
  });

  it('prefers the most specific rule when both could apply', () => {
    expect(ruleForHost([google, docs], 'docs.google.com')?.id).toBe('d');
    expect(ruleForHost([google, docs], 'mail.google.com')?.id).toBe('g');
  });

  it('ignores a disabled rule', () => {
    const off = siteRule({ domain: 'wikipedia.org', enabled: false });
    expect(ruleForHost([off], 'en.wikipedia.org')).toBeNull();
  });

  it('attributes usage to the RULE, so the tracker and the budget read one bucket', () => {
    // This is the whole limit bug in one line: fill `en.wikipedia.org`, read
    // `wikipedia.org`, and no amount of browsing ever crosses the limit.
    expect(usageKeyForHost([wikipedia], 'en.wikipedia.org')).toBe('wikipedia.org');
    expect(usageKeyForHost([google, docs], 'docs.google.com')).toBe('docs.google.com');
  });

  it('falls back to the host itself when no rule covers it, leaving plain stats untouched', () => {
    expect(usageKeyForHost([wikipedia], 'news.ycombinator.com')).toBe('news.ycombinator.com');
    expect(usageKeyForHost([], 'en.wikipedia.org')).toBe('en.wikipedia.org');
  });

  it('puts a subdomain page under the rule for the engine too', () => {
    const active = resolveActiveRules(
      [siteRule({ domain: 'wikipedia.org', mode: 'HARD_BLOCK' })],
      'en.wikipedia.org',
      at(2026, 1, 7, 12, 0),
      0,
    );
    expect(active).toHaveLength(1);
  });
});
