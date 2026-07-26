import { describe, expect, it } from 'vitest';
import {
  CHARSET,
  DEFAULT_LENGTH,
  LENGTH_EASY,
  LENGTH_HARD,
  LENGTH_MEDIUM,
  forDisplay,
  generate,
  isWeakening,
  normalize,
  rawLength,
  verify,
} from '../../src/core/strictMode';
import {
  DEFAULT_SETTINGS,
  structuredCloneSettings,
  type NudgeSettings,
  type SiteRule,
} from '../../src/core/settingsSchema';

// Confusable glyphs the charset must never contain, and generate() must never produce.
const CONFUSABLE = ['0', 'O', '1', 'l', 'I'];

describe('strictMode: constants', () => {
  it('length presets match the spec', () => {
    expect(LENGTH_EASY).toBe(12);
    expect(LENGTH_MEDIUM).toBe(24);
    expect(LENGTH_HARD).toBe(48);
    expect(DEFAULT_LENGTH).toBe(LENGTH_MEDIUM);
  });

  it('charset itself excludes confusable characters (tests the CLASS, not a sample)', () => {
    for (const forbidden of CONFUSABLE) {
      expect(CHARSET.includes(forbidden)).toBe(false);
    }
  });

  it('charset has no duplicate characters', () => {
    expect(new Set(CHARSET).size).toBe(CHARSET.length);
  });
});

describe('strictMode: generate()', () => {
  it('returns the requested raw length', () => {
    expect(generate(12).length).toBe(12);
    expect(generate(24).length).toBe(24);
    expect(generate(48).length).toBe(48);
  });

  it('uses only the unambiguous charset, over a large sample', () => {
    // Generate a large sample so any forbidden char would almost certainly appear if allowed.
    let sample = '';
    for (let i = 0; i < 50; i++) {
      sample += generate(48);
    }
    for (const forbidden of CONFUSABLE) {
      expect(sample.includes(forbidden)).toBe(false);
    }
    for (const ch of sample) {
      expect(CHARSET.includes(ch)).toBe(true);
    }
  });

  it('produces different strings across calls (real crypto randomness)', () => {
    const a = generate(24);
    const b = generate(24);
    const c = generate(24);
    // Collision of two 24-char strings from a 57-char alphabet is astronomically unlikely.
    expect(a).not.toBe(b);
    expect(b).not.toBe(c);
    expect(a).not.toBe(c);
  });

  it('coerces non-positive length to at least one char', () => {
    expect(generate(0).length).toBe(1);
    expect(generate(-5).length).toBe(1);
  });

  it('coerces a fractional length by truncating', () => {
    expect(generate(5.9).length).toBe(5);
  });

  it('is injectable: a deterministic randomInts source produces a deterministic string', () => {
    // Fixed sequence of indices into CHARSET — proves generate() defers entirely to the
    // injected source rather than mixing in its own randomness.
    const indices = [0, 1, 2, CHARSET.length - 1];
    const fixed = (count: number, maxExclusive: number): number[] => {
      expect(maxExclusive).toBe(CHARSET.length);
      expect(count).toBe(indices.length);
      return indices;
    };
    const result = generate(indices.length, fixed);
    const expected = indices.map((i) => CHARSET.charAt(i)).join('');
    expect(result).toBe(expected);
  });

  it('injected randomInts is called with the coerced length, not the raw input', () => {
    let calledWith: [number, number] | null = null;
    const spy = (count: number, maxExclusive: number): number[] => {
      calledWith = [count, maxExclusive];
      return new Array(count).fill(0);
    };
    generate(-5, spy);
    expect(calledWith).toEqual([1, CHARSET.length]);
  });
});

describe('strictMode: forDisplay()', () => {
  it('groups into dash-separated chunks of five', () => {
    expect(forDisplay('abcdefghijklmno')).toBe('abcde-fghij-klmno');
  });

  it('handles a partial final group', () => {
    expect(forDisplay('abcdefg')).toBe('abcde-fg');
  });

  it('handles a string shorter than one group', () => {
    expect(forDisplay('abc')).toBe('abc');
  });

  it('handles the empty string', () => {
    expect(forDisplay('')).toBe('');
  });
});

describe('strictMode: verify()', () => {
  it('true on exact match', () => {
    expect(verify('aB3kP', 'aB3kP')).toBe(true);
  });

  it('false on wrong character', () => {
    expect(verify('aB3kQ', 'aB3kP')).toBe(false);
  });

  it('is case sensitive', () => {
    expect(verify('ab3kp', 'aB3kP')).toBe(false);
    expect(verify('AB3KP', 'aB3kP')).toBe(false);
  });

  it('false on truncated input', () => {
    expect(verify('aB3k', 'aB3kP')).toBe(false);
  });

  it('ignores display dashes on either side', () => {
    // Target shown grouped; user types raw.
    expect(verify('abcdefghij', 'abcde-fghij')).toBe(true);
    // User types with the dashes too.
    expect(verify('abcde-fghij', 'abcde-fghij')).toBe(true);
    // Both raw.
    expect(verify('abcdefghij', 'abcdefghij')).toBe(true);
  });

  it('is dash-insensitive in BOTH directions against a grouped target', () => {
    // The bug this guards: the target is displayed dash-grouped and the copy no longer
    // promises "exactly", so typing WITH or WITHOUT dashes must both pass.
    const raw = 'k7Qm2vX9pLtR';
    const grouped = forDisplay(raw); // "k7Qm2-vX9pL-tR"
    // Typed without dashes.
    expect(verify(raw, grouped)).toBe(true);
    // Typed with the same dashes shown.
    expect(verify(grouped, grouped)).toBe(true);
    // Typed with dashes in different (user-chosen) positions — still passes.
    expect(verify('k7-Qm2vX9-pLtR', grouped)).toBe(true);
    // Wrong content with the right dashes must still fail (dashes don't mask a typo).
    expect(verify('k7Qm2-vX9pL-tX', grouped)).toBe(false);
  });

  it('trims surrounding whitespace', () => {
    expect(verify('  aB3kP  ', 'aB3kP')).toBe(true);
    expect(verify('\taB3kP\n', 'aB3kP')).toBe(true);
  });

  it('false when internal whitespace breaks the match', () => {
    // Internal space is NOT stripped — an accidental mid-string space must fail.
    expect(verify('aB 3kP', 'aB3kP')).toBe(false);
  });

  it('false on empty target', () => {
    expect(verify('', '')).toBe(false);
    expect(verify('anything', '')).toBe(false);
  });
});

describe('strictMode: normalize() / rawLength() — the unit the live counter and verify share', () => {
  it('normalize strips dashes and surrounding whitespace but keeps case and inner content', () => {
    expect(normalize('  k7Qm2-vX9pL  ')).toBe('k7Qm2vX9pL');
    expect(normalize('k7Qm2vX9pL')).toBe('k7Qm2vX9pL');
  });

  it('normalize does NOT strip internal whitespace', () => {
    expect(normalize('aB 3kP')).toBe('aB 3kP');
  });

  it('rawLength counts the same characters with or without dashes', () => {
    const raw = 'k7Qm2vX9pLtR'; // 12 raw chars
    expect(rawLength(raw)).toBe(12);
    expect(rawLength(forDisplay(raw))).toBe(12);
    expect(rawLength(`  ${raw}  `)).toBe(12);
    // Partial input progresses in raw units regardless of dashes the user typed.
    expect(rawLength('k7Qm2-vX')).toBe(7);
  });

  it('normalize + rawLength agree with verify: equal normalized forms always verify true', () => {
    const cases = ['abcdefghij', 'abcde-fghij', '  abcde-fghij  ', 'a-b-c-d-e-f-g-h-i-j'];
    const target = 'abcdefghij';
    for (const candidate of cases) {
      expect(normalize(candidate)).toBe(normalize(target));
      expect(rawLength(candidate)).toBe(rawLength(target));
      expect(verify(candidate, target)).toBe(true);
    }
  });

  it('normalize + rawLength agree with verify: differing normalized forms always verify false', () => {
    const target = 'abcdefghij';
    const candidate = 'abcdefghix'; // last char differs
    expect(normalize(candidate)).not.toBe(normalize(target));
    expect(verify(candidate, target)).toBe(false);
  });
});

// ── isWeakening ──

function baseSettings(overrides: Partial<NudgeSettings> = {}): NudgeSettings {
  return { ...structuredCloneSettings(DEFAULT_SETTINGS), ...overrides };
}

function makeRule(overrides: Partial<SiteRule> = {}): SiteRule {
  return {
    id: 'r1',
    domain: 'example.com',
    mode: 'DELAY',
    delaySeconds: 15,
    dailyLimitMinutes: null,
    enabled: true,
    createdAt: 0,
    showTimeRemaining: false,
    schedule: null,
    ...overrides,
  };
}

describe('strictMode: isWeakening() — globalEnabled axis', () => {
  it('true -> false is weakening', () => {
    const oldS = baseSettings({ globalEnabled: true });
    const newS = baseSettings({ globalEnabled: false });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('false -> true is not weakening', () => {
    const oldS = baseSettings({ globalEnabled: false });
    const newS = baseSettings({ globalEnabled: true });
    expect(isWeakening(oldS, newS)).toBe(false);
  });

  it('unchanged is not weakening', () => {
    const oldS = baseSettings({ globalEnabled: true });
    const newS = baseSettings({ globalEnabled: true });
    expect(isWeakening(oldS, newS)).toBe(false);
  });
});

describe('strictMode: isWeakening() — strictMode.enabled axis', () => {
  it('true -> false is weakening', () => {
    const oldS = baseSettings({ strictMode: { enabled: true, challengeLength: 24 } });
    const newS = baseSettings({ strictMode: { enabled: false, challengeLength: 24 } });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('false -> true is not weakening', () => {
    const oldS = baseSettings({ strictMode: { enabled: false, challengeLength: 24 } });
    const newS = baseSettings({ strictMode: { enabled: true, challengeLength: 24 } });
    expect(isWeakening(oldS, newS)).toBe(false);
  });
});

describe('strictMode: isWeakening() — rule removed / added axis', () => {
  it('removing an existing rule is weakening', () => {
    const oldS = baseSettings({ rules: [makeRule()] });
    const newS = baseSettings({ rules: [] });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('adding a brand-new rule is NOT weakening (strengthening)', () => {
    const oldS = baseSettings({ rules: [] });
    const newS = baseSettings({ rules: [makeRule()] });
    expect(isWeakening(oldS, newS)).toBe(false);
  });

  it('an unchanged rule set is not weakening', () => {
    const oldS = baseSettings({ rules: [makeRule()] });
    const newS = baseSettings({ rules: [makeRule()] });
    expect(isWeakening(oldS, newS)).toBe(false);
  });
});

describe('strictMode: isWeakening() — rule disabled axis', () => {
  it('disabling a rule is weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ enabled: true })] });
    const newS = baseSettings({ rules: [makeRule({ enabled: false })] });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('enabling a rule is not weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ enabled: false })] });
    const newS = baseSettings({ rules: [makeRule({ enabled: true })] });
    expect(isWeakening(oldS, newS)).toBe(false);
  });
});

describe('strictMode: isWeakening() — rule mode axis', () => {
  it('HARD_BLOCK -> DELAY is weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ mode: 'HARD_BLOCK' })] });
    const newS = baseSettings({ rules: [makeRule({ mode: 'DELAY' })] });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('DELAY -> HARD_BLOCK is not weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ mode: 'DELAY' })] });
    const newS = baseSettings({ rules: [makeRule({ mode: 'HARD_BLOCK' })] });
    expect(isWeakening(oldS, newS)).toBe(false);
  });

  it('DELAY -> BREATHING is weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ mode: 'DELAY' })] });
    const newS = baseSettings({ rules: [makeRule({ mode: 'BREATHING' })] });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('BREATHING -> DELAY is not weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ mode: 'BREATHING' })] });
    const newS = baseSettings({ rules: [makeRule({ mode: 'DELAY' })] });
    expect(isWeakening(oldS, newS)).toBe(false);
  });
});

describe('strictMode: isWeakening() — rule delaySeconds axis', () => {
  it('shorter delay is weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ delaySeconds: 30 })] });
    const newS = baseSettings({ rules: [makeRule({ delaySeconds: 15 })] });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('longer delay is not weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ delaySeconds: 15 })] });
    const newS = baseSettings({ rules: [makeRule({ delaySeconds: 30 })] });
    expect(isWeakening(oldS, newS)).toBe(false);
  });
});

describe('strictMode: isWeakening() — rule dailyLimitMinutes axis', () => {
  it('lower daily limit is not weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ dailyLimitMinutes: 60 })] });
    const newS = baseSettings({ rules: [makeRule({ dailyLimitMinutes: 30 })] });
    expect(isWeakening(oldS, newS)).toBe(false);
  });

  it('higher daily limit is weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ dailyLimitMinutes: 30 })] });
    const newS = baseSettings({ rules: [makeRule({ dailyLimitMinutes: 60 })] });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('removing an existing daily limit is weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ dailyLimitMinutes: 30 })] });
    const newS = baseSettings({ rules: [makeRule({ dailyLimitMinutes: null })] });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('adding a daily limit where none existed is not weakening', () => {
    const oldS = baseSettings({ rules: [makeRule({ dailyLimitMinutes: null })] });
    const newS = baseSettings({ rules: [makeRule({ dailyLimitMinutes: 30 })] });
    expect(isWeakening(oldS, newS)).toBe(false);
  });
});

describe('strictMode: isWeakening() — emergencyPass.enabled axis', () => {
  it('false -> true is weakening (adding an escape hatch)', () => {
    const oldS = baseSettings({ emergencyPass: { enabled: false } });
    const newS = baseSettings({ emergencyPass: { enabled: true } });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('true -> false is not weakening', () => {
    const oldS = baseSettings({ emergencyPass: { enabled: true } });
    const newS = baseSettings({ emergencyPass: { enabled: false } });
    expect(isWeakening(oldS, newS)).toBe(false);
  });

  it('unchanged is not weakening', () => {
    const oldS = baseSettings({ emergencyPass: { enabled: true } });
    const newS = baseSettings({ emergencyPass: { enabled: true } });
    expect(isWeakening(oldS, newS)).toBe(false);
  });
});

describe('strictMode: isWeakening() — tempAllowMinutes axis', () => {
  it('increasing is weakening', () => {
    const oldS = baseSettings({ tempAllowMinutes: 10 });
    const newS = baseSettings({ tempAllowMinutes: 20 });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('decreasing is not weakening', () => {
    const oldS = baseSettings({ tempAllowMinutes: 20 });
    const newS = baseSettings({ tempAllowMinutes: 10 });
    expect(isWeakening(oldS, newS)).toBe(false);
  });

  it('unchanged is not weakening', () => {
    const oldS = baseSettings({ tempAllowMinutes: 10 });
    const newS = baseSettings({ tempAllowMinutes: 10 });
    expect(isWeakening(oldS, newS)).toBe(false);
  });
});

describe('strictMode: isWeakening() — youtube.shortsMode axis', () => {
  function withShorts(mode: 'INHERIT' | 'HARD_BLOCK' | 'DELAY' | 'BREATHING'): NudgeSettings {
    return baseSettings({
      youtube: { shortsMode: mode, hideShortsShelf: false, shortsDelaySeconds: 15 },
    });
  }

  it('HARD_BLOCK -> DELAY is weakening', () => {
    expect(isWeakening(withShorts('HARD_BLOCK'), withShorts('DELAY'))).toBe(true);
  });

  it('DELAY -> HARD_BLOCK is not weakening', () => {
    expect(isWeakening(withShorts('DELAY'), withShorts('HARD_BLOCK'))).toBe(false);
  });

  it('DELAY -> BREATHING is weakening', () => {
    expect(isWeakening(withShorts('DELAY'), withShorts('BREATHING'))).toBe(true);
  });

  it('BREATHING -> DELAY is not weakening', () => {
    expect(isWeakening(withShorts('BREATHING'), withShorts('DELAY'))).toBe(false);
  });

  it('DELAY -> INHERIT is weakening (INHERIT is the weakest rung)', () => {
    expect(isWeakening(withShorts('DELAY'), withShorts('INHERIT'))).toBe(true);
  });

  it('INHERIT -> DELAY is not weakening', () => {
    expect(isWeakening(withShorts('INHERIT'), withShorts('DELAY'))).toBe(false);
  });

  it('INHERIT -> INHERIT is not weakening', () => {
    expect(isWeakening(withShorts('INHERIT'), withShorts('INHERIT'))).toBe(false);
  });
});

describe('strictMode: isWeakening() — identity / mixed', () => {
  it('two identical, non-trivial settings objects are not weakening', () => {
    const settings = baseSettings({
      globalEnabled: true,
      rules: [makeRule({ id: 'r1' }), makeRule({ id: 'r2', domain: 'other.com' })],
      strictMode: { enabled: true, challengeLength: 48 },
      emergencyPass: { enabled: false },
      tempAllowMinutes: 15,
    });
    expect(isWeakening(settings, structuredCloneSettings(settings))).toBe(false);
  });

  it('weakening on one axis while strengthening another still counts as weakening', () => {
    // Stronger mode (DELAY -> HARD_BLOCK) but shorter delay (30 -> 5) on the same rule.
    const oldS = baseSettings({ rules: [makeRule({ mode: 'DELAY', delaySeconds: 30 })] });
    const newS = baseSettings({ rules: [makeRule({ mode: 'HARD_BLOCK', delaySeconds: 5 })] });
    expect(isWeakening(oldS, newS)).toBe(true);
  });

  it('multiple rules: one weakened among several unchanged still counts as weakening', () => {
    const oldS = baseSettings({
      rules: [makeRule({ id: 'r1', delaySeconds: 30 }), makeRule({ id: 'r2', delaySeconds: 30 })],
    });
    const newS = baseSettings({
      rules: [makeRule({ id: 'r1', delaySeconds: 30 }), makeRule({ id: 'r2', delaySeconds: 5 })],
    });
    expect(isWeakening(oldS, newS)).toBe(true);
  });
});
