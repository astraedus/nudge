import { beforeEach, describe, expect, it } from 'vitest';

import {
  loadVideoChannels,
  recordVideoChannel,
  rememberVideoChannel,
  videoChannelLookup,
} from '../../src/background/videoChannels';
import { resetBrowser } from './fakeApis';

beforeEach(() => {
  resetBrowser();
});

describe('rememberVideoChannel', () => {
  it('stores the trimmed identifiers with the report time', () => {
    const next = rememberVideoChannel({}, 'v1', { channelId: ' UCx ', handle: '@x' }, 5);
    expect(next).toEqual({ v1: { channelId: 'UCx', handle: '@x', at: 5 } });
  });

  it('returns the same map for a report with no usable identifier, so nothing is written', () => {
    const map = {};
    expect(rememberVideoChannel(map, 'v1', { channelId: null, handle: '  ' }, 5)).toBe(map);
    expect(rememberVideoChannel(map, 'v1', { channelId: 'x'.repeat(200), handle: null }, 5)).toBe(map);
  });

  it('drops the OLDEST report past the cap', () => {
    let map = {};
    map = rememberVideoChannel(map, 'old', { channelId: 'UCa', handle: null }, 1, 2);
    map = rememberVideoChannel(map, 'mid', { channelId: 'UCb', handle: null }, 2, 2);
    map = rememberVideoChannel(map, 'new', { channelId: 'UCc', handle: null }, 3, 2);
    expect(Object.keys(map).sort()).toEqual(['mid', 'new']);
  });
});

describe('the stored map', () => {
  it('round-trips through storage.session and answers the sync lookup', async () => {
    expect(await recordVideoChannel('v1', { channelId: 'UCx', handle: null }, 1)).toBe(true);
    expect(await recordVideoChannel('v2', { channelId: null, handle: null }, 2)).toBe(false);
    expect(Object.keys(await loadVideoChannels())).toEqual(['v1']);

    const lookup = await videoChannelLookup();
    expect(lookup('v1')).toEqual({ channelId: 'UCx', handle: null });
    expect(lookup('v2')).toBeNull();
    // An inherited property name is not a stored video.
    expect(lookup('constructor')).toBeNull();
  });
});
