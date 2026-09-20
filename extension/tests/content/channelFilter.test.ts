// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';

import {
  applyChannelFilter,
  applyGrayColor,
  watchChannelVerdict,
  type ChannelConfig,
} from '../../src/content/channelFilter';
import { CHANNEL_HIDDEN_CLASS, COLOR_CLASS } from '../../src/content/selectors';
import type { ChannelEntry } from '../../src/core/settingsSchema';
import {
  FEED_ALL_IDENTIFIABLE_HTML,
  FEED_MULTI_CHANNEL_HTML,
  FEED_WITH_ADS_AND_SHORTS_HTML,
} from './fixtures/feedPage';
import {
  WATCH_FIXTURE_VIDEO_ID,
  WATCH_NOTHING_HTML,
  WATCH_PLAYER_RESPONSE_HTML,
} from './fixtures/watchPage';

/**
 * These cover the SEAM: the decision matrix itself is exhaustively tested in
 * tests/core/channels.test.ts, and detection in tests/content/channelDetection.test.ts.
 * What can only break here is the wiring — does an allowed channel actually stay on screen,
 * does a disallowed one actually disappear, and does the page actually turn colour.
 */

const ALPHA = 'UCALPHACHANNEL000000001';
const PLAYER_RESPONSE_CHANNEL = 'UCplayerresponse00000001';

function channel(overrides: Partial<ChannelEntry>): ChannelEntry {
  return {
    channelId: null,
    handle: null,
    displayName: 'Test Channel',
    addedAt: 0,
    ...overrides,
  };
}

/**
 * `ChannelConfig` since v0.2: the flat `YoutubeConfig` is gone, the channel-list fields
 * live nested under `youtube` (null behaves exactly like `channelMode: 'OFF'`), and the
 * config now also carries the SITE's resolved state, because the site rule stands behind
 * the whitelist once it is in force (`decideWatchGate`). `siteApplies: false` here is the
 * "no site rule in the picture" default that every pre-existing test in this file assumed
 * implicitly before that concept existed.
 */
function config(overrides: Partial<ChannelConfig> = {}): ChannelConfig {
  return {
    enabled: true,
    siteMode: 'ALLOW',
    siteApplies: false,
    siteDelaySeconds: 15,
    grayscale: false,
    youtube: {
      channelMode: 'OFF',
      channels: [],
      channelBlockMode: 'DELAY',
      channelDelaySeconds: 15,
      disableAutoplay: false,
    },
    ...overrides,
  };
}

/**
 * Second helper for the common case: overriding only the nested `youtube` block (and,
 * optionally, a few top-level site fields alongside it) without clobbering the rest of
 * `config()`'s defaults — a plain `config({ channelMode: ... })` would silently do nothing
 * useful now that those fields live one level down.
 */
function withYoutube(
  youtubeOverrides: Partial<NonNullable<ChannelConfig['youtube']>>,
  configOverrides: Partial<ChannelConfig> = {},
): ChannelConfig {
  const base = config(configOverrides);
  return {
    ...base,
    youtube: { ...(base.youtube as NonNullable<ChannelConfig['youtube']>), ...youtubeOverrides },
  };
}

function loadFeed(): Document {
  document.body.innerHTML = FEED_MULTI_CHANNEL_HTML;
  return document;
}

function cardFor(testId: string): HTMLElement {
  const card = document.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  if (card === null) throw new Error(`fixture is missing the card "${testId}"`);
  return card;
}

function isHidden(testId: string): boolean {
  return cardFor(testId).classList.contains(CHANNEL_HIDDEN_CLASS);
}

/**
 * jsdom's document origin is not youtube.com, so `pageTypeFor(doc.location.href)` would
 * classify every fixture as 'other'. The real controller already passes the URL it just
 * observed, so these do the same.
 */
// The `v` MUST match the fixture's inline videoId: detection deliberately rejects inline
// data that describes a different video (the SPA-staleness guard), so a mismatched URL
// here would silently be testing the DOM tier instead of the one we mean to exercise.
const WATCH_URL = `https://www.youtube.com/watch?v=${WATCH_FIXTURE_VIDEO_ID}`;
const HOME_URL = 'https://www.youtube.com/';

beforeEach(() => {
  document.body.innerHTML = '';
  document.documentElement.classList.remove(COLOR_CLASS);
});

describe('the feed with channel lists off', () => {
  it('shows every video, whatever is on the list', () => {
    loadFeed();
    applyChannelFilter(
      document,
      withYoutube({ channelMode: 'OFF', channels: [channel({ channelId: ALPHA })] }),
    );

    expect(isHidden('card-alpha')).toBe(false);
    expect(isHidden('card-bravo')).toBe(false);
    expect(isHidden('card-charlie')).toBe(false);
  });
});

describe('the feed in "block these channels" mode', () => {
  it('removes a video from a listed channel and keeps the rest', () => {
    loadFeed();
    applyChannelFilter(
      document,
      withYoutube({ channelMode: 'BLACKLIST', channels: [channel({ channelId: ALPHA })] }),
    );

    expect(isHidden('card-alpha')).toBe(true);
    expect(isHidden('card-bravo')).toBe(false);
    expect(isHidden('card-charlie')).toBe(false);
  });

  it('matches a channel the user added by handle even though the card shows only a handle', () => {
    loadFeed();
    applyChannelFilter(
      document,
      withYoutube({ channelMode: 'BLACKLIST', channels: [channel({ handle: 'bravochannel' })] }),
    );

    expect(isHidden('card-bravo')).toBe(true);
    expect(isHidden('card-alpha')).toBe(false);
  });
});

describe('the feed in "only allow these channels" mode', () => {
  it('keeps the one channel on the list and removes the others', () => {
    loadFeed();
    applyChannelFilter(
      document,
      withYoutube({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
    );

    expect(isHidden('card-alpha')).toBe(false);
    expect(isHidden('card-bravo')).toBe(true);
    expect(isHidden('card-charlie')).toBe(true);
  });

  it('leaves a video visible when its channel cannot be identified at all', () => {
    // Fail OPEN: a detection failure must look like normal YouTube, never like an empty
    // feed the user cannot explain. Documented in core/channels.ts decideChannel. Feed
    // composition always uses this fail-open decision, never the watch gate's site-default
    // one — see the "feed keeps the fail-open decision" test below.
    loadFeed();
    const result = applyChannelFilter(
      document,
      withYoutube({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
    );

    expect(isHidden('card-no-channel')).toBe(false);
    expect(result.unidentified).toBeGreaterThan(0);
  });

  it('keeps the fail-open feed decision even while the site rule is in force', () => {
    // Feed composition is not the watch gate: hiding a card is not refusing to play a
    // video, so it never adopts decideWatchGate's fail-CLOSED behaviour even once the
    // youtube.com rule is in force.
    loadFeed();
    const result = applyChannelFilter(
      document,
      withYoutube(
        { channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] },
        { siteApplies: true, siteMode: 'HARD_BLOCK' },
      ),
    );

    expect(isHidden('card-no-channel')).toBe(false);
    expect(result.unidentified).toBeGreaterThan(0);
  });
});

describe('turning the channel filter off', () => {
  it('brings the hidden videos back without a reload', () => {
    loadFeed();
    const blocking = withYoutube({
      channelMode: 'WHITELIST',
      channels: [channel({ channelId: ALPHA })],
    });
    applyChannelFilter(document, blocking);
    expect(isHidden('card-bravo')).toBe(true);

    applyChannelFilter(document, withYoutube({ channelMode: 'OFF' }));

    expect(isHidden('card-bravo')).toBe(false);
    expect(isHidden('card-charlie')).toBe(false);
  });

  it('brings them back when Nudge is switched off entirely', () => {
    loadFeed();
    applyChannelFilter(
      document,
      withYoutube({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
    );
    expect(isHidden('card-bravo')).toBe(true);

    applyChannelFilter(
      document,
      withYoutube(
        { channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] },
        { enabled: false },
      ),
    );

    expect(isHidden('card-bravo')).toBe(false);
  });
});

describe('opening a video', () => {
  it('interrupts a video from a channel the user chose to avoid', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    const verdict = watchChannelVerdict(
      document,
      withYoutube({
        channelMode: 'BLACKLIST',
        channels: [channel({ channelId: PLAYER_RESPONSE_CHANNEL })],
        channelBlockMode: 'BREATHING',
      }),
      { url: WATCH_URL },
    );

    expect(verdict).toEqual({
      action: 'BLOCK',
      mode: 'BREATHING',
      delaySeconds: 15,
      source: 'channel-rule',
      reason: 'listed',
    });
  });

  it('plays a video from a channel on the allow list', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    const verdict = watchChannelVerdict(
      document,
      withYoutube({
        channelMode: 'WHITELIST',
        channels: [channel({ channelId: PLAYER_RESPONSE_CHANNEL })],
      }),
      { url: WATCH_URL },
    );

    expect(verdict?.action).toBe('ALLOW');
  });

  it('plays on when the channel cannot be identified, rather than blocking all of YouTube', () => {
    document.body.innerHTML = WATCH_NOTHING_HTML;
    const verdict = watchChannelVerdict(
      document,
      withYoutube({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
      { url: WATCH_URL },
    );

    expect(verdict).toEqual({ action: 'ALLOW', reason: 'unknown-channel' });
  });

  it('is not interrupted at all while channel lists are off', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    expect(
      watchChannelVerdict(document, withYoutube({ channelMode: 'OFF' }), { url: WATCH_URL }),
    ).toBeNull();
  });
});

describe('the watch gate when the youtube.com site rule is in force', () => {
  /**
   * `decideWatchGate` itself is exhaustively covered in tests/core/channels.test.ts; these
   * only prove the WIRING — that `watchChannelVerdict` actually plugs the resolved site
   * fields through to it, using a real watch-page fixture instead of a hand-built input
   * object. Site and channel rule are deliberately given DIFFERENT modes/delays so a value
   * leaking from the wrong rule would be caught rather than accidentally matching.
   */
  const SITE_MODE = 'BREATHING';
  const SITE_DELAY_SECONDS = 40;

  function siteInForceConfig(overrides: Partial<ChannelConfig> = {}): ChannelConfig {
    return withYoutube(
      { channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] },
      { siteApplies: true, siteMode: SITE_MODE, siteDelaySeconds: SITE_DELAY_SECONDS, ...overrides },
    );
  }

  it('still lets an allowed channel play, even though the site rule is in force', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    const verdict = watchChannelVerdict(
      document,
      withYoutube(
        { channelMode: 'WHITELIST', channels: [channel({ channelId: PLAYER_RESPONSE_CHANNEL })] },
        { siteApplies: true, siteMode: SITE_MODE, siteDelaySeconds: SITE_DELAY_SECONDS },
      ),
      { url: WATCH_URL },
    );

    expect(verdict).toEqual({ action: 'ALLOW', reason: 'listed' });
  });

  it("holds a video from a channel not on the list behind the SITE's mode and delay, not the channel rule's", () => {
    // Fixture's channel is PLAYER_RESPONSE_CHANNEL, which is NOT on the whitelist below.
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    const verdict = watchChannelVerdict(document, siteInForceConfig(), { url: WATCH_URL });

    expect(verdict).toEqual({
      action: 'BLOCK',
      mode: SITE_MODE,
      delaySeconds: SITE_DELAY_SECONDS,
      source: 'site-default',
      reason: 'not-listed',
    });
  });

  it("holds an unidentifiable video behind the site's own rule instead of letting it through", () => {
    document.body.innerHTML = WATCH_NOTHING_HTML;
    const verdict = watchChannelVerdict(document, siteInForceConfig(), { url: WATCH_URL });

    expect(verdict).toEqual({
      action: 'BLOCK',
      mode: SITE_MODE,
      delaySeconds: SITE_DELAY_SECONDS,
      source: 'site-default',
      reason: 'unknown-channel',
    });
  });

  it('lets an unidentifiable video through when the site rule is NOT in force (the mirror case)', () => {
    document.body.innerHTML = WATCH_NOTHING_HTML;
    const verdict = watchChannelVerdict(
      document,
      withYoutube(
        { channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] },
        { siteApplies: false },
      ),
      { url: WATCH_URL },
    );

    expect(verdict).toEqual({ action: 'ALLOW', reason: 'unknown-channel' });
  });
});

describe('gray-screen mode', () => {
  it('shows an allowed channel in colour', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    applyGrayColor(
      document,
      withYoutube(
        { channelMode: 'WHITELIST', channels: [channel({ channelId: PLAYER_RESPONSE_CHANNEL })] },
        { grayscale: true },
      ),
      { url: WATCH_URL },
    );

    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(true);
  });

  it('leaves a channel the user did not pick in grayscale', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    applyGrayColor(
      document,
      withYoutube(
        { channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] },
        { grayscale: true },
      ),
      { url: WATCH_URL },
    );

    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(false);
  });

  it('stays grayscale when the channel cannot be identified', () => {
    // The opposite bias to blocking: staying gray is harmless, so an unknown channel never
    // earns colour.
    document.body.innerHTML = WATCH_NOTHING_HTML;
    applyGrayColor(
      document,
      withYoutube(
        { channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] },
        { grayscale: true },
      ),
      { url: WATCH_URL },
    );

    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(false);
  });

  it('stays grayscale on a feed, which is a mix of many channels', () => {
    loadFeed();
    const feedUrl = HOME_URL;
    applyGrayColor(
      document,
      withYoutube(
        { channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] },
        { grayscale: true },
      ),
      { url: feedUrl },
    );

    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(false);
  });

  it('restores full colour everywhere once the mode is switched off', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    const on = withYoutube(
      { channelMode: 'WHITELIST', channels: [channel({ channelId: PLAYER_RESPONSE_CHANNEL })] },
      { grayscale: true },
    );
    applyGrayColor(document, on, { url: WATCH_URL });
    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(true);

    applyGrayColor(document, { ...on, grayscale: false }, { url: WATCH_URL });

    // The grayscale stylesheet is unregistered by the worker; this only has to make sure no
    // stale colour class is left behind to fight the next enable.
    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(false);
  });
});

describe('when YouTube changes its DOM and channels stop being identifiable', () => {
  /**
   * The fail-open is deliberate, but it must not be SILENT: with no signal, selector rot
   * degrades the channel filter into a no-op that looks exactly like "nothing on the list".
   */
  it('warns that filtering is degraded, naming how many cards it could not identify', () => {
    loadFeed();
    const warnings: string[] = [];

    applyChannelFilter(
      document,
      withYoutube({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
      { warn: (message) => warnings.push(message) },
    );

    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toMatch(/degraded/i);
    expect(warnings[0]).toMatch(/left visible/i);
  });

  it('stays quiet when every card on the page was identified', () => {
    // No false alarms: a canary that cries wolf is a canary nobody listens to.
    document.body.innerHTML = FEED_ALL_IDENTIFIABLE_HTML;
    const warnings: string[] = [];

    applyChannelFilter(
      document,
      withYoutube({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
      { warn: (message) => warnings.push(message) },
    );

    expect(warnings).toEqual([]);
  });

  it('stays quiet while channel lists are switched off', () => {
    loadFeed();
    const warnings: string[] = [];

    applyChannelFilter(document, withYoutube({ channelMode: 'OFF' }), {
      warn: (message) => warnings.push(message),
    });

    expect(warnings).toEqual([]);
  });
});

describe('the degraded-detection canary on an ordinary feed', () => {
  /**
   * REGRESSION (live QA, 2026-07-26): the canary counted ads and Shorts lockups, which have
   * no channel by nature, as unidentifiable, so it cried "YouTube changed its DOM" on every
   * normal home feed (~15 cards). A warning that fires when nothing is wrong is a warning
   * nobody reads, which would have cost us the real signal.
   */
  function runOnMixedFeed(): { warnings: string[]; unidentified: number } {
    document.body.innerHTML = FEED_WITH_ADS_AND_SHORTS_HTML;
    const warnings: string[] = [];
    const result = applyChannelFilter(
      document,
      withYoutube({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
      { warn: (message) => warnings.push(message) },
    );
    return { warnings, unidentified: result.unidentified };
  }

  it('counts only the organic video it genuinely could not identify', () => {
    // One organic mystery card; the ad and both Shorts lockups must not count.
    expect(runOnMixedFeed().unidentified).toBe(1);
  });

  it('still leaves every unidentifiable card visible', () => {
    runOnMixedFeed();

    const mystery = document.querySelector('[data-testid="card-organic-unreadable"]');
    const ad = document.querySelector('[data-testid="card-ad"]');
    expect(mystery?.classList.contains(CHANNEL_HIDDEN_CLASS)).toBe(false);
    expect(ad?.classList.contains(CHANNEL_HIDDEN_CLASS)).toBe(false);
  });

  it('stays quiet on a feed whose only unidentifiable cards are ads and Shorts', () => {
    document.body.innerHTML = FEED_WITH_ADS_AND_SHORTS_HTML;
    const organic = document.querySelector('[data-testid="card-organic-unreadable"]');
    organic?.remove();

    const warnings: string[] = [];
    applyChannelFilter(
      document,
      withYoutube({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
      { warn: (message) => warnings.push(message) },
    );

    expect(warnings).toEqual([]);
  });
});
