import { describe, expect, it } from 'vitest';

import {
  channelFreshness,
  channelKey,
  SETTLE_COLOR_MS,
  SETTLE_MS,
  SETTLE_RECHECK_MS,
  type FreshnessInput,
} from '../../src/core/channelFreshness';

/**
 * The settle window (live QA, 2026-07-26). After a watch -> watch SPA hop YouTube's owner
 * byline keeps describing the PREVIOUS video for a second or more, so acting on it
 * immediately either ungates a blocked channel or — much worse — accuses an allowed one.
 *
 * These are outcome-phrased in the terms the caller cares about: may we act on what we just
 * detected, or must we keep waiting?
 */

const ALLOWED = channelKey({ channelId: 'UCallowed0000000000001' })!;
const OTHER = channelKey({ channelId: 'UCother00000000000002' })!;

function input(overrides: Partial<FreshnessInput> = {}): FreshnessInput {
  return {
    videoId: 'newvideo001',
    inlineVideoId: 'oldvideo000',
    detectedKey: OTHER,
    previousKey: ALLOWED,
    msSinceNav: 0,
    ...overrides,
  };
}

describe('channelKey', () => {
  it('treats the same channel reached by id or by handle as one identity', () => {
    expect(channelKey({ channelId: 'UCabc', handle: null })).toBe(
      channelKey({ channelId: 'UCabc', handle: null }),
    );
    expect(channelKey({ handle: 'Veritasium' })).toBe(channelKey({ handle: 'veritasium' }));
  });

  it('has no identity for a channel that could not be identified', () => {
    expect(channelKey(null)).toBeNull();
    expect(channelKey({ channelId: null, handle: null })).toBeNull();
  });
});

describe('right after navigating to a different video', () => {
  it('acts immediately once the byline shows a different channel than before', () => {
    // The byline demonstrably re-rendered, so there is nothing left to wait for.
    expect(channelFreshness(input({ detectedKey: OTHER, previousKey: ALLOWED }))).toBe(
      'CONFIRMED',
    );
  });

  it('keeps waiting while the byline still shows the channel from the previous video', () => {
    // Indistinguishable from "stale", so we withhold the verdict rather than risk accusing
    // a channel the user allowed.
    expect(channelFreshness(input({ detectedKey: ALLOWED, previousKey: ALLOWED }))).toBe(
      'SETTLING',
    );
  });

  it('acts immediately when the page data itself describes the video in the address bar', () => {
    // A full page load: the inline data is authoritative, so no waiting is warranted.
    expect(
      channelFreshness(
        input({ videoId: 'newvideo001', inlineVideoId: 'newvideo001', detectedKey: ALLOWED }),
      ),
    ).toBe('CONFIRMED');
  });

  it('stops waiting once the settle window has elapsed', () => {
    const stuck = input({ detectedKey: ALLOWED, previousKey: ALLOWED });

    expect(channelFreshness({ ...stuck, msSinceNav: SETTLE_MS - 1 })).toBe('SETTLING');
    expect(channelFreshness({ ...stuck, msSinceNav: SETTLE_MS })).toBe('CONFIRMED');
  });

  it('acts immediately on the first video of a session, with nothing to be stale from', () => {
    expect(channelFreshness(input({ previousKey: null, detectedKey: ALLOWED }))).toBe(
      'CONFIRMED',
    );
  });

  it('stops waiting when nothing can be identified, leaving the fail-open to handle it', () => {
    // Detecting NOTHING is already safe to act on: the caller's unknown-channel path allows
    // the video through without an interstitial and withholds colour. Waiting here would add
    // latency without changing any outcome.
    expect(channelFreshness(input({ detectedKey: null, previousKey: ALLOWED }))).toBe(
      'CONFIRMED',
    );
  });
});

describe('the reverse case that made this necessary', () => {
  it('does not act on a blocked-looking byline in the moment after leaving that channel', () => {
    // blocked -> allowed hop. The byline still reports the blocked channel; acting now is
    // exactly the 3-5s false "This channel is off your list" QA saw on an allowed video.
    const justAfterNav = input({
      detectedKey: OTHER,
      previousKey: OTHER,
      msSinceNav: 200,
    });

    expect(channelFreshness(justAfterNav)).toBe('SETTLING');
  });

  it('acts as soon as the byline catches up to the allowed channel', () => {
    const bylineCaughtUp = input({
      detectedKey: ALLOWED,
      previousKey: OTHER,
      msSinceNav: 900,
    });

    expect(channelFreshness(bylineCaughtUp)).toBe('CONFIRMED');
  });
});

describe('the post-navigation re-check schedule', () => {
  it('re-checks several times, ending after the settle window closes', () => {
    // The debounced observer can be starved indefinitely by YouTube's mutation storm, so
    // these fixed timers are what guarantee the corrective pass actually happens.
    expect(SETTLE_RECHECK_MS.length).toBeGreaterThan(2);
    expect(Math.min(...SETTLE_RECHECK_MS)).toBeLessThan(1_000);
    expect(Math.max(...SETTLE_RECHECK_MS)).toBeGreaterThan(SETTLE_MS);
  });

  it('is strictly increasing, so each re-check is a fresh look rather than a duplicate', () => {
    const sorted = [...SETTLE_RECHECK_MS].sort((a, b) => a - b);
    expect(SETTLE_RECHECK_MS).toEqual(sorted);
    expect(new Set(SETTLE_RECHECK_MS).size).toBe(SETTLE_RECHECK_MS.length);
  });
});

describe('colour is less patient than the block verdict', () => {
  /**
   * Two videos by the SAME channel is the case that needs this: the byline never changes, so
   * nothing can confirm freshness and only the backstop ends the wait. Sharing one 6s value
   * meant watching several videos from a channel you deliberately whitelisted sat in
   * grayscale for six seconds each time, which reads as the feature being broken.
   */
  const sameChannelHop = () => input({ detectedKey: ALLOWED, previousKey: ALLOWED });

  it('restores colour sooner than it would deliver a verdict', () => {
    expect(SETTLE_COLOR_MS).toBeLessThan(SETTLE_MS);
  });

  it('is still withholding both a moment after the hop', () => {
    const justAfter = { ...sameChannelHop(), msSinceNav: 200 };

    expect(channelFreshness({ ...justAfter, settleMs: SETTLE_COLOR_MS })).toBe('SETTLING');
    expect(channelFreshness(justAfter)).toBe('SETTLING');
  });

  it('gives colour back while the verdict is still deliberately waiting', () => {
    const midWindow = { ...sameChannelHop(), msSinceNav: SETTLE_COLOR_MS };

    // Cosmetic decision: settled.
    expect(channelFreshness({ ...midWindow, settleMs: SETTLE_COLOR_MS })).toBe('CONFIRMED');
    // Punitive decision: still waiting, because gating the wrong video is far worse.
    expect(channelFreshness(midWindow)).toBe('SETTLING');
  });
});
