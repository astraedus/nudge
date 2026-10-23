import { describe, expect, it } from 'vitest';

import {
  CREATOR_PATHS,
  LEGACY_CHANNEL_PATHS,
  PLAYLIST_PATH,
  WATCH_PATHS,
  effectiveChannelLists,
  isCreatorExemptUrl,
  youtubeAllowFilters,
  youtubePageAccess,
  youtubeWhitelistPaths,
  type YoutubePageAccess,
} from '../../src/core/youtubeAccess';
import type {
  ChannelEntry,
  YoutubeFeatureSettings,
} from '../../src/core/settingsSchema';
import { featuresWith, siteRule } from '../helpers/rules';

function channel(handle: string | null, channelId: string | null = null): ChannelEntry {
  return { channelId, handle, displayName: handle ?? channelId ?? '', addedAt: 0 };
}

/** A real YouTube settings block from the shared fixture builder. */
function yt(overrides: Partial<YoutubeFeatureSettings> = {}): YoutubeFeatureSettings {
  const youtube = featuresWith('youtube', { youtube: overrides }).youtube;
  if (youtube === undefined) throw new Error('youtube features expected');
  return youtube;
}

const VERITASIUM = channel('veritasium', 'UCHnyfMqiRRG1u-2MsSQLbXA');
const MINE = channel('mine', 'UCmine000000000000000001');
const ON = { siteApplies: true, limitReached: false };

describe('constants are written as paths (leading slash)', () => {
  it('every path pattern starts with /', () => {
    for (const path of [...WATCH_PATHS, ...LEGACY_CHANNEL_PATHS, ...CREATOR_PATHS, PLAYLIST_PATH]) {
      expect(path.startsWith('/'), path).toBe(true);
    }
  });
});

describe('youtubeWhitelistPaths: which pages a whitelist lets through', () => {
  it('always opens watch pages and playlists, even with no channels', () => {
    const paths = youtubeWhitelistPaths([]);
    for (const path of [...WATCH_PATHS, PLAYLIST_PATH]) expect(paths).toContain(path);
  });

  it("adds each channel's own handle and id pages", () => {
    const paths = youtubeWhitelistPaths([VERITASIUM]);
    expect(paths).toContain('/@veritasium(?:/.*)?');
    expect(paths).toContain('/channel/UCHnyfMqiRRG1u-2MsSQLbXA(?:/.*)?');
  });

  it.each([['.*'], ['a/b'], ['x|y'], ['']])(
    'skips the unsafe identifier %j so a hand-edited list cannot open all of YouTube',
    (bad) => {
      const base = youtubeWhitelistPaths([]);
      expect(youtubeWhitelistPaths([channel(bad, bad)])).toEqual(base);
    },
  );

  it('is sorted and has no duplicates', () => {
    const paths = youtubeWhitelistPaths([VERITASIUM, VERITASIUM, MINE]);
    expect(paths).toEqual([...paths].sort());
    expect(new Set(paths).size).toBe(paths.length);
  });
});

describe('effectiveChannelLists: which channel rule is really enforced', () => {
  it('is OFF with no channels when there is no YouTube config', () => {
    expect(effectiveChannelLists(null, true)).toEqual({ channelMode: 'OFF', channels: [] });
  });

  it('adds my channel to a whitelist while creator mode is on', () => {
    const youtube = yt({ channelMode: 'WHITELIST', channels: [VERITASIUM], creatorMode: true, myChannel: MINE });
    expect(effectiveChannelLists(youtube, true)).toEqual({
      channelMode: 'WHITELIST',
      channels: [VERITASIUM, MINE],
    });
  });

  it('ignores my channel when creator mode is off', () => {
    const youtube = yt({ channelMode: 'WHITELIST', channels: [VERITASIUM], creatorMode: false, myChannel: MINE });
    expect(effectiveChannelLists(youtube, true)).toEqual({
      channelMode: 'WHITELIST',
      channels: [VERITASIUM],
    });
  });

  it.each(['BLACKLIST', 'OFF'] as const)(
    'turns a blocked site with a %s list into "only my channel" under creator mode',
    (channelMode) => {
      const youtube = yt({ channelMode, channels: [VERITASIUM], creatorMode: true, myChannel: MINE });
      expect(effectiveChannelLists(youtube, true)).toEqual({
        channelMode: 'WHITELIST',
        channels: [MINE],
      });
    },
  );

  it.each(['BLACKLIST', 'OFF'] as const)(
    'leaves a %s list alone while the site is open, so creator mode never starts blocking YouTube',
    (channelMode) => {
      const youtube = yt({ channelMode, channels: [VERITASIUM], creatorMode: true, myChannel: MINE });
      expect(effectiveChannelLists(youtube, false)).toEqual({ channelMode, channels: [VERITASIUM] });
    },
  );

  it('changes nothing when creator mode is on but no channel is set', () => {
    const youtube = yt({ channelMode: 'BLACKLIST', channels: [VERITASIUM], creatorMode: true, myChannel: null });
    expect(effectiveChannelLists(youtube, true)).toEqual({
      channelMode: 'BLACKLIST',
      channels: [VERITASIUM],
    });
  });
});

describe('youtubeAllowFilters: what the network layer lets through', () => {
  const whitelist = yt({ channelMode: 'WHITELIST', channels: [VERITASIUM] });

  it('opens nothing when the rule is not in force or there is no config', () => {
    const none = { channel: [], verify: [], creator: [] };
    expect(youtubeAllowFilters('youtube.com', whitelist, { siteApplies: false, limitReached: false })).toEqual(none);
    expect(youtubeAllowFilters('youtube.com', null, ON)).toEqual(none);
  });

  it('opens channel and legacy-verify pages for a non-empty whitelist', () => {
    const filters = youtubeAllowFilters('youtube.com', whitelist, ON);
    expect(filters.channel.length).toBeGreaterThan(0);
    expect(filters.verify.length).toBe(LEGACY_CHANNEL_PATHS.length);
    expect(filters.creator).toEqual([]);
  });

  it('opens no channel pages for an empty whitelist', () => {
    const filters = youtubeAllowFilters('youtube.com', yt({ channelMode: 'WHITELIST', channels: [] }), ON);
    expect(filters.channel).toEqual([]);
    expect(filters.verify).toEqual([]);
  });

  it('closes channel pages once the daily limit is spent', () => {
    const filters = youtubeAllowFilters('youtube.com', whitelist, { siteApplies: true, limitReached: true });
    expect(filters.channel).toEqual([]);
    expect(filters.verify).toEqual([]);
  });

  it('opens Studio and creator routes only with creator mode, even after the limit is spent', () => {
    const creator = yt({ ...whitelist, creatorMode: true });
    expect(youtubeAllowFilters('youtube.com', creator, ON).creator.length).toBe(CREATOR_PATHS.length + 1);
    expect(
      youtubeAllowFilters('youtube.com', creator, { siteApplies: true, limitReached: true }).creator.length,
    ).toBe(CREATOR_PATHS.length + 1);
    expect(youtubeAllowFilters('youtube.com', whitelist, ON).creator).toEqual([]);
  });

  it('opens just my channel when the list is OFF, creator mode is on and the site is blocked', () => {
    const youtube = yt({ channelMode: 'OFF', channels: [], creatorMode: true, myChannel: MINE });
    const filters = youtubeAllowFilters('youtube.com', youtube, ON);
    expect(filters.channel.some((f) => f.includes('@mine'))).toBe(true);
    expect(filters.channel.some((f) => f.includes('veritasium'))).toBe(false);
  });
});

describe('youtubePageAccess: the verdict for a typed or clicked URL', () => {
  const youtube = yt({ channelMode: 'WHITELIST', channels: [VERITASIUM], creatorMode: true, myChannel: MINE });
  const base = { domain: 'youtube.com', youtube, siteApplies: true, limitReached: false, tempAllowed: false };

  const table: ReadonlyArray<readonly [string, YoutubePageAccess]> = [
    ['https://studio.youtube.com/', 'creator'],
    ['https://studio.youtube.com/channel/UCmine000000000000000001/videos', 'creator'],
    ['https://www.youtube.com/upload', 'creator'],
    ['https://m.youtube.com/signin?next=https%3A%2F%2Fstudio.youtube.com%2F', 'creator'],
    ['https://www.youtube.com/watch?v=x', 'carved'],
    ['https://www.youtube.com/live/abcDEF_-1', 'carved'],
    ['https://www.youtube.com/playlist?list=PLabc', 'carved'],
    ['https://www.youtube.com/@veritasium/videos', 'carved'],
    ['https://www.youtube.com/@Veritasium', 'carved'],
    ['https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA', 'carved'],
    ['https://www.youtube.com/@mine', 'carved'],
    ['https://www.youtube.com/c/kurzgesagt', 'verify'],
    ['https://www.youtube.com/user/Kurzgesagt/videos', 'verify'],
    ['https://www.youtube.com/', 'blocked'],
    ['https://www.youtube.com/feed/subscriptions', 'blocked'],
    ['https://www.youtube.com/results?search_query=a', 'blocked'],
    ['https://www.youtube.com/shorts/abc', 'blocked'],
    ['https://www.youtube.com/@other', 'blocked'],
    ['https://www.youtube.com/channel/UCother', 'blocked'],
    ['https://www.youtube.com/watchlater', 'blocked'],
  ];

  it.each(table)('%s is %s', (url, expected) => {
    expect(youtubePageAccess({ ...base, url })).toBe(expected);
  });

  it('leaves everything open when the rule is not in force', () => {
    expect(youtubePageAccess({ ...base, url: 'https://www.youtube.com/', siteApplies: false })).toBe('open');
  });

  it('leaves everything open during a completed pause', () => {
    expect(youtubePageAccess({ ...base, url: 'https://www.youtube.com/', tempAllowed: true })).toBe('open');
  });

  it('blocks Studio when creator mode is off', () => {
    const off = yt({ channelMode: 'WHITELIST', channels: [VERITASIUM], creatorMode: false });
    expect(youtubePageAccess({ ...base, youtube: off, url: 'https://studio.youtube.com/' })).toBe('blocked');
    expect(youtubePageAccess({ ...base, youtube: off, url: 'https://www.youtube.com/upload' })).toBe('blocked');
  });

  it('blocks channel pages after the daily limit but keeps the upload route open', () => {
    const spent = { ...base, limitReached: true };
    expect(youtubePageAccess({ ...spent, url: 'https://www.youtube.com/@veritasium' })).toBe('blocked');
    expect(youtubePageAccess({ ...spent, url: 'https://www.youtube.com/upload' })).toBe('creator');
  });
});

describe('isCreatorExemptUrl: time in Studio is not counted against the YouTube limit', () => {
  const creatorRule = siteRule({
    features: featuresWith('youtube', { youtube: { creatorMode: true, myChannel: MINE } }),
  });

  it.each(['https://studio.youtube.com/', 'https://www.youtube.com/upload'])('exempts %s', (url) => {
    expect(isCreatorExemptUrl([creatorRule], url)).toBe(true);
  });

  it.each([
    'https://www.youtube.com/watch?v=x',
    'https://www.youtube.com/@mine',
    'https://studio.example.com/',
  ])('does not exempt %s', (url) => {
    expect(isCreatorExemptUrl([creatorRule], url)).toBe(false);
  });

  it('does not exempt Studio with creator mode off', () => {
    const off = siteRule({ features: featuresWith('youtube', { youtube: { creatorMode: false } }) });
    expect(isCreatorExemptUrl([off], 'https://studio.youtube.com/')).toBe(false);
  });

  it('does not exempt Studio when the youtube rule is disabled', () => {
    const disabled = { ...creatorRule, enabled: false };
    expect(isCreatorExemptUrl([disabled], 'https://studio.youtube.com/')).toBe(false);
  });

  it('does not exempt a studio.* host under a non-youtube rule', () => {
    const other = siteRule({ domain: 'example.com', features: null });
    expect(isCreatorExemptUrl([other], 'https://studio.example.com/')).toBe(false);
  });
});

describe('RE2 safety: DNR and the page must compile the same strings', () => {
  // RE2 has no lookaround and no backreferences; DNR silently drops a rule that uses one.
  const forbidden = /\(\?=|\(\?!|\(\?<|\\[1-9]/;

  const youtube = yt({ channelMode: 'WHITELIST', channels: [VERITASIUM, MINE], creatorMode: true, myChannel: MINE });
  const filters = youtubeAllowFilters('youtube.com', youtube, ON);
  const all = [...filters.channel, ...filters.verify, ...filters.creator];

  it('produces filters in all three arrays', () => {
    expect(filters.channel.length).toBeGreaterThan(0);
    expect(filters.verify.length).toBeGreaterThan(0);
    expect(filters.creator.length).toBeGreaterThan(0);
  });

  it('uses no lookaround or backreference and compiles as a RegExp', () => {
    for (const filter of all) {
      expect(forbidden.test(filter), filter).toBe(false);
      expect(() => new RegExp(filter, 'i'), filter).not.toThrow();
    }
  });

  it('the checker is not vacuous: it rejects a lookahead and a backreference', () => {
    expect(forbidden.test('^https://x/(?=a)$')).toBe(true);
    expect(forbidden.test('^https://x/(?<!a)$')).toBe(true);
    expect(forbidden.test('^(a)\\1$')).toBe(true);
  });
});
