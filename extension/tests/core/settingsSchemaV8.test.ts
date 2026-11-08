import { describe, expect, it } from 'vitest';

import { SCHEMA_VERSION, migrateSettings } from '../../src/core/settingsSchema';

/**
 * v7 -> v8: a schedule window can carry its own YouTube channel list. A v7 blob has no
 * `channels` on its schedule and must come out "same as default" (null): an upgrade never
 * changes which channels are allowed or blocked.
 */

const WINDOW = {
  enabled: true,
  days: [1, 2, 3, 4, 5],
  startMinute: 540,
  endMinute: 1020,
  mode: 'HARD_BLOCK',
  delaySeconds: 15,
};

function v7Blob(rule: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schemaVersion: 7,
    globalEnabled: true,
    onboardingComplete: true,
    rules: [
      {
        id: 'r1',
        domain: 'youtube.com',
        mode: 'ALLOW',
        delaySeconds: 15,
        dailyLimitMinutes: null,
        enabled: true,
        createdAt: 1,
        schedule: WINDOW,
        ...rule,
      },
    ],
    messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
    strictMode: { enabled: false, challengeLength: 24 },
    emergencyPass: { enabled: true },
    tempAllowMinutes: 10,
  };
}

/** The migrated `schedule.channels` of a rule whose stored window carried `channels`. */
const migratedChannels = (channels: unknown, domain = 'youtube.com') =>
  migrateSettings(v7Blob({ domain, schedule: { ...WINDOW, channels } })).rules[0]?.schedule
    ?.channels;

describe('settings schema v8', () => {
  it('stamps version 8', () => {
    expect(SCHEMA_VERSION).toBe(8);
    expect(migrateSettings(v7Blob()).schemaVersion).toBe(8);
  });

  it('migrates a v7 schedule to "same as default"', () => {
    const migrated = migrateSettings(v7Blob());
    expect(migrated.rules[0]?.schedule?.channels).toBeNull();
    // The rest of the window is untouched.
    expect(migrated.rules[0]?.schedule?.days).toEqual([1, 2, 3, 4, 5]);
  });

  it('round-trips a full override through migrate and JSON', () => {
    const channels = {
      channelMode: 'WHITELIST',
      channels: [
        { channelId: 'UCstudy0000000000000001', handle: 'study', displayName: 'Study', addedAt: 5 },
      ],
      allowSubscriptions: true,
    };
    const once = migrateSettings(v7Blob({ schedule: { ...WINDOW, channels } }));
    const again = migrateSettings(JSON.parse(JSON.stringify(once)));
    expect(again.rules[0]?.schedule?.channels).toEqual(channels);
    expect(again).toEqual(once);
  });

  it('drops malformed entries and merges duplicates', () => {
    const entry = { channelId: null, handle: 'study', displayName: 'Study', addedAt: 1 };
    const result = migratedChannels({
      channelMode: 'WHITELIST',
      channels: [entry, { ...entry, addedAt: 9 }, 'junk', null, { handle: 42 }],
      allowSubscriptions: false,
    });
    expect(result?.channels).toHaveLength(1);
    expect(result?.channels[0]?.handle).toBe('study');
  });

  it('reads an unknown channelMode as OFF', () => {
    const result = migratedChannels({
      channelMode: 'EVERYTHING',
      channels: [],
      allowSubscriptions: false,
    });
    expect(result?.channelMode).toBe('OFF');
  });

  it.each([
    ['true', true, true],
    ['the string "true"', 'true', false],
    ['1', 1, false],
    ['missing', undefined, false],
  ])('allowSubscriptions %s -> %s only for a literal true', (_label, stored, expected) => {
    const result = migratedChannels({
      channelMode: 'WHITELIST',
      channels: [],
      allowSubscriptions: stored,
    });
    expect(result?.allowSubscriptions).toBe(expected);
  });

  it.each(['reddit.com', 'x.com'])('drops an override on the non-YouTube rule %s', (domain) => {
    const stored = { channelMode: 'BLACKLIST', channels: [], allowSubscriptions: false };
    expect(migratedChannels(stored, domain)).toBeNull();
  });

  it('keeps a null schedule null', () => {
    expect(migrateSettings(v7Blob({ schedule: null })).rules[0]?.schedule).toBeNull();
  });
});
