import { describe, expect, it } from 'vitest';

import {
  addChannel,
  decideChannel,
  decideWatchGate,
  enrichEntries,
  findChannel,
  isChannelListed,
  parseChannelInput,
  removeChannel,
  sameChannel,
  shouldShowInColor,
  type ChannelObservation,
  type ChannelProbe,
  type WatchGateInput,
  type WatchGateVerdict,
} from '../../src/core/channels';
import { isWeakening } from '../../src/core/strictMode';
import {
  DEFAULT_SETTINGS,
  type ChannelEntry,
  type ChannelListMode,
  type NudgeSettings,
} from '../../src/core/settingsSchema';
import { featuresWith, settings, siteRule } from '../helpers/rules';

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

// -----------------------------------------------------------------------------------
// decideWatchGate — decideChannel PLUS the site rule standing behind it.
//
// Full cross-product: channelMode (OFF / BLACKLIST / WHITELIST) x probe (listed /
// not-listed / unidentified) x siteApplies (true / false) x siteMode (ALLOW / HARD_BLOCK /
// DELAY / BREATHING, where the site rule actually has an opinion). Every BLOCK assertion
// checks the FULL returned object — mode, delaySeconds, source, reason — so a value
// leaking in from the wrong rule (channel rule vs. site rule) is caught, not just whether
// the video was blocked at all.
// -----------------------------------------------------------------------------------

describe('decideWatchGate', () => {
  const LISTED_HANDLE = 'veritasium';
  const listedProbe: ChannelProbe = { handle: LISTED_HANDLE };
  const notListedProbe: ChannelProbe = { handle: 'someoneelse' };
  const unknownProbe: ChannelProbe = {};
  const channels: ChannelEntry[] = [entry({ handle: LISTED_HANDLE })];

  // Deliberately different from every site-rule value used below, so a test that expects
  // the CHANNEL rule's mode/delay cannot pass by accident from the site rule's value (or
  // vice versa).
  const CHANNEL_BLOCK_MODE = 'DELAY';
  const CHANNEL_DELAY_SECONDS = 15;
  const SITE_DELAY_SECONDS = 45;

  function gate(overrides: Partial<WatchGateInput> = {}): WatchGateVerdict {
    return decideWatchGate({
      mode: 'WHITELIST',
      channels,
      probe: listedProbe,
      siteMode: 'ALLOW',
      siteApplies: false,
      siteDelaySeconds: SITE_DELAY_SECONDS,
      channelBlockMode: CHANNEL_BLOCK_MODE,
      channelDelaySeconds: CHANNEL_DELAY_SECONDS,
      ...overrides,
    });
  }

  describe('mode OFF — always allowed, whatever the site rule says', () => {
    it('allows a listed channel even while a site block is in force', () => {
      expect(
        gate({ mode: 'OFF', probe: listedProbe, siteApplies: true, siteMode: 'HARD_BLOCK' }),
      ).toEqual({ action: 'ALLOW', reason: 'mode-off' });
    });

    it('allows an unidentified channel even while a site block is in force', () => {
      expect(
        gate({ mode: 'OFF', probe: unknownProbe, siteApplies: true, siteMode: 'HARD_BLOCK' }),
      ).toEqual({ action: 'ALLOW', reason: 'mode-off' });
    });
  });

  describe('mode BLACKLIST — names what to keep OUT, so it never borrows the site default', () => {
    it('blocks a listed channel using the CHANNEL rule, not the site rule, even while the site is in force', () => {
      expect(
        gate({
          mode: 'BLACKLIST',
          probe: listedProbe,
          siteApplies: true,
          siteMode: 'BREATHING',
          siteDelaySeconds: SITE_DELAY_SECONDS,
        }),
      ).toEqual({
        action: 'BLOCK',
        mode: CHANNEL_BLOCK_MODE,
        delaySeconds: CHANNEL_DELAY_SECONDS,
        source: 'channel-rule',
        reason: 'listed',
      });
    });

    it('allows an unlisted channel even while the site is in force', () => {
      expect(
        gate({
          mode: 'BLACKLIST',
          probe: notListedProbe,
          siteApplies: true,
          siteMode: 'HARD_BLOCK',
        }),
      ).toEqual({ action: 'ALLOW', reason: 'not-listed' });
    });

    it('FAILS OPEN on an unidentified channel — a blacklist can never prove a channel it cannot see is on it', () => {
      expect(
        gate({
          mode: 'BLACKLIST',
          probe: unknownProbe,
          siteApplies: true,
          siteMode: 'HARD_BLOCK',
        }),
      ).toEqual({ action: 'ALLOW', reason: 'unknown-channel' });
    });

    it('behaves identically to the site rule not being in force at all', () => {
      expect(gate({ mode: 'BLACKLIST', probe: listedProbe, siteApplies: false })).toEqual({
        action: 'BLOCK',
        mode: CHANNEL_BLOCK_MODE,
        delaySeconds: CHANNEL_DELAY_SECONDS,
        source: 'channel-rule',
        reason: 'listed',
      });
    });
  });

  describe('mode WHITELIST, site rule NOT in force — identical to the standalone channel rule', () => {
    it('allows a listed channel', () => {
      expect(gate({ mode: 'WHITELIST', probe: listedProbe, siteApplies: false })).toEqual({
        action: 'ALLOW',
        reason: 'listed',
      });
    });

    it('blocks an unlisted channel using the CHANNEL rule mode and delay', () => {
      expect(gate({ mode: 'WHITELIST', probe: notListedProbe, siteApplies: false })).toEqual({
        action: 'BLOCK',
        mode: CHANNEL_BLOCK_MODE,
        delaySeconds: CHANNEL_DELAY_SECONDS,
        source: 'channel-rule',
        reason: 'not-listed',
      });
    });

    it('FAILS OPEN on an unidentified channel — the long-standing fail-open doctrine, unchanged here', () => {
      expect(gate({ mode: 'WHITELIST', probe: unknownProbe, siteApplies: false })).toEqual({
        action: 'ALLOW',
        reason: 'unknown-channel',
      });
    });
  });

  describe('mode WHITELIST, site rule IN FORCE — the list becomes the site\'s exception list', () => {
    it('still allows a listed channel — this is the entire point of the feature', () => {
      expect(
        gate({ mode: 'WHITELIST', probe: listedProbe, siteApplies: true, siteMode: 'HARD_BLOCK' }),
      ).toEqual({ action: 'ALLOW', reason: 'listed' });
    });

    it("blocks an unlisted channel in the SITE's mode and delay, not the channel rule's (site mode BREATHING)", () => {
      expect(
        gate({
          mode: 'WHITELIST',
          probe: notListedProbe,
          siteApplies: true,
          siteMode: 'BREATHING',
          siteDelaySeconds: SITE_DELAY_SECONDS,
        }),
      ).toEqual({
        action: 'BLOCK',
        mode: 'BREATHING',
        delaySeconds: SITE_DELAY_SECONDS,
        source: 'site-default',
        reason: 'not-listed',
      });
    });

    it("blocks an unlisted channel in the SITE's mode and delay when the site mode is DELAY", () => {
      expect(
        gate({
          mode: 'WHITELIST',
          probe: notListedProbe,
          siteApplies: true,
          siteMode: 'DELAY',
          siteDelaySeconds: 10,
        }),
      ).toEqual({
        action: 'BLOCK',
        mode: 'DELAY',
        delaySeconds: 10,
        source: 'site-default',
        reason: 'not-listed',
      });
    });

    it('resolves an exhausted-budget site rule (siteMode ALLOW while siteApplies) to HARD_BLOCK for an unlisted channel', () => {
      // The only way `siteApplies` is true while `siteMode` is 'ALLOW' is an exhausted
      // daily budget — it cannot be waited out, so `siteFallbackMode` resolves it to
      // HARD_BLOCK, same as everywhere else that fallback is used.
      expect(
        gate({
          mode: 'WHITELIST',
          probe: notListedProbe,
          siteApplies: true,
          siteMode: 'ALLOW',
          siteDelaySeconds: SITE_DELAY_SECONDS,
        }),
      ).toEqual({
        action: 'BLOCK',
        mode: 'HARD_BLOCK',
        delaySeconds: SITE_DELAY_SECONDS,
        source: 'site-default',
        reason: 'not-listed',
      });
    });

    it('FAILS CLOSED on an unidentified channel — the ONE place in this module an unknown channel is blocked', () => {
      // Everywhere else (decideChannel, and WHITELIST/BLACKLIST above with no site rule in
      // force) an unidentified channel is allowed through. This is the deliberate exception:
      // the site's OWN default is already "blocked", so failing open here would let a
      // rotted selector silently defeat the very rule the user asked for.
      expect(
        gate({
          mode: 'WHITELIST',
          probe: unknownProbe,
          siteApplies: true,
          siteMode: 'HARD_BLOCK',
          siteDelaySeconds: SITE_DELAY_SECONDS,
        }),
      ).toEqual({
        action: 'BLOCK',
        mode: 'HARD_BLOCK',
        delaySeconds: SITE_DELAY_SECONDS,
        source: 'site-default',
        reason: 'unknown-channel',
      });
    });

    it('resolves an exhausted-budget site rule to HARD_BLOCK for an unidentified channel too', () => {
      expect(
        gate({
          mode: 'WHITELIST',
          probe: unknownProbe,
          siteApplies: true,
          siteMode: 'ALLOW',
          siteDelaySeconds: SITE_DELAY_SECONDS,
        }),
      ).toEqual({
        action: 'BLOCK',
        mode: 'HARD_BLOCK',
        delaySeconds: SITE_DELAY_SECONDS,
        source: 'site-default',
        reason: 'unknown-channel',
      });
    });
  });
});

// -----------------------------------------------------------------------------------
// enrichEntries — learning the identifier a stored entry is missing
// -----------------------------------------------------------------------------------

/** The canonical worked example: added by handle, watched on a page that names both. */
const VERITASIUM_ID = 'UCHnyfMqiRRG1u-2MsSQLbXA';
const OTHER_ID = 'UCsXVk37bltHxD1rDPwtNM8Q';

describe('enrichEntries — fills the missing identifier', () => {
  it('teaches a handle-only entry the channel id it never had', () => {
    const list = [entry({ handle: 'veritasium', displayName: '@veritasium' })];

    const result = enrichEntries(list, {
      channelId: VERITASIUM_ID,
      handle: 'veritasium',
      displayName: 'Veritasium',
    });

    expect(result.changed).toBe(true);
    expect(result.reason).toBe('enriched');
    expect(result.entries).toEqual([
      {
        channelId: VERITASIUM_ID,
        handle: 'veritasium',
        displayName: 'Veritasium',
        addedAt: 0,
      },
    ]);
  });

  it('teaches an id-only entry the handle it never had', () => {
    const list = [entry({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID })];

    const result = enrichEntries(list, { channelId: VERITASIUM_ID, handle: '@Veritasium' });

    expect(result.changed).toBe(true);
    // Stored lowercased and without the '@', the storage contract every other path uses.
    expect(result.entries[0]).toMatchObject({
      channelId: VERITASIUM_ID,
      handle: 'veritasium',
    });
  });

  it('matches a stored handle case-insensitively', () => {
    const list = [entry({ handle: 'veritasium', displayName: '@veritasium' })];

    const result = enrichEntries(list, { handle: 'VERITASIUM', channelId: VERITASIUM_ID });

    expect(result.changed).toBe(true);
    expect(result.entries[0]?.channelId).toBe(VERITASIUM_ID);
  });

  it('leaves the other entries in the list alone, in their original order', () => {
    const untouched = entry({ handle: 'kurzgesagt', displayName: 'Kurzgesagt' });
    const list = [
      untouched,
      entry({ handle: 'veritasium', displayName: '@veritasium' }),
      entry({ channelId: OTHER_ID, displayName: OTHER_ID }),
    ];

    const result = enrichEntries(list, { handle: 'veritasium', channelId: VERITASIUM_ID });

    expect(result.entries).toHaveLength(3);
    expect(result.entries[0]).toEqual(untouched);
    expect(result.entries[2]?.channelId).toBe(OTHER_ID);
  });

  it('never mutates the list it was given', () => {
    const original = entry({ handle: 'veritasium', displayName: '@veritasium' });
    const list = [original];

    enrichEntries(list, { handle: 'veritasium', channelId: VERITASIUM_ID, displayName: 'V' });

    expect(list).toHaveLength(1);
    expect(original).toEqual({
      channelId: null,
      handle: 'veritasium',
      displayName: '@veritasium',
      addedAt: 0,
    });
  });
});

describe('enrichEntries — display names', () => {
  it('replaces the "@handle" placeholder with the real channel name', () => {
    const list = [entry({ handle: 'veritasium', displayName: '@veritasium' })];

    const result = enrichEntries(list, { handle: 'veritasium', displayName: 'Veritasium' });

    expect(result.changed).toBe(true);
    expect(result.entries[0]?.displayName).toBe('Veritasium');
  });

  it('replaces a bare channel id with the real channel name', () => {
    const list = [entry({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID })];

    const result = enrichEntries(list, { channelId: VERITASIUM_ID, displayName: 'Veritasium' });

    expect(result.entries[0]?.displayName).toBe('Veritasium');
  });

  it('keeps a name the user already has, even while learning an identifier', () => {
    // What a legacy "/c/Veritasium" URL stores: a lowercased handle plus the nicest human
    // form the URL gave us. That name is not a placeholder and must survive.
    const list = [entry({ handle: 'veritasium', displayName: 'Veritasium' })];

    const result = enrichEntries(list, {
      handle: 'veritasium',
      channelId: VERITASIUM_ID,
      displayName: 'Veritasium Official',
    });

    expect(result.entries[0]?.channelId).toBe(VERITASIUM_ID);
    expect(result.entries[0]?.displayName).toBe('Veritasium');
  });

  it('refuses to "upgrade" a placeholder into another bare identifier', () => {
    const list = [entry({ handle: 'veritasium', displayName: '@veritasium' })];

    const result = enrichEntries(list, {
      handle: 'veritasium',
      channelId: VERITASIUM_ID,
      displayName: VERITASIUM_ID,
    });

    expect(result.entries[0]?.displayName).toBe('@veritasium');
  });

  it('ignores a blank author name', () => {
    const list = [entry({ handle: 'veritasium', displayName: '@veritasium' })];

    const result = enrichEntries(list, { handle: 'veritasium', displayName: '   ' });

    expect(result.changed).toBe(false);
    expect(result.reason).toBe('nothing-to-learn');
  });
});

describe('enrichEntries — a contradiction is never an enrichment', () => {
  it('leaves the entry untouched when the handles match but the ids differ', () => {
    const list = [entry({ channelId: VERITASIUM_ID, handle: 'veritasium', displayName: 'V' })];

    const result = enrichEntries(list, { channelId: OTHER_ID, handle: 'veritasium' });

    expect(result.changed).toBe(false);
    expect(result.reason).toBe('contradiction');
    expect(result.entries).toEqual(list);
  });

  it('leaves the entry untouched when the ids match but the handles differ', () => {
    const list = [entry({ channelId: VERITASIUM_ID, handle: 'veritasium', displayName: 'V' })];

    const result = enrichEntries(list, { channelId: VERITASIUM_ID, handle: 'someoneelse' });

    expect(result.changed).toBe(false);
    expect(result.reason).toBe('contradiction');
  });

  it('treats a differently-cased channel id as a different channel', () => {
    // Ids are case-SENSITIVE on YouTube, so this is a contradiction, not a match.
    const list = [entry({ channelId: VERITASIUM_ID, handle: 'veritasium', displayName: 'V' })];

    const result = enrichEntries(list, {
      channelId: VERITASIUM_ID.toLowerCase(),
      handle: 'veritasium',
    });

    expect(result.changed).toBe(false);
    expect(result.reason).toBe('contradiction');
  });

  it('refuses when the observation reconciles two entries that disagree with each other', () => {
    const list = [
      entry({ channelId: VERITASIUM_ID, handle: 'veritasium', displayName: 'V' }),
      entry({ channelId: OTHER_ID, handle: 'veritasium', displayName: 'Other' }),
    ];

    const result = enrichEntries(list, { handle: 'veritasium' });

    expect(result.changed).toBe(false);
    expect(result.reason).toBe('contradiction');
    expect(result.entries).toEqual(list);
  });
});

describe('enrichEntries — merging a channel the user added twice', () => {
  it('folds the handle entry and the id entry into one', () => {
    const list = [
      entry({ handle: 'veritasium', displayName: '@veritasium', addedAt: 500 }),
      entry({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID, addedAt: 100 }),
    ];

    const result = enrichEntries(list, {
      handle: 'veritasium',
      channelId: VERITASIUM_ID,
      displayName: 'Veritasium',
    });

    expect(result.changed).toBe(true);
    expect(result.reason).toBe('merged');
    expect(result.entries).toEqual([
      {
        channelId: VERITASIUM_ID,
        handle: 'veritasium',
        displayName: 'Veritasium',
        // The EARLIER addedAt survives: a merge must not make the channel look newer than
        // the day the user actually added it.
        addedAt: 100,
      },
    ]);
  });

  it('keeps the merged row where the first of the two used to be', () => {
    const list = [
      entry({ handle: 'kurzgesagt', displayName: 'Kurzgesagt' }),
      entry({ handle: 'veritasium', displayName: '@veritasium', addedAt: 500 }),
      entry({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID, addedAt: 100 }),
    ];

    const result = enrichEntries(list, { handle: 'veritasium', channelId: VERITASIUM_ID });

    expect(result.entries).toHaveLength(2);
    expect(result.entries[0]?.handle).toBe('kurzgesagt');
    expect(result.entries[1]).toMatchObject({
      handle: 'veritasium',
      channelId: VERITASIUM_ID,
    });
  });

  it('leaves the merged row matching BOTH identifiers it absorbed', () => {
    // This is what makes the merge safe: nothing the list used to cover stops being covered.
    const byHandle = entry({ handle: 'veritasium', displayName: '@veritasium', addedAt: 500 });
    const byId = entry({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID, addedAt: 100 });

    const { entries } = enrichEntries([byHandle, byId], {
      handle: 'veritasium',
      channelId: VERITASIUM_ID,
    });

    expect(isChannelListed(entries, { handle: 'veritasium' })).toBe(true);
    expect(isChannelListed(entries, { channelId: VERITASIUM_ID })).toBe(true);
  });

  it('prefers a real name over the placeholder when folding', () => {
    const list = [
      entry({ handle: 'veritasium', displayName: '@veritasium', addedAt: 500 }),
      entry({ channelId: VERITASIUM_ID, displayName: 'Veritasium', addedAt: 100 }),
    ];

    const result = enrichEntries(list, { handle: 'veritasium', channelId: VERITASIUM_ID });

    expect(result.entries[0]?.displayName).toBe('Veritasium');
  });
});

describe('enrichEntries — when there is nothing to do', () => {
  it('reports no-match rather than adding a channel nobody listed', () => {
    const list = [entry({ handle: 'kurzgesagt', displayName: 'Kurzgesagt' })];

    const result = enrichEntries(list, { handle: 'veritasium', channelId: VERITASIUM_ID });

    expect(result.changed).toBe(false);
    expect(result.reason).toBe('no-match');
    expect(result.entries).toEqual(list);
  });

  it('reports no-match on an empty list', () => {
    const result = enrichEntries([], { handle: 'veritasium', channelId: VERITASIUM_ID });

    expect(result.changed).toBe(false);
    expect(result.entries).toEqual([]);
  });

  it('does nothing when the observation carries no identifier at all', () => {
    const list = [entry({ handle: 'veritasium', displayName: '@veritasium' })];

    const result = enrichEntries(list, { displayName: 'Veritasium' });

    expect(result.changed).toBe(false);
    expect(result.reason).toBe('nothing-to-learn');
    expect(result.entries).toEqual(list);
  });

  it('does nothing when the entry already knows everything the page says', () => {
    const list = [
      entry({ channelId: VERITASIUM_ID, handle: 'veritasium', displayName: 'Veritasium' }),
    ];

    const result = enrichEntries(list, {
      channelId: VERITASIUM_ID,
      handle: 'veritasium',
      displayName: 'Veritasium',
    });

    expect(result.changed).toBe(false);
    expect(result.reason).toBe('nothing-to-learn');
  });
});

describe('enrichEntries — it can only ever learn, never weaken', () => {
  /**
   * The invariant that lets enrichment run without a Commitment Lock challenge: whatever the
   * observation, the SET OF CHANNELS the list covers is unchanged. Nothing new is allowed
   * (a whitelist cannot grow) and nothing stops being blocked (a blacklist cannot shrink).
   *
   * Asserted over a table rather than one case, because the failure mode this guards is a
   * future edit to `enrichEntries` — not today's code.
   */
  const startingLists: Array<{ name: string; list: ChannelEntry[] }> = [
    {
      name: 'a handle-only entry',
      list: [entry({ handle: 'veritasium', displayName: '@veritasium' })],
    },
    {
      name: 'an id-only entry',
      list: [entry({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID })],
    },
    {
      name: 'the same channel added twice by different routes',
      list: [
        entry({ handle: 'veritasium', displayName: '@veritasium', addedAt: 500 }),
        entry({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID, addedAt: 100 }),
      ],
    },
    {
      name: 'a list with an unrelated channel on it',
      list: [
        entry({ handle: 'kurzgesagt', displayName: 'Kurzgesagt' }),
        entry({ handle: 'veritasium', displayName: '@veritasium' }),
      ],
    },
  ];

  const observations: Array<{ name: string; observation: ChannelObservation }> = [
    { name: 'both identifiers', observation: { channelId: VERITASIUM_ID, handle: 'veritasium' } },
    { name: 'the id alone', observation: { channelId: VERITASIUM_ID } },
    { name: 'the handle alone', observation: { handle: 'veritasium' } },
    { name: 'a contradicting id', observation: { channelId: OTHER_ID, handle: 'veritasium' } },
    {
      name: 'a channel nobody listed',
      observation: { channelId: OTHER_ID, handle: 'someoneelse' },
    },
  ];

  for (const { name: listName, list } of startingLists) {
    for (const { name: observationName, observation } of observations) {
      it(`covers the same channels for ${listName} observing ${observationName}`, () => {
        const { entries } = enrichEntries(list, observation);

        // Nothing new: every surviving row describes a channel that was already listed.
        for (const result of entries) {
          expect(list.some((before) => sameChannel(before, result))).toBe(true);
        }
        // Nothing lost: every channel that was listed is still matched by some row.
        for (const before of list) {
          expect(entries.some((result) => sameChannel(before, result))).toBe(true);
        }
        // And every identifier that used to match still matches.
        for (const before of list) {
          if (before.channelId !== null) {
            expect(isChannelListed(entries, { channelId: before.channelId })).toBe(true);
          }
          if (before.handle !== null) {
            expect(isChannelListed(entries, { handle: before.handle })).toBe(true);
          }
        }
      });
    }
  }

  it('is not a weakening in either list mode, so the Commitment Lock never challenges it', () => {
    const before = [
      entry({ handle: 'veritasium', displayName: '@veritasium', addedAt: 500 }),
      entry({ channelId: VERITASIUM_ID, displayName: VERITASIUM_ID, addedAt: 100 }),
    ];
    const { entries: after, changed } = enrichEntries(before, {
      channelId: VERITASIUM_ID,
      handle: 'veritasium',
      displayName: 'Veritasium',
    });
    expect(changed).toBe(true);

    for (const channelMode of ['WHITELIST', 'BLACKLIST'] as const) {
      const withChannels = (channels: ChannelEntry[]): NudgeSettings =>
        settings({
          strictMode: { ...DEFAULT_SETTINGS.strictMode, enabled: true },
          rules: [
            siteRule({
              features: featuresWith('youtube', { youtube: { channelMode, channels } }),
            }),
          ],
        });

      expect(isWeakening(withChannels(before), withChannels(after))).toBe(false);
    }
  });
});
