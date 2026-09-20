/**
 * @vitest-environment jsdom
 * @vitest-environment-options { "url": "https://www.youtube.com/watch?v=oldvideo000001" }
 */
/**
 * The YouTube controller's SETTLE WINDOW, driven through `initYoutubeContentScript` itself.
 *
 * Why this file exists: `youtube.ts` now builds its interstitial with the shared
 * `content/overlay.ts` builder and detects navigation with the shared `content/spaNav.ts`
 * layer, instead of private copies of both. That migration was deferred once precisely
 * because the CALLER is entangled with the channel-freshness settle window, and until now
 * the ONLY automated guard on that window was `e2e/youtubeAdvanced.spec.ts` — a real
 * Chrome, minutes per run, and nothing at all in the unit suite.
 *
 * Everything here is phrased as what the user would see (repo rule):
 *
 *  - "interrupted" = an element with `NUDGE_OVERLAY_ID` is attached to the page
 *  - "in colour"   = `<html>` carries `COLOR_CLASS` (grayscale.css keys the filter off it)
 *
 * THE P0 THIS PINS (extension/CLAUDE.md, live QA 2026-07-26): YouTube fires
 * `yt-navigate-finish` BEFORE it re-renders the owner byline, so for 1.5-5s after a
 * watch -> watch hop the DOM still names the PREVIOUS video's channel. Acting on that
 * accuses a channel the user explicitly allowed of being "off your list". While the byline
 * still names the channel from before the hop, the verdict is WITHHELD and the page stays
 * gray — both fail-safe directions.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initYoutubeContentScript, type YoutubeController } from '../../src/content/youtube';
import { COLOR_CLASS, NUDGE_OVERLAY_ID } from '../../src/content/selectors';
import { SETTLE_COLOR_MS, SETTLE_MS, SETTLE_RECHECK_MS } from '../../src/core/channelFreshness';
import type { SiteConfig } from '../../src/core/protocol';

const OLD_VIDEO = 'oldvideo000001';
const NEW_VIDEO = 'newvideo000002';
/** Not on the whitelist. */
const BLOCKED_CHANNEL = 'UCblockedchannel00000001';
/** On the whitelist. */
const ALLOWED_CHANNEL = 'UCallowedchannel00000002';
/** Also not on the whitelist — the "hopped to a different blocked channel" case. */
const OTHER_BLOCKED_CHANNEL = 'UCotherblocked0000000003';

/**
 * A watch page shaped like `tests/content/fixtures/watchPage.ts`'s: inline
 * `ytInitialPlayerResponse` (which YouTube does NOT rewrite on a client-side hop) plus the
 * owner byline (which it does, eventually). Built here rather than imported because these
 * tests need to move the two independently — that divergence IS the bug.
 */
function watchPageHtml(inline: { videoId: string; channelId: string }, bylineChannelId: string): string {
  const payload = {
    videoDetails: {
      videoId: inline.videoId,
      title: 'A video',
      channelId: inline.channelId,
      author: 'Some Channel',
    },
    playabilityStatus: { status: 'OK' },
  };
  return `
    <div id="page-manager">
      <script>var ytInitialPlayerResponse = ${JSON.stringify(payload)};</script>
      <ytd-watch-flexy>
        <div id="primary"><div id="player"><video id="movie_player"></video></div></div>
        <div id="secondary">
          <ytd-channel-name id="channel-name">
            <a class="yt-formatted-string" href="/channel/${bylineChannelId}">Channel</a>
          </ytd-channel-name>
        </div>
      </ytd-watch-flexy>
    </div>
  `;
}

/** Re-render just the owner byline, the way YouTube does a beat after the navigation. */
function rerenderByline(channelId: string): void {
  const anchor = document.querySelector('#channel-name a');
  if (anchor === null) throw new Error('fixture has no byline anchor');
  anchor.setAttribute('href', `/channel/${channelId}`);
}

/**
 * The shape the worker resolves for "youtube.com is Allowed, but only these channels play".
 * `siteApplies: false` is deliberate: the site itself is open, and it is the CHANNEL rule
 * doing the blocking — the configuration the P0 was reported against.
 */
function whitelistConfig(overrides: { grayscale?: boolean } = {}): SiteConfig {
  return {
    enabled: true,
    domain: 'youtube.com',
    platform: 'youtube',
    siteMode: 'ALLOW',
    siteDelaySeconds: 15,
    siteApplies: false,
    siteLimitReached: false,
    grayscale: overrides.grayscale === true,
    gates: [],
    hides: {},
    youtube: {
      channelMode: 'WHITELIST',
      channels: [
        { channelId: ALLOWED_CHANNEL, handle: null, displayName: 'Allowed Channel', addedAt: 0 },
      ],
      channelBlockMode: 'HARD_BLOCK',
      channelDelaySeconds: 15,
      disableAutoplay: false,
    },
  };
}

function overlayEl(): Element | null {
  return document.getElementById(NUDGE_OVERLAY_ID);
}

function isInterrupted(): boolean {
  return overlayEl() !== null;
}

function isInColor(): boolean {
  return document.documentElement.classList.contains(COLOR_CLASS);
}

/**
 * Hop the SPA to a new video the way YouTube does: push the URL, fire the site's own
 * navigation event, and leave BOTH the inline JSON and the byline exactly as they were.
 * That combination is what makes the detected channel stale.
 */
function spaHop(toPath: string): void {
  window.history.pushState({}, '', toPath);
  document.dispatchEvent(new CustomEvent('yt-navigate-finish'));
}

let controller: YoutubeController | null = null;

/**
 * Start the controller on a page already fully loaded at `OLD_VIDEO`, with the inline data
 * AGREEING with the URL (so the first verdict is CONFIRMED immediately, exactly like a real
 * cold load) and the byline naming `startChannel`.
 */
async function startOn(
  startChannel: string,
  config: SiteConfig,
): Promise<void> {
  window.history.replaceState({}, '', `/watch?v=${OLD_VIDEO}`);
  document.documentElement.className = '';
  document.body.innerHTML = watchPageHtml(
    { videoId: OLD_VIDEO, channelId: startChannel },
    startChannel,
  );
  controller = initYoutubeContentScript(document, () => Promise.resolve(config));
  // `initYoutubeContentScript` kicks off its own `reload()`; awaiting one explicitly makes
  // the first verdict deterministic instead of racing a floating promise.
  await controller.reload();
  // Land on a whole multiple of the poll interval so the later assertions can tell a
  // settle-ladder re-check apart from an idle-poll one.
  vi.advanceTimersByTime(3 * 1000);
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  controller?.stop();
  controller = null;
  vi.useRealTimers();
  document.body.innerHTML = '';
  document.documentElement.className = '';
});

describe('the reverse hop (blocked -> allowed): the P0 this machinery exists for', () => {
  it('never accuses the allowed channel, from the moment of the hop to the moment it is confirmed', async () => {
    await startOn(BLOCKED_CHANNEL, whitelistConfig({ grayscale: true }));

    // Baseline: the blocked channel IS interrupted, and is not in colour.
    expect(isInterrupted()).toBe(true);
    expect(isInColor()).toBe(false);

    // The hop. The byline still names the BLOCKED channel — that is the lag.
    spaHop(`/watch?v=${NEW_VIDEO}`);

    // Withheld, not applied: the interstitial comes DOWN rather than being re-asserted
    // against a page we cannot yet read, and the page stays gray.
    expect(isInterrupted()).toBe(false);
    expect(isInColor()).toBe(false);

    // Every scheduled re-check inside the settle window must reach the same answer. The
    // ladder entries are times SINCE the navigation, so they are walked absolutely.
    let sinceNav = 0;
    for (const delay of SETTLE_RECHECK_MS) {
      if (delay >= SETTLE_MS) break;
      vi.advanceTimersByTime(delay - sinceNav);
      sinceNav = delay;
      expect(isInterrupted()).toBe(false);
    }

    // YouTube finally re-renders the byline, naming the channel the user allowed. In this
    // fixture that is an attribute change, which the observer does not watch (it observes
    // childList/subtree), so it is the next SCHEDULED re-check that notices -- exactly the
    // guarantee the fixed ladder plus the idle poll exist to provide.
    rerenderByline(ALLOWED_CHANNEL);
    vi.advanceTimersByTime(1_500);

    // ZERO interruptions across the whole hop, and colour is granted now that the identity
    // is confirmed.
    expect(isInterrupted()).toBe(false);
    expect(isInColor()).toBe(true);
  });
});

describe('the forward hop (allowed -> blocked)', () => {
  it('interrupts the new video as soon as the byline proves it is the new video', async () => {
    await startOn(ALLOWED_CHANNEL, whitelistConfig({ grayscale: true }));

    expect(isInterrupted()).toBe(false);
    expect(isInColor()).toBe(true);

    spaHop(`/watch?v=${NEW_VIDEO}`);

    // Still reading the previous video's byline: no verdict either way, and colour is
    // withdrawn because an unconfirmed identity never earns it.
    expect(isInterrupted()).toBe(false);
    expect(isInColor()).toBe(false);

    rerenderByline(BLOCKED_CHANNEL);
    vi.advanceTimersByTime(SETTLE_RECHECK_MS[0] ?? 400);

    expect(isInterrupted()).toBe(true);
    expect(isInColor()).toBe(false);
  });

  it('shows exactly ONE interstitial, not one per re-check in the settle ladder', async () => {
    await startOn(ALLOWED_CHANNEL, whitelistConfig());

    spaHop(`/watch?v=${NEW_VIDEO}`);
    rerenderByline(BLOCKED_CHANNEL);

    vi.advanceTimersByTime(SETTLE_MS + 2_000);

    expect(document.querySelectorAll(`#${NUDGE_OVERLAY_ID}`)).toHaveLength(1);
  });
});

describe('the same-channel hop: only the backstop can end the wait', () => {
  /**
   * When both videos are by the same channel the byline never changes, so nothing can ever
   * CONFIRM freshness and only `SETTLE_MS` ends it. This is the case the fixed re-check
   * ladder exists for: YouTube's mutation storm can starve a debounced pass indefinitely,
   * so the corrective pass must run on a timer page activity cannot reset.
   */
  it('withholds the verdict for the full backstop, then applies it with no DOM change at all', async () => {
    await startOn(BLOCKED_CHANNEL, whitelistConfig());
    expect(isInterrupted()).toBe(true);

    spaHop(`/watch?v=${NEW_VIDEO}`);
    expect(isInterrupted()).toBe(false);

    // Nothing touches the DOM from here on: no mutations, so no observer callback. The only
    // thing that can re-check the page is the settle ladder's own timer.
    vi.advanceTimersByTime(SETTLE_RECHECK_MS[SETTLE_RECHECK_MS.length - 1]! - 1);
    expect(isInterrupted()).toBe(false);

    vi.advanceTimersByTime(1);
    expect(isInterrupted()).toBe(true);
  });

  it('holds colour for the SHORTER backstop, because staying gray is the harmless direction', async () => {
    await startOn(ALLOWED_CHANNEL, whitelistConfig({ grayscale: true }));
    expect(isInColor()).toBe(true);

    spaHop(`/watch?v=${NEW_VIDEO}`);
    expect(isInColor()).toBe(false);

    // The colour backstop is deliberately shorter than the verdict's, so an allowed ->
    // allowed hop does not sit in grayscale for six seconds. Colour comes back at the first
    // scheduled re-check PAST that backstop, since the ladder is what re-evaluates the page.
    expect(SETTLE_COLOR_MS).toBeLessThan(SETTLE_MS);
    const firstCheckPastColorBackstop = SETTLE_RECHECK_MS.find((ms) => ms >= SETTLE_COLOR_MS);
    expect(firstCheckPastColorBackstop).toBeDefined();
    expect(firstCheckPastColorBackstop!).toBeLessThan(SETTLE_MS);
    vi.advanceTimersByTime(firstCheckPastColorBackstop! + 1);
    expect(isInColor()).toBe(true);
    // ...and it did NOT interrupt on the way there.
    expect(isInterrupted()).toBe(false);
  });
});

describe('a navigation is handled immediately, never debounced', () => {
  it('takes effect on the same tick as yt-navigate-finish, with no timer advanced', async () => {
    await startOn(BLOCKED_CHANNEL, whitelistConfig());
    expect(isInterrupted()).toBe(true);

    // The byline is re-rendered BEFORE the event, the way a fast re-render would land: the
    // new channel differs from the previous one, so it is CONFIRMED at once.
    rerenderByline(OTHER_BLOCKED_CHANNEL);
    window.history.pushState({}, '', `/watch?v=${NEW_VIDEO}`);
    document.dispatchEvent(new CustomEvent('yt-navigate-finish'));

    // Not one millisecond advanced. If the navigation were routed through the 250ms
    // debounce, this page would still be holding the PREVIOUS video's verdict.
    expect(isInterrupted()).toBe(true);
  });

  it('is noticed by the href-diff poll even when no event fires at all', async () => {
    // An isolated-world content script cannot observe the page's own `pushState`, so the
    // poll is the only thing that catches a hop YouTube does not announce.
    await startOn(ALLOWED_CHANNEL, whitelistConfig());
    expect(isInterrupted()).toBe(false);

    window.history.pushState({}, '', `/watch?v=${NEW_VIDEO}`);
    rerenderByline(BLOCKED_CHANNEL);

    vi.advanceTimersByTime(1_000 + 400);
    expect(isInterrupted()).toBe(true);
  });
});

describe('"I changed my mind" on the channel interstitial', () => {
  it('goes BACK to where the user came from, not to a site root the rule still redirects', async () => {
    await startOn(BLOCKED_CHANNEL, whitelistConfig());
    expect(isInterrupted()).toBe(true);

    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {});

    const bail = overlayEl()?.querySelector('button');
    expect(bail?.textContent).toBe('I changed my mind');
    (bail as HTMLButtonElement).click();

    expect(back).toHaveBeenCalledTimes(1);
    // The interstitial comes down with it — no lid left over a page we have navigated off.
    expect(isInterrupted()).toBe(false);

    back.mockRestore();
  });
});

describe('the controller stops cleanly', () => {
  it('leaves no interstitial, and no timer that can put one back', async () => {
    await startOn(BLOCKED_CHANNEL, whitelistConfig());
    expect(isInterrupted()).toBe(true);

    spaHop(`/watch?v=${NEW_VIDEO}`);
    controller?.stop();
    controller = null;

    vi.advanceTimersByTime(SETTLE_MS + 5_000);
    expect(isInterrupted()).toBe(false);
  });
});
