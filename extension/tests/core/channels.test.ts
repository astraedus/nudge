import { describe, expect, it } from 'vitest';

import {
  addChannel,
  decideChannel,
  findChannel,
  isChannelListed,
  parseChannelInput,
  removeChannel,
  sameChannel,
  shouldShowInColor,
  type ChannelProbe,
} from '../../src/core/channels';
import type { ChannelEntry, ChannelListMode } from '../../src/core/settingsSchema';

/**
 * Port intent: mirrors the house style set by domainMatcher.test.ts/blockEngine.test.ts —
 * every case is phrased as a user-visible outcome ("blocks a channel not on the
 * whitelist"), never as which internal branch fired. That rule exists because of a real
 * incident on this codebase: a critical BlockEngine bug survived 400 tests because two of
 * them asserted the (wrong) internal branch instead of the user-visible behaviour.
 */

const VALID_CHANNEL_ID = 'UCHnyfMqiRRG1u-2MsSQLbXA';

function entry(overrides: Partial<ChannelEntry>): ChannelEntry {
  return {
    channelId: null,
    handle: null,
    displayName: 'placeholder',
    addedAt: 0,
    ...overrides,
  };
}

// -----------------------------------------------------------------------------------
// parseChannelInput
// -----------------------------------------------------------------------------------

describe('parseChannelInput — recognized forms', () => {
  it('accepts a bare handle written with the leading @', () => {
    const result = parseChannelInput('@veritasium', 1000);
    expect(result).toEqual({
      channelId: null,
      handle: 'veritasium',
      displayName: '@veritasium',
      addedAt: 1000,
    });
  });

  it('accepts a bare handle written without the leading @', () => {
    const result = parseChannelInput('veritasium', 1000);
    expect(result).toEqual({
      channelId: null,
      handle: 'veritasium',
      displayName: '@veritasium',
      addedAt: 1000,
    });
  });

  it('accepts a full handle URL', () => {
    const result = parseChannelInput('https://www.youtube.com/@veritasium', 1000);
    expect(result?.handle).toBe('veritasium');
    expect(result?.channelId).toBeNull();
  });

  it('accepts a schemeless handle URL with a trailing path segment', () => {
    const result = parseChannelInput('youtube.com/@veritasium/videos', 1000);
    expect(result?.handle).toBe('veritasium');
  });

  it('accepts a canonical channel-id URL and keeps the id exactly as given', () => {
    const result = parseChannelInput(
      `https://www.youtube.com/channel/${VALID_CHANNEL_ID}`,
      1000,
    );
    expect(result).toEqual({
      channelId: VALID_CHANNEL_ID,
      handle: null,
      displayName: VALID_CHANNEL_ID,
      addedAt: 1000,
    });
  });

  it('accepts a bare channel id', () => {
    const result = parseChannelInput(VALID_CHANNEL_ID, 1000);
    expect(result?.channelId).toBe(VALID_CHANNEL_ID);
    expect(result?.handle).toBeNull();
  });

  it('accepts a legacy "/c/" custom URL, using the name as a handle-ish identifier', () => {
    const result = parseChannelInput('youtube.com/c/Veritasium', 1000);
    expect(result).toEqual({
      channelId: null,
      handle: 'veritasium',
      displayName: 'Veritasium',
      addedAt: 1000,
    });
  });

  it('accepts a legacy "/user/" URL, using the name as a handle-ish identifier', () => {
    const result = parseChannelInput('youtube.com/user/1veritasium', 1000);
    expect(result).toEqual({
      channelId: null,
      handle: '1veritasium',
      displayName: '1veritasium',
      addedAt: 1000,
    });
  });

  it('defaults addedAt to the current time when no clock is injected', () => {
    const before = Date.now();
    const result = parseChannelInput('@veritasium');
    const after = Date.now();
    expect(result?.addedAt).toBeGreaterThanOrEqual(before);
    expect(result?.addedAt).toBeLessThanOrEqual(after);
  });
});

describe('parseChannelInput — case handling', () => {
  it('lowercases and strips "@" from a mixed-case handle URL', () => {
    const result = parseChannelInput('@VeriTasium', 1000);
    expect(result?.handle).toBe('veritasium');
    expect(result?.displayName).toBe('@veritasium');
  });

  it('lowercases an uppercase bare handle', () => {
    const result = parseChannelInput('VERITASIUM', 1000);
    expect(result?.handle).toBe('veritasium');
  });

  it('preserves the exact case of a channel id (ids are case-sensitive)', () => {
    const mixedCaseId = 'UCaBcDeFgHiJkLmNoPqRsTuV';
    const result = parseChannelInput(mixedCaseId, 1000);
    expect(result?.channelId).toBe(mixedCaseId);
  });
});

describe('parseChannelInput — rejects non-channel input', () => {
  it('rejects a blank string', () => {
    expect(parseChannelInput('', 1000)).toBeNull();
  });

  it('rejects a whitespace-only string', () => {
    expect(parseChannelInput('   ', 1000)).toBeNull();
  });

  it('rejects a watch URL (it names a video, not a channel)', () => {
    expect(
      parseChannelInput('https://www.youtube.com/watch?v=dQw4w9WgXcQ', 1000),
    ).toBeNull();
  });

  it('rejects a YouTube URL with no channel anywhere in the path', () => {
    expect(parseChannelInput('https://www.youtube.com/feed/trending', 1000)).toBeNull();
  });

  it('rejects the bare YouTube homepage (no path at all)', () => {
    expect(parseChannelInput('https://www.youtube.com/', 1000)).toBeNull();
  });

  it('rejects a canonical channel URL missing the id segment', () => {
    expect(parseChannelInput('https://www.youtube.com/channel/', 1000)).toBeNull();
  });

  it('rejects free-text garbage that is not a URL, handle, or id', () => {
    expect(parseChannelInput('this is not a channel!!', 1000)).toBeNull();
  });

  it('rejects a channel id that is one character short of valid (a near-miss)', () => {
    const nearMiss = VALID_CHANNEL_ID.slice(0, -1);
    expect(parseChannelInput(nearMiss, 1000)).toBeNull();
  });

  it('rejects a channel id that has an extra trailing character (a near-miss)', () => {
    const nearMiss = `${VALID_CHANNEL_ID}A`;
    expect(parseChannelInput(nearMiss, 1000)).toBeNull();
  });
});

// -----------------------------------------------------------------------------------
// sameChannel
// -----------------------------------------------------------------------------------

describe('sameChannel', () => {
  it('recognizes two entries for the same channel when the ids match exactly', () => {
    const a = entry({ channelId: VALID_CHANNEL_ID });
    const b = entry({ channelId: VALID_CHANNEL_ID, displayName: 'Different Name' });
    expect(sameChannel(a, b)).toBe(true);
  });

  it('treats channel ids as case-sensitive (a differently-cased id is a different channel)', () => {
    const a = entry({ channelId: VALID_CHANNEL_ID });
    const b = entry({ channelId: VALID_CHANNEL_ID.toLowerCase() });
    expect(sameChannel(a, b)).toBe(false);
  });

  it('recognizes two entries for the same channel when handles match regardless of case', () => {
    const a = entry({ handle: 'veritasium' });
    const b = entry({ handle: 'VeriTasium' });
    expect(sameChannel(a, b)).toBe(true);
  });

  it('treats two entries with unrelated ids and handles as different channels', () => {
    const a = entry({ channelId: VALID_CHANNEL_ID, handle: 'veritasium' });
    const b = entry({ channelId: 'UCzzzzzzzzzzzzzzzzzzzzzz', handle: 'someoneelse' });
    expect(sameChannel(a, b)).toBe(false);
  });

  it('does not match two entries that only carry non-overlapping identifier types', () => {
    const a = entry({ channelId: VALID_CHANNEL_ID, handle: null });
    const b = entry({ channelId: null, handle: 'veritasium' });
    expect(sameChannel(a, b)).toBe(false);
  });
});

// -----------------------------------------------------------------------------------
// findChannel / isChannelListed
// -----------------------------------------------------------------------------------

describe('findChannel', () => {
  const list: ChannelEntry[] = [
    entry({ channelId: VALID_CHANNEL_ID, handle: 'veritasium', displayName: 'Veritasium' }),
    entry({ channelId: null, handle: 'mkbhd', displayName: '@mkbhd' }),
  ];

  it('finds a channel by id-only probe', () => {
    const probe: ChannelProbe = { channelId: VALID_CHANNEL_ID };
    expect(findChannel(list, probe)?.displayName).toBe('Veritasium');
  });

  it('finds a channel by handle-only probe', () => {
    const probe: ChannelProbe = { handle: 'mkbhd' };
    expect(findChannel(list, probe)?.displayName).toBe('@mkbhd');
  });

  it('finds a channel by handle probe even when the probe handle has a leading @ and different case', () => {
    const probe: ChannelProbe = { handle: '@MKBHD' };
    expect(findChannel(list, probe)?.handle).toBe('mkbhd');
  });

  it('cross-matches when the stored entry has both identifiers but the probe only carries one', () => {
    const probe: ChannelProbe = { handle: 'veritasium' };
    expect(findChannel(list, probe)?.channelId).toBe(VALID_CHANNEL_ID);
  });

  it('returns null when neither identifier on the probe matches anything on the list', () => {
    const probe: ChannelProbe = { channelId: 'UCnotintheL1stnotintheL1' };
    expect(findChannel(list, probe)).toBeNull();
  });

  it('returns null when the probe carries neither identifier', () => {
    expect(findChannel(list, {})).toBeNull();
  });

  it('returns null against an empty list', () => {
    expect(findChannel([], { handle: 'veritasium' })).toBeNull();
  });
});

describe('isChannelListed', () => {
  const list: ChannelEntry[] = [entry({ handle: 'veritasium' })];

  it('reports a listed channel as listed', () => {
    expect(isChannelListed(list, { handle: 'veritasium' })).toBe(true);
  });

  it('reports an unlisted channel as not listed', () => {
    expect(isChannelListed(list, { handle: 'someoneelse' })).toBe(false);
  });
});

// -----------------------------------------------------------------------------------
// decideChannel — full mode x listed/not-listed/unknown matrix
// -----------------------------------------------------------------------------------

describe('decideChannel', () => {
  const listedProbe: ChannelProbe = { handle: 'veritasium' };
  const notListedProbe: ChannelProbe = { handle: 'someoneelse' };
  const unknownProbe: ChannelProbe = {};
  const channels: ChannelEntry[] = [entry({ handle: 'veritasium' })];

  function decide(mode: ChannelListMode, probe: ChannelProbe) {
    return decideChannel({ mode, channels, probe, blockMode: 'DELAY' });
  }

  describe('mode OFF — the feature is disabled, so it never blocks anything', () => {
    it('lets a listed channel through', () => {
      expect(decide('OFF', listedProbe)).toEqual({ action: 'ALLOW', reason: 'mode-off' });
    });

    it('lets an unlisted channel through', () => {
      expect(decide('OFF', notListedProbe)).toEqual({ action: 'ALLOW', reason: 'mode-off' });
    });

    it('lets an unidentifiable channel through', () => {
      expect(decide('OFF', unknownProbe)).toEqual({ action: 'ALLOW', reason: 'mode-off' });
    });
  });

  describe('mode BLACKLIST — only listed channels are blocked', () => {
    it('blocks a channel that is on the blacklist', () => {
      expect(decide('BLACKLIST', listedProbe)).toEqual({ action: 'BLOCK', mode: 'DELAY' });
    });

    it('allows a channel that is not on the blacklist', () => {
      expect(decide('BLACKLIST', notListedProbe)).toEqual({
        action: 'ALLOW',
        reason: 'not-listed',
      });
    });

    it('allows a channel it could not identify (fails open, never blocks all of YouTube on a detection miss)', () => {
      expect(decide('BLACKLIST', unknownProbe)).toEqual({
        action: 'ALLOW',
        reason: 'unknown-channel',
      });
    });
  });

  describe('mode WHITELIST — only listed channels are allowed', () => {
    it('allows a channel that is on the whitelist', () => {
      expect(decide('WHITELIST', listedProbe)).toEqual({ action: 'ALLOW', reason: 'listed' });
    });

    it('blocks a channel that is not on the whitelist', () => {
      expect(decide('WHITELIST', notListedProbe)).toEqual({ action: 'BLOCK', mode: 'DELAY' });
    });

    it('allows a channel it could not identify rather than breaking all of YouTube on a detection miss', () => {
      expect(decide('WHITELIST', unknownProbe)).toEqual({
        action: 'ALLOW',
        reason: 'unknown-channel',
      });
    });
  });

  it('reports the caller-configured block mode, not a hardcoded one', () => {
    expect(decideChannel({ mode: 'BLACKLIST', channels, probe: listedProbe, blockMode: 'HARD_BLOCK' })).toEqual({
      action: 'BLOCK',
      mode: 'HARD_BLOCK',
    });
  });
});

// -----------------------------------------------------------------------------------
// shouldShowInColor — same matrix, the unknown channel must always stay gray
// -----------------------------------------------------------------------------------

describe('shouldShowInColor', () => {
  const listedProbe: ChannelProbe = { handle: 'veritasium' };
  const notListedProbe: ChannelProbe = { handle: 'someoneelse' };
  const unknownProbe: ChannelProbe = {};
  const channels: ChannelEntry[] = [entry({ handle: 'veritasium' })];

  function show(mode: ChannelListMode, probe: ChannelProbe) {
    return shouldShowInColor({ mode, channels, probe });
  }

  it('keeps everything gray while the feature is off', () => {
    expect(show('OFF', listedProbe)).toBe(false);
    expect(show('OFF', notListedProbe)).toBe(false);
    expect(show('OFF', unknownProbe)).toBe(false);
  });

  it('keeps a blacklisted channel gray', () => {
    expect(show('BLACKLIST', listedProbe)).toBe(false);
  });

  it('shows a non-blacklisted channel in color', () => {
    expect(show('BLACKLIST', notListedProbe)).toBe(true);
  });

  it('shows a whitelisted channel in color', () => {
    expect(show('WHITELIST', listedProbe)).toBe(true);
  });

  it('keeps a non-whitelisted channel gray', () => {
    expect(show('WHITELIST', notListedProbe)).toBe(false);
  });

  it('keeps a channel it could not identify gray, in every mode — gray is always the safe default', () => {
    expect(show('BLACKLIST', unknownProbe)).toBe(false);
    expect(show('WHITELIST', unknownProbe)).toBe(false);
    expect(show('OFF', unknownProbe)).toBe(false);
  });
});

// -----------------------------------------------------------------------------------
// addChannel / removeChannel — purity + duplicate merging
// -----------------------------------------------------------------------------------

describe('addChannel', () => {
  it('adds a channel to an empty list', () => {
    const result = addChannel([], entry({ handle: 'veritasium' }));
    expect(result).toHaveLength(1);
    expect(result[0]?.handle).toBe('veritasium');
  });

  it('adds a new, unrelated channel alongside existing ones', () => {
    const existing = [entry({ handle: 'veritasium' })];
    const result = addChannel(existing, entry({ handle: 'mkbhd' }));
    expect(result).toHaveLength(2);
  });

  it('does not create a duplicate row for a channel that is already on the list', () => {
    const existing = [entry({ handle: 'veritasium', channelId: null })];
    const result = addChannel(existing, entry({ handle: 'veritasium', channelId: VALID_CHANNEL_ID }));
    expect(result).toHaveLength(1);
  });

  it('fills in a missing identifier when merging a duplicate add', () => {
    const existing = [entry({ handle: 'veritasium', channelId: null, displayName: '@veritasium' })];
    const result = addChannel(
      existing,
      entry({ handle: 'veritasium', channelId: VALID_CHANNEL_ID, displayName: '@veritasium' }),
    );
    expect(result[0]?.channelId).toBe(VALID_CHANNEL_ID);
    expect(result[0]?.handle).toBe('veritasium');
  });

  it('prefers a real display name over a bare "@handle" placeholder when merging', () => {
    const existing = [entry({ handle: 'veritasium', displayName: '@veritasium' })];
    const result = addChannel(existing, entry({ handle: 'veritasium', displayName: 'Veritasium' }));
    expect(result[0]?.displayName).toBe('Veritasium');
  });

  it('never mutates the input list (by reference and by content)', () => {
    const existing = [entry({ handle: 'veritasium' })];
    const snapshot = JSON.parse(JSON.stringify(existing));
    const result = addChannel(existing, entry({ handle: 'mkbhd' }));
    expect(existing).toEqual(snapshot);
    expect(result).not.toBe(existing);
  });
});

describe('removeChannel', () => {
  it('removes a channel matched by id', () => {
    const list = [entry({ channelId: VALID_CHANNEL_ID, handle: 'veritasium' })];
    const result = removeChannel(list, entry({ channelId: VALID_CHANNEL_ID }));
    expect(result).toHaveLength(0);
  });

  it('removes a channel matched by handle', () => {
    const list = [entry({ handle: 'veritasium' })];
    const result = removeChannel(list, entry({ handle: 'VeriTasium' }));
    expect(result).toHaveLength(0);
  });

  it('leaves unrelated channels untouched', () => {
    const list = [entry({ handle: 'veritasium' }), entry({ handle: 'mkbhd' })];
    const result = removeChannel(list, entry({ handle: 'veritasium' }));
    expect(result).toHaveLength(1);
    expect(result[0]?.handle).toBe('mkbhd');
  });

  it('is a no-op (content-wise) when the channel is not on the list', () => {
    const list = [entry({ handle: 'veritasium' })];
    const result = removeChannel(list, entry({ handle: 'someoneelse' }));
    expect(result).toEqual(list);
  });

  it('never mutates the input list (by reference and by content)', () => {
    const list = [entry({ handle: 'veritasium' }), entry({ handle: 'mkbhd' })];
    const snapshot = JSON.parse(JSON.stringify(list));
    const result = removeChannel(list, entry({ handle: 'veritasium' }));
    expect(list).toEqual(snapshot);
    expect(result).not.toBe(list);
  });
});
