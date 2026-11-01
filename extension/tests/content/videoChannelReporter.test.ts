// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';

import type { ChannelConfig } from '../../src/content/channelFilter';
import {
  createVideoChannelReporter,
  type VideoChannelReport,
} from '../../src/content/videoChannelReporter';
import { SETTLE_MS, channelKey } from '../../src/core/channelFreshness';
import {
  REAL_WATCH_ID_INLINE_HANDLE_DOM_CHANNEL_ID,
  REAL_WATCH_ID_INLINE_HANDLE_DOM_HANDLE,
  WATCH_DOM_ONLY_HTML,
  WATCH_FIXTURE_VIDEO_ID,
  WATCH_ID_INLINE_HANDLE_DOM_HTML,
  WATCH_NOTHING_HTML,
} from './fixtures/watchPage';

/**
 * The reporter tells the worker whose video a watch page plays, so a whitelisted channel's
 * minutes are not spent from the daily limit. A WRONG report exempts the wrong video, so
 * what is under test is restraint: whitelist only, confirmed detections only, once each.
 */

const WATCH_URL = `https://www.youtube.com/watch?v=${WATCH_FIXTURE_VIDEO_ID}`;
const DOM_ONLY_URL = 'https://www.youtube.com/watch?v=domonlyvideo01';
const DOM_ONLY_CHANNEL_ID = 'UCdomonlystandard0000006';

function config(
  channelMode: 'WHITELIST' | 'BLACKLIST' | 'OFF' = 'WHITELIST',
  overrides: Partial<ChannelConfig> = {},
): ChannelConfig {
  return {
    enabled: true,
    siteMode: 'ALLOW',
    siteApplies: true,
    siteDelaySeconds: 15,
    grayscale: false,
    youtube: {
      channelMode,
      channels: [],
      channelBlockMode: 'DELAY',
      channelDelaySeconds: 15,
      disableAutoplay: false,
      creatorMode: false,
      myChannel: null,
      allowSubscriptions: false,
      subscribedChannels: [],
    },
    ...overrides,
  };
}

function load(html: string): Document {
  document.body.innerHTML = html;
  return document;
}

function recorder(): { sent: VideoChannelReport[]; report: (r: VideoChannelReport) => void } {
  const sent: VideoChannelReport[] = [];
  return { sent, report: (r) => sent.push(r) };
}

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('reporting whose video this is', () => {
  it('reports the URL and the confirmed identifiers under a whitelist', () => {
    const { sent, report } = recorder();
    createVideoChannelReporter(report).observe(load(WATCH_ID_INLINE_HANDLE_DOM_HTML), config(), {
      url: WATCH_URL,
    });
    expect(sent).toEqual([
      {
        url: WATCH_URL,
        channelId: REAL_WATCH_ID_INLINE_HANDLE_DOM_CHANNEL_ID,
        handle: `@${REAL_WATCH_ID_INLINE_HANDLE_DOM_HANDLE}`,
      },
    ]);
  });

  it('reports once however many times the page is re-checked', () => {
    const { sent, report } = recorder();
    const reporter = createVideoChannelReporter(report);
    const doc = load(WATCH_ID_INLINE_HANDLE_DOM_HTML);
    for (let i = 0; i < 5; i += 1) reporter.observe(doc, config(), { url: WATCH_URL });
    expect(sent).toHaveLength(1);
  });
});

describe('staying quiet', () => {
  it.each(['BLACKLIST', 'OFF'] as const)('sends nothing for a %s list', (mode) => {
    const { sent, report } = recorder();
    createVideoChannelReporter(report).observe(load(WATCH_ID_INLINE_HANDLE_DOM_HTML), config(mode), {
      url: WATCH_URL,
    });
    expect(sent).toEqual([]);
  });

  it("sends nothing for creator mode's implicit whitelist alone", () => {
    const { sent, report } = recorder();
    const implicit = config('OFF', {
      youtube: {
        channelMode: 'OFF',
        channels: [],
        channelBlockMode: 'DELAY',
        channelDelaySeconds: 15,
        disableAutoplay: false,
        creatorMode: true,
        myChannel: { channelId: 'UCmine', handle: 'mine', displayName: 'Me', addedAt: 0 },
        allowSubscriptions: false,
        subscribedChannels: [],
      },
    });
    createVideoChannelReporter(report).observe(load(WATCH_ID_INLINE_HANDLE_DOM_HTML), implicit, {
      url: WATCH_URL,
    });
    expect(sent).toEqual([]);
  });

  it('sends nothing when the rule is disabled', () => {
    const { sent, report } = recorder();
    createVideoChannelReporter(report).observe(
      load(WATCH_ID_INLINE_HANDLE_DOM_HTML),
      config('WHITELIST', { enabled: false }),
      { url: WATCH_URL },
    );
    expect(sent).toEqual([]);
  });

  it('sends nothing on a page that is not a watch page', () => {
    const { sent, report } = recorder();
    createVideoChannelReporter(report).observe(load(WATCH_ID_INLINE_HANDLE_DOM_HTML), config(), {
      url: 'https://www.youtube.com/shorts/abc',
    });
    expect(sent).toEqual([]);
  });

  it('sends nothing when the channel could not be identified', () => {
    const { sent, report } = recorder();
    createVideoChannelReporter(report).observe(load(WATCH_NOTHING_HTML), config(), { url: WATCH_URL });
    expect(sent).toEqual([]);
  });
});

describe('the settle window', () => {
  const SETTLING = {
    url: DOM_ONLY_URL,
    previousKey: channelKey({ channelId: DOM_ONLY_CHANNEL_ID, handle: null }),
    msSinceNav: 0,
  };

  it('sends nothing while the byline may still name the previous video', () => {
    const { sent, report } = recorder();
    createVideoChannelReporter(report).observe(load(WATCH_DOM_ONLY_HTML), config(), SETTLING);
    expect(sent).toEqual([]);
  });

  it('reports once the detection is confirmed', () => {
    const { sent, report } = recorder();
    createVideoChannelReporter(report).observe(load(WATCH_DOM_ONLY_HTML), config(), {
      ...SETTLING,
      msSinceNav: SETTLE_MS + 1,
    });
    expect(sent).toHaveLength(1);
    expect(sent[0]?.channelId).toBe(DOM_ONLY_CHANNEL_ID);
    expect(sent[0]?.url).toBe(DOM_ONLY_URL);
  });
});
