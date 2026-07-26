import { describe, expect, it } from 'vitest';
import {
  GLOBAL_KEY,
  LOCKOUT_MS,
  PASS_DURATION_MS,
  canUseGlobal,
  globalLastUsed,
  nextAvailableGlobalMs,
  parse,
  recordGlobal,
  serialize,
} from '../../src/core/emergencyPass';

// Tests for the pure emergency-pass ledger logic. The pass gates a protection-WEAKENING escape
// hatch with GLOBAL (one-per-24h-across-all-sites) semantics, so both the eligibility boundary
// (exact-cooldown, never-used, cross-site lockout) and robustness of the persisted-ledger parser
// (malformed input must never throw; a legacy per-site ledger must migrate) are load-bearing.

const cooldown = LOCKOUT_MS;
const t0 = 1_000_000_000_000; // arbitrary fixed "now" base

describe('emergencyPass: parse() / serialize() round-trip', () => {
  it('empty string parses to an empty object', () => {
    expect(parse('')).toEqual({});
    expect(parse('   ')).toEqual({});
  });

  it('single entry round-trips', () => {
    const usage = { 'foo.com': 123456789 };
    expect(parse(serialize(usage))).toEqual(usage);
  });

  it('global record round-trips through serialize', () => {
    const recorded = recordGlobal(t0);
    expect(recorded).toEqual({ [GLOBAL_KEY]: t0 });
    expect(parse(serialize(recorded))).toEqual(recorded);
  });

  it('serialize of an empty object is an empty string', () => {
    expect(serialize({})).toBe('');
  });

  it('malformed entries are ignored and never throw', () => {
    // Missing value, missing key, non-numeric value, negative value, stray separators, no '='.
    const raw = 'a.com=100;=200;b.com=;c.com=abc;;d.com=-5;garbage;e.com=300';
    expect(() => parse(raw)).not.toThrow();
    const parsed = parse(raw);
    expect(parsed).toEqual({ 'a.com': 100, 'e.com': 300 });
  });

  it('entirely garbage input yields an empty object and never throws', () => {
    expect(() => parse(';;;===;no-equals;')).not.toThrow();
    expect(parse(';;;===;no-equals;')).toEqual({});
  });
});

describe('emergencyPass: globalLastUsed() (migration-safe MAX)', () => {
  it('is null when never used', () => {
    expect(globalLastUsed({})).toBeNull();
  });

  it('is the single global entry', () => {
    expect(globalLastUsed({ [GLOBAL_KEY]: t0 })).toBe(t0);
  });

  it('takes the MAX across a legacy per-site ledger (migration)', () => {
    // Old per-site ledger: the most recent site's timestamp becomes the global last-used.
    const legacy = {
      'instagram.com': t0,
      'tiktok.com': t0 + 5_000,
      'youtube.com': t0 - 10_000,
    };
    expect(globalLastUsed(legacy)).toBe(t0 + 5_000);
  });
});

describe('emergencyPass: canUseGlobal()', () => {
  it('true when never used before', () => {
    expect(canUseGlobal({}, t0, cooldown)).toBe(true);
  });

  it('false immediately after use', () => {
    const usage = recordGlobal(t0);
    expect(canUseGlobal(usage, t0, cooldown)).toBe(false);
  });

  it('false just before cooldown elapses', () => {
    const usage = recordGlobal(t0);
    expect(canUseGlobal(usage, t0 + cooldown - 1, cooldown)).toBe(false);
  });

  it('true exactly at the cooldown boundary', () => {
    const usage = recordGlobal(t0);
    expect(canUseGlobal(usage, t0 + cooldown, cooldown)).toBe(true);
  });

  it('is GLOBAL — using on any site locks out all sites', () => {
    // A per-site ledger with a single recent entry (e.g. pass used on Instagram) still blocks
    // the pass for every other site because the lockout is global, not per-domain.
    const usedOnInstagram = { 'instagram.com': t0 };
    // No matter which site's block screen we are on, the pass is locked until cooldown elapses.
    expect(canUseGlobal(usedOnInstagram, t0 + 1, cooldown)).toBe(false);
    expect(canUseGlobal(usedOnInstagram, t0 + cooldown, cooldown)).toBe(true);
  });
});

describe('emergencyPass: nextAvailableGlobalMs()', () => {
  it('is zero when never used', () => {
    expect(nextAvailableGlobalMs({}, t0, cooldown)).toBe(0);
  });

  it('is the full cooldown immediately after use', () => {
    const usage = recordGlobal(t0);
    expect(nextAvailableGlobalMs(usage, t0, cooldown)).toBe(cooldown);
  });

  it('decreases as time passes (decay toward zero)', () => {
    const usage = recordGlobal(t0);
    const elapsed = 3_600_000; // 1h
    expect(nextAvailableGlobalMs(usage, t0 + elapsed, cooldown)).toBe(cooldown - elapsed);
  });

  it('is zero once the cooldown has passed', () => {
    const usage = recordGlobal(t0);
    expect(nextAvailableGlobalMs(usage, t0 + cooldown, cooldown)).toBe(0);
    expect(nextAvailableGlobalMs(usage, t0 + cooldown + 5_000, cooldown)).toBe(0);
  });

  it('uses the MAX timestamp of a legacy ledger', () => {
    const legacy = { 'a.com': t0, 'b.com': t0 + 10_000 };
    // Locked out relative to the most recent (b.com) entry.
    expect(nextAvailableGlobalMs(legacy, t0 + 20_000, cooldown)).toBe(cooldown - 10_000);
  });
});

describe('emergencyPass: recordGlobal()', () => {
  it('collapses to a single global entry', () => {
    expect(recordGlobal(t0)).toEqual({ [GLOBAL_KEY]: t0 });
  });

  it('overwrites any prior per-site entries (collapse, not merge)', () => {
    // recordGlobal is a pure constructor — the "collapse" behavior is verified by the caller
    // (settings write) discarding the old ledger entirely and using this return value as-is.
    const fresh = recordGlobal(t0);
    expect(Object.keys(fresh)).toEqual([GLOBAL_KEY]);
  });

  it('output round-trips through serialize and canUseGlobal', () => {
    const recorded = recordGlobal(t0);
    const reparsed = parse(serialize(recorded));
    expect(canUseGlobal(reparsed, t0, cooldown)).toBe(false);
    expect(canUseGlobal(reparsed, t0 + cooldown, cooldown)).toBe(true);
  });
});

describe('emergencyPass: constants', () => {
  it('match the spec', () => {
    expect(PASS_DURATION_MS).toBe(120_000);
    expect(LOCKOUT_MS).toBe(86_400_000);
    expect(GLOBAL_KEY).toBe('*');
  });
});
