import { describe, expect, it } from 'vitest';

import {
  DAILY_LIMIT_MAX_MINUTES,
  DAILY_LIMIT_MIN_MINUTES,
  DEFAULT_SETTINGS,
  DELAY_MAX_SECONDS,
  DELAY_MIN_SECONDS,
  migrateSettings,
  SCHEMA_VERSION,
  TEMP_ALLOW_MAX_MINUTES,
  TEMP_ALLOW_MIN_MINUTES,
} from '../../src/core/settingsSchema';

/**
 * migrateSettings must be TOTAL (never throws) and LENIENT (corrupt input falls back to
 * safe defaults) — it sits between chrome.storage and the block path, so a bad read must
 * never break enforcement. It also always fails toward the SAFE/enforcing direction
 * (globalEnabled true, strictMode/AIGC-style toggles false unless explicit).
 */

describe('migrateSettings — totality on garbage top-level input', () => {
  const garbageInputs: Array<[string, unknown]> = [
    ['null', null],
    ['undefined', undefined],
    ['a string', 'not settings'],
    ['a number', 42],
    ['an empty array', []],
    ['a non-empty array', [1, 2, 3]],
  ];

  it.each(garbageInputs)('%s never throws and falls back to valid DEFAULT_SETTINGS', (_label, input) => {
    expect(() => migrateSettings(input)).not.toThrow();
    expect(migrateSettings(input)).toEqual(DEFAULT_SETTINGS);
  });
});

describe('migrateSettings — rule.mode fallback', () => {
  it('an unknown/garbage mode falls back to HARD_BLOCK', () => {
    const result = migrateSettings({ rules: [{ domain: 'x.com', mode: 'NUKE_FROM_ORBIT' }] });
    expect(result.rules[0]?.mode).toBe('HARD_BLOCK');
  });
});

describe('migrateSettings — clamping to documented ranges', () => {
  it('clamps delaySeconds below the minimum up to 1', () => {
    const result = migrateSettings({ rules: [{ domain: 'x.com', delaySeconds: -50 }] });
    expect(result.rules[0]?.delaySeconds).toBe(DELAY_MIN_SECONDS);
  });

  it('clamps delaySeconds above the maximum down to 300', () => {
    const result = migrateSettings({ rules: [{ domain: 'x.com', delaySeconds: 99_999 }] });
    expect(result.rules[0]?.delaySeconds).toBe(DELAY_MAX_SECONDS);
  });

  it('clamps dailyLimitMinutes below the minimum up to 1', () => {
    const result = migrateSettings({ rules: [{ domain: 'x.com', dailyLimitMinutes: -5 }] });
    expect(result.rules[0]?.dailyLimitMinutes).toBe(DAILY_LIMIT_MIN_MINUTES);
  });

  it('clamps dailyLimitMinutes above the maximum down to 480', () => {
    const result = migrateSettings({ rules: [{ domain: 'x.com', dailyLimitMinutes: 99_999 }] });
    expect(result.rules[0]?.dailyLimitMinutes).toBe(DAILY_LIMIT_MAX_MINUTES);
  });

  it('leaves dailyLimitMinutes as null when not a number', () => {
    const result = migrateSettings({ rules: [{ domain: 'x.com' }] });
    expect(result.rules[0]?.dailyLimitMinutes).toBeNull();
  });

  it('clamps tempAllowMinutes to 1-60', () => {
    expect(migrateSettings({ tempAllowMinutes: 0 }).tempAllowMinutes).toBe(TEMP_ALLOW_MIN_MINUTES);
    expect(migrateSettings({ tempAllowMinutes: -5 }).tempAllowMinutes).toBe(TEMP_ALLOW_MIN_MINUTES);
    expect(migrateSettings({ tempAllowMinutes: 9_999 }).tempAllowMinutes).toBe(TEMP_ALLOW_MAX_MINUTES);
  });
});

describe('migrateSettings — rule dropping', () => {
  it('drops rules with a missing or blank domain instead of keeping them broken', () => {
    const result = migrateSettings({
      rules: [
        { domain: 'good.com' },
        { domain: '' },
        { domain: '   ' },
        { mode: 'HARD_BLOCK' }, // no domain field at all
        null,
        'not an object',
        42,
      ],
    });
    expect(result.rules).toHaveLength(1);
    expect(result.rules[0]?.domain).toBe('good.com');
  });

  it('lowercases and trims domains', () => {
    const result = migrateSettings({ rules: [{ domain: '  YouTube.COM  ' }] });
    expect(result.rules[0]?.domain).toBe('youtube.com');
  });
});

describe('migrateSettings — globalEnabled fails toward enforcing', () => {
  it('defaults to true when absent', () => {
    expect(migrateSettings({}).globalEnabled).toBe(true);
  });

  it('becomes false only on an explicit boolean false', () => {
    expect(migrateSettings({ globalEnabled: false }).globalEnabled).toBe(false);
  });

  it('stays true for any other (non-false) value', () => {
    expect(migrateSettings({ globalEnabled: 'no' }).globalEnabled).toBe(true);
    expect(migrateSettings({ globalEnabled: 0 }).globalEnabled).toBe(true);
    expect(migrateSettings({ globalEnabled: null }).globalEnabled).toBe(true);
  });
});

describe('migrateSettings — strictMode.enabled defaults false', () => {
  it('defaults to false when absent', () => {
    expect(migrateSettings({}).strictMode.enabled).toBe(false);
  });

  it('is true only on an explicit boolean true', () => {
    expect(migrateSettings({ strictMode: { enabled: true } }).strictMode.enabled).toBe(true);
    expect(migrateSettings({ strictMode: { enabled: 'true' } }).strictMode.enabled).toBe(false);
    expect(migrateSettings({ strictMode: { enabled: 1 } }).strictMode.enabled).toBe(false);
  });
});

describe('migrateSettings — emergencyPass.enabled defaults true', () => {
  it('defaults to true when absent', () => {
    expect(migrateSettings({}).emergencyPass.enabled).toBe(true);
  });

  it('becomes false only on an explicit boolean false', () => {
    expect(migrateSettings({ emergencyPass: { enabled: false } }).emergencyPass.enabled).toBe(false);
    expect(migrateSettings({ emergencyPass: { enabled: 'nope' } }).emergencyPass.enabled).toBe(true);
  });
});

describe('migrateSettings — schedule sub-object coercion', () => {
  it('filters invalid day numbers (0, 8, 1.5, "mon"), keeping valid ISO days', () => {
    const result = migrateSettings({
      rules: [{ domain: 'x.com', schedule: { days: [0, 8, 1.5, 'mon', 3] } }],
    });
    expect(result.rules[0]?.schedule?.days).toEqual([3]);
  });

  it('an empty resulting day list becomes null, not []', () => {
    const result = migrateSettings({
      rules: [{ domain: 'x.com', schedule: { days: [0, 8, 1.5, 'mon'] } }],
    });
    expect(result.rules[0]?.schedule?.days).toBeNull();
  });

  it('clamps startMinute and endMinute to 0-1439', () => {
    const result = migrateSettings({
      rules: [{ domain: 'x.com', schedule: { startMinute: -100, endMinute: 5000 } }],
    });
    expect(result.rules[0]?.schedule?.startMinute).toBe(0);
    expect(result.rules[0]?.schedule?.endMinute).toBe(1439);
  });

  it('a missing schedule stays null', () => {
    const result = migrateSettings({ rules: [{ domain: 'x.com' }] });
    expect(result.rules[0]?.schedule).toBeNull();
  });
});

describe('migrateSettings — schemaVersion', () => {
  it('is always stamped to the current SCHEMA_VERSION regardless of input', () => {
    expect(migrateSettings({ schemaVersion: 999 }).schemaVersion).toBe(SCHEMA_VERSION);
    expect(migrateSettings({ schemaVersion: 'garbage' }).schemaVersion).toBe(SCHEMA_VERSION);
    expect(migrateSettings({}).schemaVersion).toBe(SCHEMA_VERSION);
  });
});

describe('migrateSettings — idempotence', () => {
  it('migrating a garbage input twice is stable', () => {
    const garbage = {
      rules: 'not an array',
      globalEnabled: 'nope',
      strictMode: 42,
      schedule: { days: [0, 99, 'mon'] },
    };
    const once = migrateSettings(garbage);
    const twice = migrateSettings(once);
    expect(twice).toEqual(once);
  });

  it('migrating an already-valid settings object twice is stable', () => {
    const valid = migrateSettings({
      rules: [
        {
          domain: 'youtube.com',
          mode: 'DELAY',
          delaySeconds: 20,
          dailyLimitMinutes: 45,
          enabled: true,
          showTimeRemaining: true,
          schedule: {
            enabled: true,
            days: [1, 2, 3],
            startMinute: 60,
            endMinute: 120,
            mode: 'BREATHING',
            delaySeconds: 30,
          },
        },
      ],
      globalEnabled: false,
      onboardingComplete: true,
      strictMode: { enabled: true, challengeLength: 12 },
      emergencyPass: { enabled: false },
      youtube: { shortsMode: 'DELAY', hideShortsShelf: true, shortsDelaySeconds: 10 },
      tempAllowMinutes: 5,
    });
    const twice = migrateSettings(valid);
    expect(twice).toEqual(valid);
  });
});
