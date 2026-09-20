import { describe, expect, it } from 'vitest';

import {
  DAILY_COUNT_MAX_ITEMS,
  DAILY_COUNT_MIN_ITEMS,
  DAILY_LIMIT_MAX_MINUTES,
  DAILY_LIMIT_MIN_MINUTES,
  DEFAULT_SETTINGS,
  DELAY_MAX_SECONDS,
  DELAY_MIN_SECONDS,
  migrateSettings,
  SCHEMA_VERSION,
  TEMP_ALLOW_MAX_MINUTES,
  TEMP_ALLOW_MIN_MINUTES,
  type NudgeSettings,
  type SiteRule,
  type YoutubeFeatureSettings,
} from '../../src/core/settingsSchema';

/** The youtube.com rule a migration produced. Throws loudly rather than returning undefined. */
function youtubeRule(migrated: NudgeSettings): SiteRule {
  const rule = migrated.rules.find((r) => r.domain === 'youtube.com');
  if (rule === undefined) throw new Error('expected a youtube.com rule after migration');
  return rule;
}

function youtubeFeatures(migrated: NudgeSettings): YoutubeFeatureSettings {
  const yt = youtubeRule(migrated).features?.youtube;
  if (yt === undefined) throw new Error('expected YouTube features on the youtube.com rule');
  return yt;
}

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

/**
 * `dailyLimitCount` (v0.3), the gate's independent item-count budget. Mirrors the
 * dailyLimitMinutes clamping cases above exactly — same shape of bug, same fix — plus the
 * one case that IS the v3 -> v4 migration step itself: a gate object with no
 * `dailyLimitCount` key at all (every v3 blob in the wild) must read as null, never invent
 * a limit the user never set. `youtube.com` is used here (rather than `x.com` above)
 * because only a known-platform domain materializes a `features.gates` object to clamp.
 */
describe('migrateSettings — dailyLimitCount clamping (v0.3)', () => {
  it('clamps a gate’s dailyLimitCount below the minimum up to 1', () => {
    const result = migrateSettings({
      rules: [{ domain: 'youtube.com', features: { gates: { shorts: { dailyLimitCount: -5 } } } }],
    });
    expect(result.rules[0]?.features?.gates.shorts?.dailyLimitCount).toBe(DAILY_COUNT_MIN_ITEMS);
  });

  it('clamps a gate’s dailyLimitCount above the maximum down to 500', () => {
    const result = migrateSettings({
      rules: [
        { domain: 'youtube.com', features: { gates: { shorts: { dailyLimitCount: 99_999 } } } },
      ],
    });
    expect(result.rules[0]?.features?.gates.shorts?.dailyLimitCount).toBe(DAILY_COUNT_MAX_ITEMS);
  });

  it('reads a non-numeric dailyLimitCount as null rather than inventing a limit', () => {
    const result = migrateSettings({
      rules: [
        { domain: 'youtube.com', features: { gates: { shorts: { dailyLimitCount: 'twenty' } } } },
      ],
    });
    expect(result.rules[0]?.features?.gates.shorts?.dailyLimitCount).toBeNull();
  });

  it('leaves dailyLimitCount as null when the field is entirely absent — this IS the v3 -> v4 step', () => {
    const result = migrateSettings({
      rules: [{ domain: 'youtube.com', features: { gates: { shorts: {} } } }],
    });
    expect(result.rules[0]?.features?.gates.shorts?.dailyLimitCount).toBeNull();
  });

  it('clamps independently of dailyLimitMinutes — the two axes never interfere with each other', () => {
    const result = migrateSettings({
      rules: [
        {
          domain: 'youtube.com',
          features: {
            gates: { shorts: { dailyLimitMinutes: 999, dailyLimitCount: -1 } },
          },
        },
      ],
    });
    expect(result.rules[0]?.features?.gates.shorts?.dailyLimitMinutes).toBe(DAILY_LIMIT_MAX_MINUTES);
    expect(result.rules[0]?.features?.gates.shorts?.dailyLimitCount).toBe(DAILY_COUNT_MIN_ITEMS);
  });
});

/**
 * v3 -> v4 (v0.3): the count axis is purely additive, so a v3 blob (no `dailyLimitCount`
 * anywhere) must migrate to schemaVersion 4 with every gate's count null, and everything
 * else the v3 user had configured preserved exactly.
 */
describe('migrateSettings — v3 -> v4 (COUNT budgets)', () => {
  /** A realistic v3 blob: schemaVersion 3, gate settings with no `dailyLimitCount` field. */
  const V3_SETTINGS = {
    schemaVersion: 3,
    globalEnabled: true,
    onboardingComplete: true,
    rules: [
      {
        id: 'rule-youtube.com',
        domain: 'youtube.com',
        mode: 'ALLOW',
        delaySeconds: 15,
        dailyLimitMinutes: null,
        enabled: true,
        createdAt: 1_700_000_000_000,
        showTimeRemaining: false,
        schedule: null,
        grayscale: false,
        features: {
          platform: 'youtube',
          gates: {
            shorts: { mode: 'HARD_BLOCK', delaySeconds: 15, dailyLimitMinutes: 30 },
          },
          hides: { comments: true },
          youtube: {
            channelMode: 'OFF',
            channels: [],
            channelBlockMode: 'DELAY',
            channelDelaySeconds: 15,
            disableAutoplay: false,
          },
        },
      },
    ],
    messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
    strictMode: { enabled: false, challengeLength: 24 },
    emergencyPass: { enabled: true },
    tempAllowMinutes: 10,
  };

  it('round-trips a v3 blob to v4 with the gate’s dailyLimitCount read as null', () => {
    const migrated = migrateSettings(V3_SETTINGS);
    expect(migrated.schemaVersion).toBe(4);
    expect(migrated.schemaVersion).toBe(SCHEMA_VERSION);
    expect(migrated.rules[0]?.features?.gates.shorts).toMatchObject({
      mode: 'HARD_BLOCK',
      delaySeconds: 15,
      dailyLimitMinutes: 30,
      dailyLimitCount: null,
    });
  });

  it('keeps every field the v3 user had already set, undisturbed by the new axis', () => {
    const migrated = migrateSettings(V3_SETTINGS);
    expect(migrated.rules[0]?.features?.hides.comments).toBe(true);
    expect(migrated.rules[0]?.mode).toBe('ALLOW');
    expect(migrated.rules[0]?.id).toBe('rule-youtube.com');
  });

  it('is idempotent — migrating the v3 -> v4 result again changes nothing', () => {
    const once = migrateSettings(V3_SETTINGS);
    expect(migrateSettings(once)).toEqual(once);
  });
});

describe('migrateSettings — a v4 blob round-trips unchanged', () => {
  it('preserves a configured dailyLimitCount across a second migration', () => {
    const v4 = migrateSettings({
      schemaVersion: 4,
      rules: [
        {
          domain: 'youtube.com',
          mode: 'ALLOW',
          features: {
            platform: 'youtube',
            gates: {
              shorts: { mode: 'OFF', delaySeconds: 15, dailyLimitMinutes: null, dailyLimitCount: 20 },
            },
          },
        },
      ],
    });
    expect(v4.rules[0]?.features?.gates.shorts?.dailyLimitCount).toBe(20);

    const again = migrateSettings(v4);
    expect(again).toEqual(v4);
  });

  it('preserves a null dailyLimitCount (no count configured) across a second migration', () => {
    const v4 = migrateSettings({
      schemaVersion: 4,
      rules: [{ domain: 'tiktok.com', mode: 'HARD_BLOCK' }],
    });
    expect(v4.rules[0]?.features?.gates.foryou?.dailyLimitCount).toBeNull();
    expect(migrateSettings(v4)).toEqual(v4);
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

describe('upgrading an existing user from schema v1', () => {
  /**
   * The realistic shape stored by the shipped v1 release: no channel list, no gray-screen,
   * no hide toggles, those fields simply did not exist yet.
   */
  const V1_SETTINGS = {
    schemaVersion: 1,
    globalEnabled: true,
    onboardingComplete: true,
    rules: [
      {
        id: 'rule-youtube.com',
        domain: 'youtube.com',
        mode: 'DELAY',
        delaySeconds: 30,
        dailyLimitMinutes: 45,
        enabled: true,
        createdAt: 1_700_000_000_000,
        showTimeRemaining: true,
        schedule: null,
      },
    ],
    messages: {
      delayTitles: ['My own title'],
      delaySubtitles: [],
      hardBlockMessages: [],
    },
    strictMode: { enabled: true, challengeLength: 48 },
    emergencyPass: { enabled: false },
    youtube: { shortsMode: 'HARD_BLOCK', hideShortsShelf: true, shortsDelaySeconds: 20 },
    tempAllowMinutes: 25,
  };

  it('keeps every setting the user had already chosen', () => {
    const migrated = migrateSettings(V1_SETTINGS);

    expect(migrated.rules).toHaveLength(1);
    expect(migrated.rules[0]).toMatchObject({
      domain: 'youtube.com',
      mode: 'DELAY',
      delaySeconds: 30,
      dailyLimitMinutes: 45,
      showTimeRemaining: true,
    });
    expect(migrated.messages.delayTitles).toEqual(['My own title']);
    expect(migrated.strictMode).toEqual({ enabled: true, challengeLength: 48 });
    expect(migrated.emergencyPass.enabled).toBe(false);
    expect(migrated.tempAllowMinutes).toBe(25);
    // The old top-level YouTube block now lives on the youtube.com rule as features.
    const features = youtubeRule(migrated).features;
    expect(features?.gates.shorts).toMatchObject({ mode: 'HARD_BLOCK', delaySeconds: 20 });
    expect(features?.hides.shortsShelf).toBe(true);
  });

  it('leaves every new feature switched off, so upgrading changes nothing the user sees', () => {
    const migrated = migrateSettings(V1_SETTINGS);

    const rule = youtubeRule(migrated);
    expect(youtubeFeatures(migrated).channelMode).toBe('OFF');
    expect(youtubeFeatures(migrated).channels).toEqual([]);
    expect(youtubeFeatures(migrated).disableAutoplay).toBe(false);
    expect(rule.grayscale).toBe(false);
    expect(rule.features?.hides.homeFeed).toBe(false);
    expect(rule.features?.hides.sidebarRecs).toBe(false);
    expect(rule.features?.hides.endScreen).toBe(false);
    expect(rule.features?.hides.comments).toBe(false);
  });

  it('is stamped as the current schema version', () => {
    expect(migrateSettings(V1_SETTINGS).schemaVersion).toBe(SCHEMA_VERSION);
  });

  it('does not change again on a second upgrade', () => {
    const once = migrateSettings(V1_SETTINGS);
    expect(migrateSettings(once)).toEqual(once);
  });

  it('drops a channel entry that carries no identifier at all, since it could never match', () => {
    const withJunk = {
      ...V1_SETTINGS,
      youtube: {
        ...V1_SETTINGS.youtube,
        channels: [
          { channelId: 'UCrealchannel00000000001', handle: null, displayName: 'Real', addedAt: 1 },
          { channelId: null, handle: null, displayName: 'Ghost', addedAt: 2 },
        ],
      },
    };

    const channels = youtubeFeatures(migrateSettings(withJunk)).channels;
    expect(channels).toHaveLength(1);
    expect(channels[0]?.displayName).toBe('Real');
  });

  it('merges two entries that describe the same channel by different identifiers', () => {
    const duplicated = {
      ...V1_SETTINGS,
      youtube: {
        ...V1_SETTINGS.youtube,
        channels: [
          { channelId: 'UCsamechannel00000000001', handle: null, displayName: '@veritasium', addedAt: 1 },
          { channelId: 'UCsamechannel00000000001', handle: 'veritasium', displayName: 'Veritasium', addedAt: 2 },
        ],
      },
    };

    const channels = youtubeFeatures(migrateSettings(duplicated)).channels;
    expect(channels).toHaveLength(1);
    // The merge fills in the identifier the first copy was missing, and prefers a real name.
    expect(channels[0]).toMatchObject({
      channelId: 'UCsamechannel00000000001',
      handle: 'veritasium',
      displayName: 'Veritasium',
    });
  });

  it('migrates all the way through to the CURRENT schema (v1 -> v4), not just as far as v2', () => {
    // A two-hop migration (v1's top-level youtube block folds into v3 shape, then the count
    // axis lands as v4) run from a single call — `migrateSettings` has no per-version branch
    // to get half right, but this pins the whole distance travelled in one assertion.
    const migrated = migrateSettings(V1_SETTINGS);
    expect(migrated.schemaVersion).toBe(4);
    expect(migrated.schemaVersion).toBe(SCHEMA_VERSION);
    // The v1 user never had a count axis to configure, so it reads null, not invented.
    expect(youtubeRule(migrated).features?.gates.shorts?.dailyLimitCount).toBeNull();
    // And the v1 -> v3 fold (shorts mode, delay, hide toggles) still landed correctly
    // alongside the new axis -- the YouTube fold is not disturbed by the v0.3 step riding
    // on top of it.
    expect(youtubeRule(migrated).features?.gates.shorts).toMatchObject({
      mode: 'HARD_BLOCK',
      delaySeconds: 20,
    });
  });
});
