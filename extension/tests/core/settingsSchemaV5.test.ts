import { describe, expect, it } from 'vitest';

import {
  SCHEMA_VERSION,
  migrateSettings,
  type ChannelEntry,
  type NudgeSettings,
  type SiteRule,
} from '../../src/core/settingsSchema';

/** v4 -> v5: creator mode. A v4 blob has neither new field and must come out switched OFF. */

const CHANNEL: ChannelEntry = {
  channelId: 'UCmine00000000000000001',
  handle: 'mine',
  displayName: 'Mine',
  addedAt: 5,
};

function youtubeRuleBlob(youtube: Record<string, unknown>): Record<string, unknown> {
  return {
    id: 'r1',
    domain: 'youtube.com',
    mode: 'HARD_BLOCK',
    delaySeconds: 15,
    dailyLimitMinutes: null,
    enabled: true,
    createdAt: 1,
    features: { platform: 'youtube', gates: {}, hides: {}, youtube },
  };
}

function blob(
  schemaVersion: number,
  rules: unknown[],
  extra: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    schemaVersion,
    globalEnabled: true,
    onboardingComplete: true,
    rules,
    messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
    strictMode: { enabled: false, challengeLength: 24 },
    emergencyPass: { enabled: true },
    tempAllowMinutes: 10,
    ...extra,
  };
}

const V4_YOUTUBE = {
  channelMode: 'WHITELIST',
  channels: [{ channelId: null, handle: 'veritasium', displayName: 'Veritasium', addedAt: 1 }],
  channelBlockMode: 'HARD_BLOCK',
  channelDelaySeconds: 40,
  disableAutoplay: true,
};

function youtubeOf(settings: NudgeSettings): NonNullable<NonNullable<SiteRule['features']>['youtube']> {
  const rule = settings.rules.find((r: SiteRule) => r.domain === 'youtube.com');
  const yt = rule?.features?.youtube;
  if (yt === undefined) throw new Error('expected youtube features');
  return yt;
}

describe('schema v5', () => {
  it('is version 5', () => {
    expect(SCHEMA_VERSION).toBe(5);
  });

  it('upgrades a v4 install with creator mode OFF and no channel, keeping its whitelist', () => {
    const migrated = migrateSettings(blob(4, [youtubeRuleBlob(V4_YOUTUBE)]));
    const yt = youtubeOf(migrated);
    expect(migrated.schemaVersion).toBe(5);
    expect(yt.creatorMode).toBe(false);
    expect(yt.myChannel).toBeNull();
    expect(yt.channelMode).toBe('WHITELIST');
    expect(yt.channels.map((c) => c.handle)).toEqual(['veritasium']);
  });

  it('migrating twice gives the same settings as migrating once', () => {
    const once = migrateSettings(blob(4, [youtubeRuleBlob(V4_YOUTUBE)]));
    expect(migrateSettings(once)).toEqual(once);
  });
});

describe('creator mode coercion: only a literal true turns it on', () => {
  it.each([['yes'], [1], ['true'], [null], [{}]])('reads creatorMode %j as OFF', (value) => {
    const migrated = migrateSettings(
      blob(5, [youtubeRuleBlob({ ...V4_YOUTUBE, creatorMode: value })]),
    );
    expect(youtubeOf(migrated).creatorMode).toBe(false);
  });

  it('keeps creatorMode true when it is literally true', () => {
    const migrated = migrateSettings(
      blob(5, [youtubeRuleBlob({ ...V4_YOUTUBE, creatorMode: true })]),
    );
    expect(youtubeOf(migrated).creatorMode).toBe(true);
  });

  it.each([[{}], ['mine'], [42], [{ displayName: 'x' }]])(
    'drops a malformed myChannel %j instead of keeping a channel that matches nothing',
    (value) => {
      const migrated = migrateSettings(
        blob(5, [youtubeRuleBlob({ ...V4_YOUTUBE, creatorMode: true, myChannel: value })]),
      );
      expect(youtubeOf(migrated).myChannel).toBeNull();
    },
  );

  it('round-trips a valid own channel', () => {
    const migrated = migrateSettings(
      blob(5, [youtubeRuleBlob({ ...V4_YOUTUBE, creatorMode: true, myChannel: CHANNEL })]),
    );
    expect(youtubeOf(migrated).myChannel).toEqual(CHANNEL);
    expect(migrateSettings(migrated)).toEqual(migrated);
  });
});

/**
 * mergeFeatures is internal; it is reached by folding a legacy top-level `youtube` block into
 * an existing youtube.com rule. The legacy side never has creator mode.
 */
describe('folding a legacy YouTube block into a rule that has creator mode', () => {
  const legacy = {
    shortsMode: 'HARD_BLOCK',
    hideShortsShelf: false,
    shortsDelaySeconds: 15,
    channelMode: 'OFF',
    channels: [],
    channelBlockMode: 'DELAY',
    channelDelaySeconds: 15,
    grayScreen: false,
    hideHomeFeed: false,
    hideSidebarRecs: false,
    hideEndScreen: false,
    hideComments: false,
    disableAutoplay: false,
  };

  it('does not keep creator mode on (the more restrictive side wins) but keeps the own channel', () => {
    const rule = youtubeRuleBlob({ ...V4_YOUTUBE, creatorMode: true, myChannel: CHANNEL });
    const migrated = migrateSettings(blob(2, [rule], { youtube: legacy }));
    const yt = youtubeOf(migrated);
    expect(yt.creatorMode).toBe(false);
    expect(yt.myChannel).toEqual(CHANNEL);
  });
});
