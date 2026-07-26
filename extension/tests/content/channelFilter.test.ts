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
import { FEED_ALL_IDENTIFIABLE_HTML, FEED_MULTI_CHANNEL_HTML } from './fixtures/feedPage';
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

function config(overrides: Partial<ChannelConfig> = {}): ChannelConfig {
  return {
    enabled: true,
    channelMode: 'OFF',
    channels: [],
    channelBlockMode: 'DELAY',
    grayScreen: false,
    ...overrides,
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
    applyChannelFilter(document, config({ channelMode: 'OFF', channels: [channel({ channelId: ALPHA })] }));

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
      config({ channelMode: 'BLACKLIST', channels: [channel({ channelId: ALPHA })] }),
    );

    expect(isHidden('card-alpha')).toBe(true);
    expect(isHidden('card-bravo')).toBe(false);
    expect(isHidden('card-charlie')).toBe(false);
  });

  it('matches a channel the user added by handle even though the card shows only a handle', () => {
    loadFeed();
    applyChannelFilter(
      document,
      config({ channelMode: 'BLACKLIST', channels: [channel({ handle: 'bravochannel' })] }),
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
      config({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
    );

    expect(isHidden('card-alpha')).toBe(false);
    expect(isHidden('card-bravo')).toBe(true);
    expect(isHidden('card-charlie')).toBe(true);
  });

  it('leaves a video visible when its channel cannot be identified at all', () => {
    // Fail OPEN: a detection failure must look like normal YouTube, never like an empty
    // feed the user cannot explain. Documented in core/channels.ts decideChannel.
    loadFeed();
    const result = applyChannelFilter(
      document,
      config({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
    );

    expect(isHidden('card-no-channel')).toBe(false);
    expect(result.unidentified).toBeGreaterThan(0);
  });
});

describe('turning the channel filter off', () => {
  it('brings the hidden videos back without a reload', () => {
    loadFeed();
    const blocking = config({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] });
    applyChannelFilter(document, blocking);
    expect(isHidden('card-bravo')).toBe(true);

    applyChannelFilter(document, config({ channelMode: 'OFF' }));

    expect(isHidden('card-bravo')).toBe(false);
    expect(isHidden('card-charlie')).toBe(false);
  });

  it('brings them back when Nudge is switched off entirely', () => {
    loadFeed();
    applyChannelFilter(
      document,
      config({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
    );
    expect(isHidden('card-bravo')).toBe(true);

    applyChannelFilter(
      document,
      config({ enabled: false, channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
    );

    expect(isHidden('card-bravo')).toBe(false);
  });
});

describe('opening a video', () => {
  it('interrupts a video from a channel the user chose to avoid', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    const verdict = watchChannelVerdict(
      document,
      config({
        channelMode: 'BLACKLIST',
        channels: [channel({ channelId: PLAYER_RESPONSE_CHANNEL })],
        channelBlockMode: 'BREATHING',
      }),
      { url: WATCH_URL },
    );

    expect(verdict).toEqual({ action: 'BLOCK', mode: 'BREATHING' });
  });

  it('plays a video from a channel on the allow list', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    const verdict = watchChannelVerdict(
      document,
      config({
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
      config({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
      { url: WATCH_URL },
    );

    expect(verdict).toEqual({ action: 'ALLOW', reason: 'unknown-channel' });
  });

  it('is not interrupted at all while channel lists are off', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    expect(watchChannelVerdict(document, config({ channelMode: 'OFF' }), { url: WATCH_URL })).toBeNull();
  });
});

describe('gray-screen mode', () => {
  it('shows an allowed channel in colour', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    applyGrayColor(
      document,
      config({
        grayScreen: true,
        channelMode: 'WHITELIST',
        channels: [channel({ channelId: PLAYER_RESPONSE_CHANNEL })],
      }),
      { url: WATCH_URL },
    );

    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(true);
  });

  it('leaves a channel the user did not pick in grayscale', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    applyGrayColor(
      document,
      config({
        grayScreen: true,
        channelMode: 'WHITELIST',
        channels: [channel({ channelId: ALPHA })],
      }),
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
      config({ grayScreen: true, channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
      { url: WATCH_URL },
    );

    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(false);
  });

  it('stays grayscale on a feed, which is a mix of many channels', () => {
    loadFeed();
    const feedUrl = HOME_URL;
    applyGrayColor(
      document,
      config({
        grayScreen: true,
        channelMode: 'WHITELIST',
        channels: [channel({ channelId: ALPHA })],
      }),
      { url: feedUrl },
    );

    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(false);
  });

  it('restores full colour everywhere once the mode is switched off', () => {
    document.body.innerHTML = WATCH_PLAYER_RESPONSE_HTML;
    const on = config({
      grayScreen: true,
      channelMode: 'WHITELIST',
      channels: [channel({ channelId: PLAYER_RESPONSE_CHANNEL })],
    });
    applyGrayColor(document, on, { url: WATCH_URL });
    expect(document.documentElement.classList.contains(COLOR_CLASS)).toBe(true);

    applyGrayColor(document, { ...on, grayScreen: false }, { url: WATCH_URL });

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
      config({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
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
      config({ channelMode: 'WHITELIST', channels: [channel({ channelId: ALPHA })] }),
      { warn: (message) => warnings.push(message) },
    );

    expect(warnings).toEqual([]);
  });

  it('stays quiet while channel lists are switched off', () => {
    loadFeed();
    const warnings: string[] = [];

    applyChannelFilter(document, config({ channelMode: 'OFF' }), {
      warn: (message) => warnings.push(message),
    });

    expect(warnings).toEqual([]);
  });
});
