/**
 * @vitest-environment jsdom
 * @vitest-environment-options { "url": "https://www.youtube.com/@veritasium/videos" }
 */
/**
 * The YouTube whitelist, driven through `initYoutubeContentScript` the way a user meets it.
 *
 * Live QA 2026-10-23 (Playwright on youtube.com, HARD_BLOCK + DELAY, a `/@handle` and a
 * `/channel/UC` entry) found the whitelist only half enforced:
 *
 *  - B1, the core bug. Clicking a whitelisted channel's video FROM ITS CHANNEL PAGE showed
 *    "Nudge couldn't identify this video's channel" and then left the video paused at 0:00.
 *    The page had no previous video, so nothing told the settle window to wait, the unknown
 *    channel failed closed, and the media hold never let go.
 *  - E1 / G1. After a Delay countdown completed, or on Back to an allowed video, the video
 *    stayed paused: the hold stopped it and nothing started it again.
 *  - F4 / F6 / A13. In-SPA, the home feed, search and any other channel's page opened freely
 *    while the same URLs typed into the address bar went to the block page.
 *
 * "Interrupted" = the interstitial is attached. "Played" = the page's video had `play()`
 * called on it after being held. "Left for X" = the controller replaced the location with X.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  initYoutubeContentScript,
  type YoutubeController,
  type YoutubeNavigator,
} from '../../src/content/youtube';
import { NUDGE_OVERLAY_ID } from '../../src/content/selectors';
import type { SiteConfig } from '../../src/core/protocol';
import type { ChannelEntry } from '../../src/core/settingsSchema';

const ORIGIN = 'https://www.youtube.com';
const LISTED_HANDLE = 'veritasium';
const LISTED_ID = 'UCHnyfMqiRRG1u-2MsSQLbXA';
const VIDEO = 'NiCgcUTL9_Q';
const OTHER_VIDEO = 'JAA-8kNSVjo';

const listed: ChannelEntry = {
  channelId: null,
  handle: LISTED_HANDLE,
  displayName: '@veritasium',
  addedAt: 0,
};

/** "Block YouTube except these channels", as the worker resolves it. */
function blockedSiteConfig(overrides: Partial<SiteConfig> = {}, youtube: Partial<NonNullable<SiteConfig['youtube']>> = {}): SiteConfig {
  return {
    enabled: true,
    domain: 'youtube.com',
    platform: 'youtube',
    siteMode: 'HARD_BLOCK',
    siteDelaySeconds: 5,
    siteApplies: true,
    siteLimitReached: false,
    tempAllowedUntil: null,
    grayscale: false,
    gates: [],
    hides: {},
    youtube: {
      channelMode: 'WHITELIST',
      channels: [listed],
      channelBlockMode: 'HARD_BLOCK',
      channelDelaySeconds: 15,
      disableAutoplay: false,
      creatorMode: false,
      myChannel: null,
      ...youtube,
    },
    ...overrides,
  };
}

/* ------------------------------------------------------------------ the page */

/**
 * The real page-manager shape (live capture 2026-10-23): YouTube keeps the channel page it
 * came from as a HIDDEN `ytd-browse`, and the watch page is a `ytd-watch-flexy` whose
 * `video-id` attribute and owner byline update together, ~1.6-2.3s after the URL changes.
 */
function channelPageHtml(handle: string): string {
  return `
    <ytd-app>
      <ytd-page-manager id="page-manager">
        <ytd-browse page-subtype="channels">
          <a href="/@${handle}">channel header</a>
          <a href="/watch?v=${VIDEO}">a video</a>
        </ytd-browse>
      </ytd-page-manager>
    </ytd-app>
  `;
}

/** Add the watch page in the state YouTube shows right after the hop: no owner yet. */
function addEmptyWatchPlayer(): HTMLElement {
  const manager = document.querySelector('ytd-page-manager');
  if (manager === null) throw new Error('fixture has no page manager');
  manager.querySelector('ytd-browse')?.setAttribute('hidden', '');
  const flexy = document.createElement('ytd-watch-flexy');
  flexy.innerHTML = '<div id="player"></div><ytd-watch-metadata><div id="owner"></div></ytd-watch-metadata>';
  manager.append(flexy);
  return flexy;
}

/** YouTube's render a beat later: the player names its video, and the byline its owner. */
function renderOwner(flexy: HTMLElement, videoId: string, handle: string): void {
  flexy.setAttribute('video-id', videoId);
  const owner = flexy.querySelector('#owner');
  if (owner === null) throw new Error('fixture has no owner slot');
  owner.innerHTML = `
    <ytd-video-owner-renderer>
      <a class="yt-simple-endpoint" href="/@${handle}"></a>
      <ytd-channel-name id="channel-name"><a href="/@${handle}">${handle}</a></ytd-channel-name>
    </ytd-video-owner-renderer>`;
}

interface FakeVideo {
  el: HTMLVideoElement;
  play: ReturnType<typeof vi.fn>;
  pause: ReturnType<typeof vi.fn>;
}

/**
 * A video that is PLAYING, in the player. jsdom has no playback, so `paused` follows the
 * stubs: `play()` starts it and `pause()` stops it, like the real element.
 */
function addPlayingVideo(flexy: HTMLElement): FakeVideo {
  const el = document.createElement('video');
  let paused = false;
  const play = vi.fn(() => {
    paused = false;
    return Promise.resolve();
  });
  const pause = vi.fn(() => {
    paused = true;
  });
  Object.defineProperty(el, 'paused', { configurable: true, get: () => paused });
  Object.assign(el, { play, pause });
  flexy.querySelector('#player')?.append(el);
  return { el, play, pause };
}

function isInterrupted(): boolean {
  return document.getElementById(NUDGE_OVERLAY_ID) !== null;
}

function spaHop(path: string): void {
  window.history.pushState({}, '', path);
  document.dispatchEvent(new CustomEvent('yt-navigate-finish'));
}

/** Let the config round trip the controller starts on a navigation resolve. */
async function settleConfig(): Promise<void> {
  for (let i = 0; i < 5; i += 1) await Promise.resolve();
}

/* ------------------------------------------------------------------ harness */

let controller: YoutubeController | null = null;
let leftFor: string[] = [];
const navigator: YoutubeNavigator = {
  blockPageFor: (target) => `chrome-extension://nudge/blocked.html?target=${target}`,
  replace: (url) => {
    leftFor.push(url);
  },
};

async function startOn(
  path: string,
  html: string,
  config: SiteConfig | (() => Promise<SiteConfig>),
): Promise<void> {
  window.history.replaceState({}, '', path);
  document.body.innerHTML = html;
  const fetchConfig = typeof config === 'function' ? config : () => Promise.resolve(config);
  controller = initYoutubeContentScript(document, fetchConfig, navigator);
  await controller.reload();
}

beforeEach(() => {
  vi.useFakeTimers();
  leftFor = [];
});

afterEach(() => {
  controller?.stop();
  controller = null;
  vi.useRealTimers();
  document.body.innerHTML = '';
  document.head.innerHTML = '';
});

/* ------------------------------------------------------------------ B1 */

describe('opening a whitelisted channel\'s video from its channel page (B1)', () => {
  it('never shows an interstitial, and the video plays once YouTube says whose it is', async () => {
    await startOn('/@veritasium/videos', channelPageHtml(LISTED_HANDLE), blockedSiteConfig());

    spaHop(`/watch?v=${VIDEO}`);
    const flexy = addEmptyWatchPlayer();
    const video = addPlayingVideo(flexy);
    video.el.dispatchEvent(new Event('play'));
    await settleConfig();

    // The owner is not on screen yet. That is "not known YET", not "unknown": nothing may
    // accuse the channel, and the video must not run ungated either.
    expect(isInterrupted()).toBe(false);
    expect(video.pause).toHaveBeenCalled();

    // The live render, ~1.6s after the URL changed.
    vi.advanceTimersByTime(1_600);
    renderOwner(flexy, VIDEO, LISTED_HANDLE);
    vi.advanceTimersByTime(1_000);

    expect(isInterrupted()).toBe(false);
    expect(video.play).toHaveBeenCalled();
    expect(video.el.paused).toBe(false);
    expect(leftFor).toEqual([]);
  });

  it('still interrupts a video by a channel that is NOT listed, once its owner is shown', async () => {
    await startOn('/@veritasium/videos', channelPageHtml(LISTED_HANDLE), blockedSiteConfig());

    spaHop(`/watch?v=${OTHER_VIDEO}`);
    const flexy = addEmptyWatchPlayer();
    const video = addPlayingVideo(flexy);
    await settleConfig();
    expect(isInterrupted()).toBe(false);

    vi.advanceTimersByTime(1_600);
    renderOwner(flexy, OTHER_VIDEO, 'Inner_PeaceVibe');
    vi.advanceTimersByTime(1_000);

    expect(isInterrupted()).toBe(true);
    expect(video.play).not.toHaveBeenCalled();
  });

  it('falls back to the site rule only once the settle window has passed with no owner', async () => {
    await startOn('/@veritasium/videos', channelPageHtml(LISTED_HANDLE), blockedSiteConfig());

    spaHop(`/watch?v=${VIDEO}`);
    addEmptyWatchPlayer();
    await settleConfig();

    vi.advanceTimersByTime(5_000);
    expect(isInterrupted()).toBe(false);

    // YouTube never rendered an owner: now it really is unknowable, and a blocked site
    // fails closed, as documented.
    vi.advanceTimersByTime(2_000);
    expect(isInterrupted()).toBe(true);
  });
});

describe('a completed Delay pause gives the video back (E1)', () => {
  it('plays the held video when the countdown ends', async () => {
    const config = blockedSiteConfig({ siteMode: 'DELAY', siteDelaySeconds: 3 });
    await startOn('/@veritasium/videos', channelPageHtml(LISTED_HANDLE), config);

    spaHop(`/watch?v=${OTHER_VIDEO}`);
    const flexy = addEmptyWatchPlayer();
    const video = addPlayingVideo(flexy);
    await settleConfig();
    vi.advanceTimersByTime(1_600);
    renderOwner(flexy, OTHER_VIDEO, 'Inner_PeaceVibe');
    vi.advanceTimersByTime(500);
    expect(isInterrupted()).toBe(true);
    expect(video.play).not.toHaveBeenCalled();

    vi.advanceTimersByTime(3_000);

    expect(isInterrupted()).toBe(false);
    expect(video.play).toHaveBeenCalledTimes(1);
  });
});

describe('the media hold never resumes behind a user who left', () => {
  it('does not start the gated video when the user bails', async () => {
    await startOn('/@veritasium/videos', channelPageHtml(LISTED_HANDLE), blockedSiteConfig());
    spaHop(`/watch?v=${OTHER_VIDEO}`);
    const flexy = addEmptyWatchPlayer();
    const video = addPlayingVideo(flexy);
    await settleConfig();
    vi.advanceTimersByTime(1_600);
    renderOwner(flexy, OTHER_VIDEO, 'Inner_PeaceVibe');
    vi.advanceTimersByTime(500);
    expect(isInterrupted()).toBe(true);

    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {});
    (document.getElementById(NUDGE_OVERLAY_ID)?.querySelector('button') as HTMLButtonElement).click();

    expect(back).toHaveBeenCalled();
    expect(video.play).not.toHaveBeenCalled();
    back.mockRestore();
  });
});

/* ------------------------------------------------------------------ the page gate */

describe('in-SPA navigation is held to what a full load would allow (F4, F6, A13)', () => {
  async function fromAllowedVideo(config: SiteConfig = blockedSiteConfig()): Promise<FakeVideo> {
    await startOn('/@veritasium/videos', channelPageHtml(LISTED_HANDLE), config);
    spaHop(`/watch?v=${VIDEO}`);
    const flexy = addEmptyWatchPlayer();
    const video = addPlayingVideo(flexy);
    renderOwner(flexy, VIDEO, LISTED_HANDLE);
    await settleConfig();
    vi.advanceTimersByTime(3_000);
    expect(leftFor).toEqual([]);
    return video;
  }

  it.each([
    ['the home feed', '/'],
    ['search', '/results?search_query=cats'],
    ['subscriptions', '/feed/subscriptions'],
    ['another channel', '/@MrBeast'],
    ['Shorts', '/shorts/abc123'],
  ])('hands %s to the network layer, which shows the block page', async (_name, path) => {
    const video = await fromAllowedVideo();

    spaHop(path);
    await settleConfig();

    expect(leftFor).toEqual([`${ORIGIN}${path}`]);
    // Nothing plays in the moment before the page is replaced.
    expect(video.el.paused).toBe(true);
  });

  it.each([
    ['the listed channel\'s own pages', '/@veritasium/shorts'],
    ['the listed channel, typed in another case', '/@Veritasium'],
    ['a playlist', '/playlist?list=PL123'],
    ['another video (gated per video instead)', `/watch?v=${OTHER_VIDEO}`],
  ])('leaves %s alone', async (_name, path) => {
    await fromAllowedVideo();

    spaHop(path);
    await settleConfig();
    vi.advanceTimersByTime(1_000);

    expect(leftFor).toEqual([]);
  });

  it('hands off once, however many passes run before the page is replaced', async () => {
    await fromAllowedVideo();

    spaHop('/');
    await settleConfig();
    vi.advanceTimersByTime(5_000);

    expect(leftFor).toHaveLength(1);
  });

  it('waits for the rule\'s state FOR THIS PAGE before acting on it', async () => {
    let resolveNext: ((config: SiteConfig) => void) | null = null;
    let deferring = false;
    const fetchConfig = (): Promise<SiteConfig> => {
      if (!deferring) return Promise.resolve(blockedSiteConfig());
      return new Promise((resolve) => {
        resolveNext = resolve;
      });
    };
    await startOn('/@veritasium/videos', channelPageHtml(LISTED_HANDLE), fetchConfig);

    deferring = true;
    spaHop('/');
    await settleConfig();
    // The config in hand was fetched for the channel page; a pause may have expired since.
    expect(leftFor).toEqual([]);

    // The worker answers for the home feed: a pause is live, so the page stays.
    resolveNext!(blockedSiteConfig({ tempAllowedUntil: Date.now() + 60_000 }));
    await settleConfig();
    expect(leftFor).toEqual([]);
  });

  it('does nothing while the site is open (an Allow rule under its budget)', async () => {
    await fromAllowedVideo(blockedSiteConfig({ siteMode: 'ALLOW', siteApplies: false }));

    spaHop('/');
    await settleConfig();

    expect(leftFor).toEqual([]);
  });

  it('does nothing during a completed pause', async () => {
    await fromAllowedVideo(blockedSiteConfig({ tempAllowedUntil: Date.now() + 10 * 60_000 }));

    spaHop('/results?search_query=cats');
    await settleConfig();

    expect(leftFor).toEqual([]);
  });

  it('closes the whitelist once the daily limit is spent: even a listed channel is refused', async () => {
    // A limit budgets how MUCH YouTube; once it is spent there is nothing left to carve the
    // listed channels out of, which is what the network layer does too (no allow rules).
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await startOn(
      '/@veritasium/videos',
      channelPageHtml(LISTED_HANDLE),
      blockedSiteConfig({ siteMode: 'ALLOW', siteLimitReached: true }),
    );

    spaHop(`/watch?v=${VIDEO}`);
    await settleConfig();

    expect(leftFor).toEqual([`${ORIGIN}/watch?v=${VIDEO}`]);
    warn.mockRestore();
  });

  it('never reloads a FULL page load the network layer let through (that could loop)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await startOn('/', '<ytd-app></ytd-app>', blockedSiteConfig());
    vi.advanceTimersByTime(3_000);

    expect(leftFor).toEqual([]);
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
});

/* ------------------------------------------------------------------ creator mode */

describe('creator mode keeps the creator routes open', () => {
  const creator = (overrides: Partial<SiteConfig> = {}): SiteConfig =>
    blockedSiteConfig(overrides, {
      channelMode: 'OFF',
      channels: [],
      creatorMode: true,
      myChannel: { channelId: LISTED_ID, handle: 'mychannel', displayName: 'Me', addedAt: 0 },
    });

  it('opens Upload in-SPA while YouTube is blocked', async () => {
    await startOn('/@mychannel', channelPageHtml('mychannel'), creator());

    spaHop('/upload');
    await settleConfig();

    expect(leftFor).toEqual([]);
  });

  it('keeps it open after the daily limit is spent', async () => {
    await startOn('/@mychannel', channelPageHtml('mychannel'), creator({ siteMode: 'ALLOW', siteLimitReached: true }));

    spaHop('/upload');
    await settleConfig();

    expect(leftFor).toEqual([]);
  });

  it('lets my own channel through, and nothing else', async () => {
    await startOn('/@mychannel', channelPageHtml('mychannel'), creator());

    spaHop('/@mychannel/videos');
    await settleConfig();
    expect(leftFor).toEqual([]);

    spaHop('/');
    await settleConfig();
    expect(leftFor).toEqual([`${ORIGIN}/`]);
  });

  it('is off when the box is not ticked', async () => {
    await startOn('/@veritasium/videos', channelPageHtml(LISTED_HANDLE), blockedSiteConfig());

    spaHop('/upload');
    await settleConfig();

    expect(leftFor).toEqual([`${ORIGIN}/upload`]);
  });
});

/* ------------------------------------------------------------------ legacy channel URLs */

describe('legacy /c/ and /user/ channel URLs are checked against the page itself', () => {
  function legacyPageHtml(channelId: string, handle: string): string {
    const data = {
      metadata: {
        channelMetadataRenderer: {
          externalId: channelId,
          vanityChannelUrl: `http://www.youtube.com/@${handle}`,
          title: handle,
        },
      },
    };
    return `<script>var ytInitialData = ${JSON.stringify(data)};</script>${channelPageHtml(handle)}`;
  }

  it('stays on a listed channel\'s legacy URL', async () => {
    await startOn('/user/veritasium', legacyPageHtml(LISTED_ID, LISTED_HANDLE), blockedSiteConfig());
    vi.advanceTimersByTime(2_000);

    expect(leftFor).toEqual([]);
  });

  it('sends an unlisted channel\'s legacy URL to the block page', async () => {
    await startOn('/c/MrBeast', legacyPageHtml('UCX6OQ3DkcsbYNE6H8uQQuVA', 'MrBeast'), blockedSiteConfig());
    vi.advanceTimersByTime(2_000);

    expect(leftFor).toEqual([`chrome-extension://nudge/blocked.html?target=${ORIGIN}/c/MrBeast`]);
  });

  it('refuses a legacy URL whose page names no channel at all', async () => {
    await startOn('/c/whatever', channelPageHtml('whatever'), blockedSiteConfig());
    vi.advanceTimersByTime(2_000);

    expect(leftFor).toEqual([`chrome-extension://nudge/blocked.html?target=${ORIGIN}/c/whatever`]);
  });

  it('reloads an in-SPA arrival, because the inline data still describes the previous page', async () => {
    await startOn('/@veritasium/videos', legacyPageHtml(LISTED_ID, LISTED_HANDLE), blockedSiteConfig());

    spaHop('/c/MrBeast');
    await settleConfig();

    expect(leftFor).toEqual([`${ORIGIN}/c/MrBeast`]);
  });
});

/* ------------------------------------------------------------------ live after-matrix */

/**
 * The channel page's own inline data, in the shape a live probe recorded (2026-10-23,
 * `/@veritasium/videos`): `ytInitialData` has NO `currentVideoEndpoint` (only a watch page
 * has one), and the first `watchEndpoint` a breadth-first walk meets is the first video in
 * the grid. That is the video a user clicks first, so an SPA hop to it lands on a page whose
 * stale inline data names the video in the address bar.
 */
function channelPageWithInlineData(handle: string, firstGridVideo: string): string {
  const data = {
    responseContext: {},
    contents: {
      twoColumnBrowseResultsRenderer: {
        tabs: [
          {
            tabRenderer: {
              content: {
                richGridRenderer: {
                  contents: [
                    {
                      richItemRenderer: {
                        content: {
                          videoRenderer: {
                            videoId: firstGridVideo,
                            navigationEndpoint: { watchEndpoint: { videoId: firstGridVideo } },
                          },
                        },
                      },
                    },
                  ],
                },
              },
            },
          },
        ],
      },
    },
    header: {},
    metadata: {
      channelMetadataRenderer: {
        externalId: LISTED_ID,
        vanityChannelUrl: `http://www.youtube.com/@${handle}`,
      },
    },
  };
  return `<script>var ytInitialData = ${JSON.stringify(data)};</script>${channelPageHtml(handle)}`;
}

describe('a channel page\'s inline data never vouches for the video after an SPA hop (live B1, G1)', () => {
  it('B1: clicking the first video in the grid is not accused while its owner renders', async () => {
    await startOn('/@veritasium/videos', channelPageWithInlineData(LISTED_HANDLE, VIDEO), blockedSiteConfig());

    spaHop(`/watch?v=${VIDEO}`);
    const flexy = addEmptyWatchPlayer();
    const video = addPlayingVideo(flexy);
    await settleConfig();

    // The live after-matrix showed "couldn't identify this video's channel" here for ~1s:
    // the grid's watchEndpoint named this video, so the stale channel-page data "proved"
    // the empty detection fresh.
    expect(isInterrupted()).toBe(false);

    vi.advanceTimersByTime(1_600);
    renderOwner(flexy, VIDEO, LISTED_HANDLE);
    vi.advanceTimersByTime(1_000);

    expect(isInterrupted()).toBe(false);
    expect(video.play).toHaveBeenCalled();
  });

  it('G1: Back from a blocked video to an allowed one plays it', async () => {
    await startOn('/@veritasium/videos', channelPageWithInlineData(LISTED_HANDLE, VIDEO), blockedSiteConfig());

    spaHop(`/watch?v=${OTHER_VIDEO}`);
    const flexy = addEmptyWatchPlayer();
    const video = addPlayingVideo(flexy);
    await settleConfig();
    vi.advanceTimersByTime(1_600);
    renderOwner(flexy, OTHER_VIDEO, 'HardwareGridyt');
    vi.advanceTimersByTime(1_000);
    expect(isInterrupted()).toBe(true);
    expect(video.play).not.toHaveBeenCalled();

    // Back. YouTube reuses the same player, which still shows the blocked video for a beat.
    spaHop(`/watch?v=${VIDEO}`);
    await settleConfig();
    vi.advanceTimersByTime(1_600);
    renderOwner(flexy, VIDEO, LISTED_HANDLE);
    vi.advanceTimersByTime(1_000);

    expect(isInterrupted()).toBe(false);
    expect(video.play).toHaveBeenCalled();
    expect(video.el.paused).toBe(false);
  });
});

describe('an interstitial belongs to the one page it was raised for', () => {
  it('a hop to another blocked video starts that video\'s own pause, and the old one cannot release it', async () => {
    const config = blockedSiteConfig({ siteMode: 'DELAY', siteDelaySeconds: 3 });
    await startOn('/@veritasium/videos', channelPageHtml(LISTED_HANDLE), config);

    spaHop(`/watch?v=${OTHER_VIDEO}`);
    const flexy = addEmptyWatchPlayer();
    const video = addPlayingVideo(flexy);
    await settleConfig();
    vi.advanceTimersByTime(1_600);
    renderOwner(flexy, OTHER_VIDEO, 'HardwareGridyt');
    vi.advanceTimersByTime(500);
    expect(isInterrupted()).toBe(true);

    // Two seconds into its three-second pause, the page moves to a different blocked video.
    // YouTube has already drawn it by the time the URL tick lands, so the very first pass
    // on the new page is decided, not settling.
    vi.advanceTimersByTime(2_000);
    const thirdVideo = 'dQw4w9WgXcQ';
    renderOwner(flexy, thirdVideo, 'SomeoneElse');
    spaHop(`/watch?v=${thirdVideo}`);
    await settleConfig();

    // The first video's countdown would have finished here. It must not hand this one back.
    vi.advanceTimersByTime(1_500);
    expect(isInterrupted()).toBe(true);
    expect(video.play).not.toHaveBeenCalled();

    // This video's own pause, completed, is what releases it.
    vi.advanceTimersByTime(2_000);
    expect(isInterrupted()).toBe(false);
    expect(video.play).toHaveBeenCalledTimes(1);
  });
});
