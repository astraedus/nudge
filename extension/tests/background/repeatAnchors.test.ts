import { beforeEach, describe, expect, it } from 'vitest';

import {
  MAX_REPEAT_ANCHORS,
  REPEAT_ANCHORS_KEY,
  renewRepeatAnchor,
  repeatAnchorFor,
  withRepeatAnchor,
} from '../../src/background/repeatAnchors';
import { resetBrowser } from './fakeApis';

/**
 * Layer: the store on its own. What an anchor means to the repeat is in
 * `tests/background/repeatPauseRouter.test.ts`.
 */

const MIN = 60_000;
const NOW = new Date(2026, 8, 20, 12, 0, 0).getTime();

beforeEach(() => {
  resetBrowser();
});

describe('withRepeatAnchor', () => {
  it('sets one video and leaves the others alone', () => {
    expect(withRepeatAnchor({ a: 1 }, 'b', 2)).toEqual({ a: 1, b: 2 });
  });

  it('renews a video in place', () => {
    expect(withRepeatAnchor({ a: 1 }, 'a', 5)).toEqual({ a: 5 });
  });

  it('keeps an anchor past its time, because that is the pause being due', () => {
    // Pruned on write, another video's report would delete the clock of a tab left open on this
    // one, and that tab would play free from then on.
    const next = withRepeatAnchor({ open: NOW - 60 * MIN }, 'other', NOW + 10 * MIN);
    expect(next.open).toBe(NOW - 60 * MIN);
  });

  it('past the cap drops the one that ran out longest ago, never the one just set', () => {
    const next = withRepeatAnchor({ old: 1, mid: 2, young: 3 }, 'new', 0, 3);
    expect(Object.keys(next).sort()).toEqual(['mid', 'new', 'young']);
    expect(next.new).toBe(0);
  });

  it('does not change what it was given', () => {
    const map = { a: 1 };
    withRepeatAnchor(map, 'b', 2);
    expect(map).toEqual({ a: 1 });
  });
});

describe('the stored clock', () => {
  it('has no answer for a video that never passed the gate', async () => {
    expect(await repeatAnchorFor('abc')).toBeNull();
  });

  it('answers what renewing set, N minutes on from now', async () => {
    expect(await renewRepeatAnchor('abc', 10, NOW)).toBe(NOW + 10 * MIN);
    expect(await repeatAnchorFor('abc')).toBe(NOW + 10 * MIN);
  });

  it('is in session storage, so it goes with the browser', async () => {
    await renewRepeatAnchor('abc', 10, NOW);
    const stored = await chrome.storage.session.get(REPEAT_ANCHORS_KEY);
    expect(stored[REPEAT_ANCHORS_KEY]).toEqual({ abc: NOW + 10 * MIN });
    expect(await chrome.storage.local.get(REPEAT_ANCHORS_KEY)).toEqual({});
  });

  it('stays within the cap however many videos pass through', async () => {
    for (let i = 0; i <= MAX_REPEAT_ANCHORS; i += 1) {
      await renewRepeatAnchor(`video${i}`, 10, NOW + i);
    }
    const stored = await chrome.storage.session.get(REPEAT_ANCHORS_KEY);
    expect(Object.keys(stored[REPEAT_ANCHORS_KEY] as object)).toHaveLength(MAX_REPEAT_ANCHORS);
    expect(await repeatAnchorFor('video0')).toBeNull();
  });

  it('a video id cannot read something off the object prototype', async () => {
    await renewRepeatAnchor('abc', 10, NOW);
    expect(await repeatAnchorFor('constructor')).toBeNull();
  });
});
