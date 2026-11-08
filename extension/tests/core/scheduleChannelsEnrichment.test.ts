import { describe, expect, it } from 'vitest';

import { keepLearnedChannelIdentifiers } from '../../src/core/channels';
import type {
  ChannelEntry,
  NudgeSettings,
  ScheduleChannelOverride,
} from '../../src/core/settingsSchema';
import { featuresWith, scheduleOverride, settings, siteRule } from '../helpers/rules';

const STUDY_ID = 'UCstudy0000000000000001';

const handleOnly: ChannelEntry = {
  channelId: null,
  handle: 'study',
  displayName: '@study',
  addedAt: 1,
};
const learned: ChannelEntry = {
  channelId: STUDY_ID,
  handle: 'study',
  displayName: 'Study',
  addedAt: 1,
};

const windowList = (channels: ChannelEntry[]): ScheduleChannelOverride => ({
  channelMode: 'WHITELIST',
  channels,
  allowSubscriptions: false,
});

/** A YouTube rule with a default blacklist and a window whitelist. */
function config(
  defaults: ChannelEntry[],
  window: ChannelEntry[] | null,
  ruleId = 'yt',
): NudgeSettings {
  return settings({
    rules: [
      siteRule({
        id: ruleId,
        mode: 'ALLOW',
        schedule: scheduleOverride({ channels: window === null ? null : windowList(window) }),
        features: featuresWith('youtube', {
          youtube: { channelMode: 'BLACKLIST', channels: defaults },
        }),
      }),
    ],
  });
}

const windowChannels = (s: NudgeSettings) => s.rules[0]?.schedule?.channels?.channels;
const defaultChannels = (s: NudgeSettings) => s.rules[0]?.features?.youtube?.channels;

describe('keepLearnedChannelIdentifiers over a window list', () => {
  it('fills an id into the incoming window list from the stored window list', () => {
    const merged = keepLearnedChannelIdentifiers(config([], [learned]), config([], [handleOnly]));
    expect(windowChannels(merged)?.[0]?.channelId).toBe(STUDY_ID);
  });

  it('fills an id into the incoming window list from the stored default list', () => {
    const merged = keepLearnedChannelIdentifiers(config([learned], null), config([], [handleOnly]));
    // The stored side had no window list at all: knowledge crosses lists.
    expect(windowChannels(merged)?.[0]?.channelId).toBe(STUDY_ID);
  });

  it('fills an id into the incoming default list from the stored window list', () => {
    const merged = keepLearnedChannelIdentifiers(config([], [learned]), config([handleOnly], []));
    expect(defaultChannels(merged)?.[0]?.channelId).toBe(STUDY_ID);
  });

  it('returns next itself when nothing was learned', () => {
    const next = config([], [handleOnly]);
    expect(keepLearnedChannelIdentifiers(config([], [handleOnly]), next)).toBe(next);
  });

  it('does not add a channel the save removed', () => {
    const merged = keepLearnedChannelIdentifiers(config([], [learned]), config([], []));
    expect(windowChannels(merged)).toEqual([]);
  });

  it('gives a deleted and re-added rule (new id) nothing', () => {
    const next = config([], [handleOnly], 'yt-new');
    expect(keepLearnedChannelIdentifiers(config([learned], [learned], 'yt-old'), next)).toBe(next);
  });
});
