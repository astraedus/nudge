import { describe, expect, it } from 'vitest';

import {
  SCHEMA_VERSION,
  migrateSettings,
  withSubscribedChannels,
  type ChannelEntry,
  type NudgeSettings,
  type SiteRule,
} from '../../src/core/settingsSchema';
import { featuresWith, settings, siteRule } from '../helpers/rules';

/**
 * v5 -> v6: "Also allow channels I'm subscribed to". A v5 blob has no `allowSubscriptions`
 * and must come out switched OFF: an upgrade never opens a channel the user did not choose.
 */

const SUBSCRIBED: ChannelEntry = {
  channelId: 'UCHnyfMqiRRG1u-2MsSQLbXA',
  handle: 'veritasium',
  displayName: 'Veritasium',
  addedAt: 9,
};

const V5_YOUTUBE = {
  channelMode: 'WHITELIST',
  channels: [{ channelId: null, handle: 'kurzgesagt', displayName: 'Kurzgesagt', addedAt: 1 }],
  channelBlockMode: 'HARD_BLOCK',
  channelDelaySeconds: 40,
  disableAutoplay: true,
  creatorMode: false,
  myChannel: null,
};

function v5Blob(youtube: Record<string, unknown> = V5_YOUTUBE): Record<string, unknown> {
  return {
    schemaVersion: 5,
    globalEnabled: true,
    onboardingComplete: true,
    rules: [
      {
        id: 'r1',
        domain: 'youtube.com',
        mode: 'HARD_BLOCK',
        delaySeconds: 15,
        dailyLimitMinutes: null,
        enabled: true,
        createdAt: 1,
        features: { platform: 'youtube', gates: {}, hides: {}, youtube },
      },
    ],
    messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
    strictMode: { enabled: false, challengeLength: 24 },
    emergencyPass: { enabled: true },
    tempAllowMinutes: 10,
  };
}

function youtubeOf(s: NudgeSettings) {
  const yt = s.rules.find((r: SiteRule) => r.domain === 'youtube.com')?.features?.youtube;
  if (yt === undefined) throw new Error('expected youtube features');
  return yt;
}

describe('schema v6', () => {
  it('is version 6', () => {
    expect(SCHEMA_VERSION).toBeGreaterThanOrEqual(6);
  });

  it('upgrades a v5 install with the option OFF and no subscriptions, keeping its whitelist', () => {
    const migrated = migrateSettings(v5Blob());
    const yt = youtubeOf(migrated);
    expect(migrated.schemaVersion).toBe(SCHEMA_VERSION);
    expect(yt.allowSubscriptions).toBe(false);
    expect(yt.subscribedChannels).toEqual([]);
    expect(yt.channelMode).toBe('WHITELIST');
    expect(yt.channels.map((c) => c.handle)).toEqual(['kurzgesagt']);
  });

  it('migrating twice gives the same settings as migrating once', () => {
    const once = migrateSettings(v5Blob());
    expect(migrateSettings(once)).toEqual(once);
  });

  it.each([['yes'], [1], ['true'], [null], [{}]])('reads allowSubscriptions %j as OFF', (value) => {
    expect(youtubeOf(migrateSettings(v5Blob({ ...V5_YOUTUBE, allowSubscriptions: value }))).allowSubscriptions).toBe(
      false,
    );
  });

  it('keeps allowSubscriptions when it is literally true', () => {
    const migrated = migrateSettings(v5Blob({ ...V5_YOUTUBE, allowSubscriptions: true }));
    expect(youtubeOf(migrated).allowSubscriptions).toBe(true);
    expect(migrateSettings(migrated)).toEqual(migrated);
  });

  // Coerced exactly like a typed list: an entry naming no channel is dropped here, an UNSAFE
  // identifier is skipped where it would become a regex (youtubeAccess tests), and the list
  // itself is always replaced from storage.local by the worker (storage/router tests).
  it('drops subscribed entries that name no channel', () => {
    const migrated = migrateSettings(
      v5Blob({ ...V5_YOUTUBE, subscribedChannels: [SUBSCRIBED, 'garbage', { displayName: 'x' }] }),
    );
    expect(youtubeOf(migrated).subscribedChannels).toEqual([SUBSCRIBED]);
  });
});

describe('withSubscribedChannels', () => {
  const base = settings({
    rules: [
      siteRule({ id: 'yt', domain: 'youtube.com', features: featuresWith('youtube') }),
      siteRule({ id: 'x', domain: 'x.com', features: featuresWith('x') }),
      siteRule({ id: 'plain', domain: 'example.com', features: null }),
    ],
  });

  it('puts the list on every YouTube rule and touches no other rule', () => {
    const hydrated = withSubscribedChannels(base, [SUBSCRIBED]);
    expect(hydrated.rules[0]?.features?.youtube?.subscribedChannels).toEqual([SUBSCRIBED]);
    expect(hydrated.rules[1]).toEqual(base.rules[1]);
    expect(hydrated.rules[2]).toEqual(base.rules[2]);
  });

  it('an empty list strips them again (what storage does before writing)', () => {
    const stripped = withSubscribedChannels(withSubscribedChannels(base, [SUBSCRIBED]), []);
    expect(stripped).toEqual(base);
  });

  it('does not mutate its input', () => {
    const snapshot = JSON.parse(JSON.stringify(base)) as NudgeSettings;
    withSubscribedChannels(base, [SUBSCRIBED]);
    expect(base).toEqual(snapshot);
  });
});
