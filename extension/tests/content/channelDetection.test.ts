// @vitest-environment jsdom
/**
 * Tests for YouTube channel detection (ext-03 §3).
 *
 * The contract these tests defend: Nudge's channel allow/block list has to know WHO
 * uploaded the video the user is looking at, on a page whose channel data YouTube ships in
 * three different shapes and reshuffles on its own schedule. A test suite that encodes
 * "which code path ran" instead of "what channel did the user actually land on" is exactly
 * how a real bug (an unscoped feed-card query, or a JSON-parse regression) survives green
 * CI — so every case here is phrased as an observable result: the channel id/handle/name
 * that comes back, or the fact that nothing throws.
 *
 * The repo's default vitest environment is `node` (src/core is pure); this file opts into
 * jsdom with the directive above.
 */

import { describe, expect, it } from 'vitest';
import {
  channelPageHtml,
  LIVE_OWNER_CHANNEL_ID,
  LIVE_OWNER_HANDLE,
  LIVE_OWNER_NAME,
  liveWatchPage,
  RELATED_HANDLE,
  STALE_BROWSE_HANDLE,
} from './fixtures/youtubeLiveWatch';
import {
  channelFromDom,
  channelPageIdentity,
  watchPlayerVideoId,
  channelFromInitialData,
  channelFromPlayerResponse,
  detectCardChannel,
  detectWatchChannel,
  feedCards,
  initialDataVideoId,
  videoIdFromUrl,
} from '../../src/content/channelDetection';
import {
  ARIA_LABEL_VS_TEXT_HTML,
  FRESH_DOM_CHANNEL_ID,
  STALE_INLINE_CHANNEL_ID,
  STALE_URL,
  TRICKY_CHANNEL_NAME,
  WATCH_DOM_ONLY_HTML,
  WATCH_INITIAL_DATA_ONLY_HTML,
  WATCH_INLINE_MATCHES_URL_HTML,
  WATCH_MALFORMED_JSON_HTML,
  WATCH_NOTHING_HTML,
  WATCH_PLAYER_RESPONSE_HTML,
  WATCH_RENAMED_WRAPPERS_HTML,
  WATCH_SPA_STALE_INLINE_HTML,
  WATCH_TRICKY_JSON_HTML,
  WATCH_FIXTURE_VIDEO_ID,
  WATCH_ID_INLINE_HANDLE_DOM_HTML,
  CARD_BOTH_AXES_HTML,
  REAL_WATCH_ID_INLINE_HANDLE_DOM_CHANNEL_ID,
  REAL_WATCH_ID_INLINE_HANDLE_DOM_HANDLE,
} from './fixtures/watchPage';
import {
  EMPTY_FEED_HTML,
  FEED_MULTI_CHANNEL_HTML,
  SEARCH_HREFLESS_PLACEHOLDER_HTML,
} from './fixtures/feedPage';

/** Mount a fixture into document.body so document-scoped lookups (script tags) can see it. */
function mount(html: string): HTMLElement {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.replaceChildren(root);
  return root;
}

describe('identifying the channel on a watch page', () => {
  it('identifies the channel on a normal watch page shipping ytInitialPlayerResponse', () => {
    mount(WATCH_PLAYER_RESPONSE_HTML);

    const result = detectWatchChannel(document);

    // This fixture has THREE different candidate channels (player response, initial data,
    // DOM) precisely so a passing test proves the player response was the one picked, not
    // merely that "some channel" was found.
    expect(result).toEqual({
      channelId: 'UCplayerresponse00000001',
      handle: null,
      displayName: 'Player Response Channel',
    });
  });

  it('still identifies the channel when ytInitialPlayerResponse is absent', () => {
    mount(WATCH_INITIAL_DATA_ONLY_HTML);

    const result = detectWatchChannel(document);

    // Again a decoy is present (a different channel in the DOM) so a pass proves
    // ytInitialData was preferred over the DOM fallback, not just "found A channel".
    expect(result).toEqual({
      channelId: 'UCinitialdataonly0000004',
      handle: '@initialdataonlychannel',
      displayName: 'Initial Data Only Channel',
    });
  });

  it('falls back to the page itself when neither inline script is present', () => {
    mount(WATCH_DOM_ONLY_HTML);

    const result = detectWatchChannel(document);

    expect(result).toEqual({
      channelId: 'UCdomonlystandard0000006',
      handle: null,
      displayName: 'Standard Dom Channel',
    });
  });

  it('still finds the channel after YouTube renames every specific wrapper element', () => {
    mount(WATCH_RENAMED_WRAPPERS_HTML);

    const result = detectWatchChannel(document);

    expect(result).toEqual({
      channelId: null,
      handle: '@renamedhandlechannel',
      displayName: 'Renamed Handle Channel',
    });
  });

  it('identifies the channel even when the raw JSON has braces and escaped quotes inside a string', () => {
    mount(WATCH_TRICKY_JSON_HTML);

    const result = detectWatchChannel(document);

    expect(result).toEqual({
      channelId: 'UCtrickytrickytricky00007',
      handle: '@trickychannel',
      displayName: TRICKY_CHANNEL_NAME,
    });
  });

  it('reports no channel, and never throws, on a page with nothing to find', () => {
    mount(WATCH_NOTHING_HTML);

    expect(() => detectWatchChannel(document)).not.toThrow();
    expect(detectWatchChannel(document)).toBeNull();
  });

  it('reports no channel, and never throws, when the inline JSON is truncated mid-object', () => {
    mount(WATCH_MALFORMED_JSON_HTML);

    expect(() => detectWatchChannel(document)).not.toThrow();
    expect(detectWatchChannel(document)).toBeNull();
  });
});

describe('what each tier extracts on its own', () => {
  it('channelFromPlayerResponse reads the channel id and uploader name straight off videoDetails', () => {
    mount(WATCH_PLAYER_RESPONSE_HTML);

    expect(channelFromPlayerResponse(document)).toEqual({
      channelId: 'UCplayerresponse00000001',
      handle: null,
      displayName: 'Player Response Channel',
    });
  });

  it('channelFromPlayerResponse finds nothing (without throwing) when the script is absent', () => {
    mount(WATCH_INITIAL_DATA_ONLY_HTML);

    expect(() => channelFromPlayerResponse(document)).not.toThrow();
    expect(channelFromPlayerResponse(document)).toBeNull();
  });

  it('channelFromInitialData reads the channel id, handle and name off the render tree', () => {
    mount(WATCH_INITIAL_DATA_ONLY_HTML);

    expect(channelFromInitialData(document)).toEqual({
      channelId: 'UCinitialdataonly0000004',
      handle: '@initialdataonlychannel',
      displayName: 'Initial Data Only Channel',
    });
  });

  it('channelFromInitialData survives a truncated payload by returning null, not throwing', () => {
    mount(WATCH_MALFORMED_JSON_HTML);

    expect(() => channelFromInitialData(document)).not.toThrow();
    expect(channelFromInitialData(document)).toBeNull();
  });

  it('channelFromDom reads the channel straight off the page when no script data exists', () => {
    mount(WATCH_DOM_ONLY_HTML);

    expect(channelFromDom(document)).toEqual({
      channelId: 'UCdomonlystandard0000006',
      handle: null,
      displayName: 'Standard Dom Channel',
    });
  });

  it('channelFromDom prefers the aria-label over disagreeing text content', () => {
    mount(ARIA_LABEL_VS_TEXT_HTML);

    const result = channelFromDom(document);

    expect(result?.displayName).toBe('Aria Label Wins');
    expect(result?.displayName).not.toContain('Different Text Content');
  });
});

describe("resolving each feed card's own channel", () => {
  it('resolves each card to its own channel rather than the first one on the page', () => {
    const root = mount(FEED_MULTI_CHANNEL_HTML);

    const cards = feedCards(root);
    expect(cards).toHaveLength(4);

    const results = cards.map((card) => detectCardChannel(card));

    expect(results[0]).toEqual({
      channelId: 'UCALPHACHANNEL000000001',
      handle: null,
      displayName: 'Alpha Channel',
    });
    expect(results[1]).toEqual({
      channelId: null,
      handle: '@bravochannel',
      displayName: 'Bravo Channel',
    });
    expect(results[2]).toEqual({
      channelId: null,
      handle: 'charliechannel',
      displayName: 'Charlie Channel',
    });

    // The identifying set is what actually catches an unscoped query: if `detectCardChannel`
    // ever queried the whole document instead of the card it was given, every card would
    // collapse onto the SAME channel (the page-level decoy, which sorts first in document
    // order) and this set would have size 1, not 3.
    const identifiers = new Set(
      results.slice(0, 3).map((r) => r?.channelId ?? r?.handle),
    );
    expect(identifiers.size).toBe(3);
    expect(identifiers.has('UCPAGELEVELDECOY000001')).toBe(false);
  });

  it('returns null, without throwing, for a card with no channel link of its own', () => {
    const root = mount(FEED_MULTI_CHANNEL_HTML);
    const cards = feedCards(root);
    const noChannelCard = cards[3];
    expect(noChannelCard).toBeDefined();

    expect(() => detectCardChannel(noChannelCard as Element)).not.toThrow();
    expect(detectCardChannel(noChannelCard as Element)).toBeNull();
  });

  it('finds no cards, without throwing, on a feed with none', () => {
    const root = mount(EMPTY_FEED_HTML);

    expect(() => feedCards(root)).not.toThrow();
    expect(feedCards(root)).toEqual([]);
  });
});

describe('after a client-side navigation between videos', () => {
  /**
   * REGRESSION (live QA, 2026-07-26): YouTube does not rewrite its inline scripts on a
   * watch -> watch SPA hop, so they keep describing the PREVIOUS video. Trusting them meant
   * a non-whitelisted video played with no gate and in full colour, the whitelist, the
   * blacklist and gray-screen were all silently bypassed during normal browsing.
   */
  it('identifies the channel of the video actually on screen, not the previous one', () => {
    document.documentElement.innerHTML = WATCH_SPA_STALE_INLINE_HTML;

    const detected = detectWatchChannel(document, { url: STALE_URL });

    expect(detected?.channelId).toBe(FRESH_DOM_CHANNEL_ID);
    expect(detected?.channelId).not.toBe(STALE_INLINE_CHANNEL_ID);
  });

  it('still uses the fast inline data when it describes the video actually on screen', () => {
    // The guard must not throw away the good case: on a full page load the inline data
    // agrees with the URL and remains the preferred, cheapest source.
    document.documentElement.innerHTML = WATCH_INLINE_MATCHES_URL_HTML;

    const detected = detectWatchChannel(document, { url: STALE_URL });

    expect(detected?.channelId).toBe(STALE_INLINE_CHANNEL_ID);
  });

  it('reads the video id from a watch URL and from a Shorts URL', () => {
    expect(videoIdFromUrl('https://www.youtube.com/watch?v=abc123&t=30')).toBe('abc123');
    expect(videoIdFromUrl('https://www.youtube.com/shorts/xyz789')).toBe('xyz789');
    expect(videoIdFromUrl('https://www.youtube.com/')).toBeNull();
    expect(videoIdFromUrl('not a url')).toBeNull();
  });

  it('still identifies the channel on a page whose URL names no video at all', () => {
    // A channel page has nothing to cross-check against, so the inline tiers stay usable.
    document.documentElement.innerHTML = WATCH_PLAYER_RESPONSE_HTML;

    const detected = detectWatchChannel(document, { url: 'https://www.youtube.com/@someone' });

    expect(detected?.channelId).toBe('UCplayerresponse00000001');
  });
});

describe('a card whose first channel anchor is an empty placeholder', () => {
  /**
   * REGRESSION (live QA, 2026-07-26, the "Big Think" search result): a hidden
   * `ytd-channel-name` anchor with an empty href won the first selector rung, yielded no
   * identifier, and the card was treated as unidentifiable, so it leaked through a
   * whitelist even though its real `/@handle` link was right there in the same card.
   */
  it('still identifies the channel from the real link further down the card', () => {
    document.body.innerHTML = SEARCH_HREFLESS_PLACEHOLDER_HTML;
    const card = document.querySelector('[data-testid="card-bigthink"]');
    if (card === null) throw new Error('fixture is missing the card');

    const detected = detectCardChannel(card);

    expect(detected?.handle).toBe('@bigthink');
  });
});

describe('a healthy watch page whose identifiers live in different places', () => {
  /**
   * The live-QA regression (2026-09-20). This is what a real watch page IS: the canonical
   * id only in `ytInitialPlayerResponse`, the handle only in the owner byline. Detection
   * has to come back carrying BOTH, because the stored entry may hold either one alone.
   */
  const url = `https://www.youtube.com/watch?v=${WATCH_FIXTURE_VIDEO_ID}`;

  it('reports the id AND the handle, not whichever tier answered first', () => {
    mount(WATCH_ID_INLINE_HANDLE_DOM_HTML);
    const detected = detectWatchChannel(document, { url });

    expect(detected?.channelId).toBe(REAL_WATCH_ID_INLINE_HANDLE_DOM_CHANNEL_ID);
    expect(detected?.handle).toBe(`@${REAL_WATCH_ID_INLINE_HANDLE_DOM_HANDLE}`);
  });

  it('still refuses to merge two observations that cannot be the same channel', () => {
    // The existing fixture gives every tier a DIFFERENT channel on purpose. Assembly must
    // not weld them together into something that matches neither.
    mount(WATCH_PLAYER_RESPONSE_HTML);
    const detected = detectWatchChannel(document, { url });

    expect(detected?.channelId).toBe('UCplayerresponse00000001');
    expect(detected?.handle).toBeNull();
  });

  it('reads both axes off one feed card that carries both links', () => {
    const root = mount(CARD_BOTH_AXES_HTML);
    const card = feedCards(root)[0];
    const detected = detectCardChannel(card!);

    expect(detected?.channelId).toBe(REAL_WATCH_ID_INLINE_HANDLE_DOM_CHANNEL_ID);
    expect(detected?.handle).toBe(`@${REAL_WATCH_ID_INLINE_HANDLE_DOM_HANDLE}`);
  });
});

describe('a real watch page, with the previous channel page still mounted beside it', () => {
  /**
   * Live DOM, 2026-10-23: YouTube keeps every page it has shown under `ytd-page-manager` and
   * only marks the old ones `hidden`. After a hop from a channel page the document holds that
   * hidden channel page AND the watch player. The old unscoped query read whichever link came
   * first and could stitch the hidden page's channel onto the video owner (a "chimera").
   */
  const VIDEO = 'liveVideo0001';
  const url = `https://www.youtube.com/watch?v=${VIDEO}`;

  it("identifies the video's owner, not the hidden channel page's channel", () => {
    mount(liveWatchPage({ videoId: VIDEO, staleBrowseHandle: STALE_BROWSE_HANDLE }));

    const detected = detectWatchChannel(document, { url });

    expect(detected?.handle).toBe(`@${LIVE_OWNER_HANDLE}`);
    expect(detected?.channelId).toBeNull();
  });

  it('does not take a channel from the related-videos column', () => {
    mount(liveWatchPage({ videoId: VIDEO }));

    expect(detectWatchChannel(document, { url })?.handle).not.toBe(`@${RELATED_HANDLE}`);
  });

  it('names nobody while the player still shows the PREVIOUS video (not re-rendered yet)', () => {
    // URL has moved on to VIDEO, but the visible player still declares the old video, so the
    // owner byline on screen belongs to that old video.
    mount(liveWatchPage({ videoId: VIDEO, flexyVideoId: 'previousVid01' }));

    expect(detectWatchChannel(document, { url })).toBeNull();
  });

  it('names nobody when the watch player is hidden (another page is showing)', () => {
    mount(liveWatchPage({ videoId: VIDEO, flexyHidden: true, staleBrowseHandle: STALE_BROWSE_HANDLE }));

    expect(detectWatchChannel(document, { url })).toBeNull();
  });

  it('names nobody while the player is up but its owner byline has not rendered', () => {
    mount(liveWatchPage({ videoId: VIDEO, noOwner: true, staleBrowseHandle: STALE_BROWSE_HANDLE }));

    expect(detectWatchChannel(document, { url })).toBeNull();
  });
});

describe('which video the visible watch player declares', () => {
  it('reads the video id off the visible player', () => {
    mount(liveWatchPage({ flexyVideoId: 'abcDEF12345' }));
    expect(watchPlayerVideoId(document)).toBe('abcDEF12345');
  });

  it('says nothing when the only player is hidden', () => {
    mount(liveWatchPage({ flexyVideoId: 'abcDEF12345', flexyHidden: true }));
    expect(watchPlayerVideoId(document)).toBeNull();
  });

  it('says nothing when the player declares no video', () => {
    mount(liveWatchPage({ flexyVideoId: null }));
    expect(watchPlayerVideoId(document)).toBeNull();
  });

  it('says nothing on a page with no watch player at all', () => {
    mount('<ytd-app><ytd-page-manager></ytd-page-manager></ytd-app>');
    expect(watchPlayerVideoId(document)).toBeNull();
  });
});

describe('the video id a URL names', () => {
  it('reads a live stream and a short, the same as a watch URL', () => {
    expect(videoIdFromUrl('https://www.youtube.com/live/abc123')).toBe('abc123');
    expect(videoIdFromUrl('https://www.youtube.com/shorts/xyz789')).toBe('xyz789');
    expect(videoIdFromUrl('https://www.youtube.com/watch?v=q1w2e3')).toBe('q1w2e3');
  });

  it('stops at a query string or fragment', () => {
    expect(videoIdFromUrl('https://www.youtube.com/live/abc123?t=30#x')).toBe('abc123');
  });

  it('names no video for a channel page', () => {
    expect(videoIdFromUrl('https://www.youtube.com/@veritasium')).toBeNull();
  });
});

describe('whose channel page this is', () => {
  /**
   * Live capture 2026-10-23: a channel page's `ytInitialData.metadata.channelMetadataRenderer`
   * carries both identifiers. It goes stale on an SPA hop, so it is only returned when it
   * provably describes the URL in the address bar.
   */
  const FRESH = { inlineIsFresh: true };
  const AFTER_HOP = { inlineIsFresh: false };
  const EXPECTED = {
    channelId: LIVE_OWNER_CHANNEL_ID,
    handle: `@${LIVE_OWNER_HANDLE}`,
    displayName: LIVE_OWNER_NAME,
  };

  function page(): void {
    mount(channelPageHtml());
  }

  it('identifies the channel from its /@handle URL, whatever the letter case', () => {
    page();
    expect(channelPageIdentity(document, 'https://www.youtube.com/@Veritasium', AFTER_HOP)).toEqual(
      EXPECTED,
    );
  });

  it('identifies it on a sub-page such as /videos', () => {
    page();
    expect(
      channelPageIdentity(document, 'https://www.youtube.com/@veritasium/videos', AFTER_HOP),
    ).toEqual(EXPECTED);
  });

  it('identifies it from its /channel/UC URL', () => {
    page();
    expect(
      channelPageIdentity(
        document,
        `https://www.youtube.com/channel/${LIVE_OWNER_CHANNEL_ID}`,
        AFTER_HOP,
      ),
    ).toEqual(EXPECTED);
  });

  it('refuses stale metadata when the URL names a DIFFERENT handle (after an SPA hop)', () => {
    page();
    expect(channelPageIdentity(document, 'https://www.youtube.com/@someoneelse', AFTER_HOP)).toBeNull();
  });

  it('refuses stale metadata when the URL names a DIFFERENT channel id', () => {
    page();
    expect(
      channelPageIdentity(document, 'https://www.youtube.com/channel/UCsomeoneelse0000000000', AFTER_HOP),
    ).toBeNull();
  });

  it('trusts the metadata on a legacy /c/ or /user/ URL only on a fresh full load', () => {
    page();
    for (const legacy of ['https://www.youtube.com/c/veritasium', 'https://www.youtube.com/user/x']) {
      expect(channelPageIdentity(document, legacy, FRESH)).toEqual(EXPECTED);
      expect(channelPageIdentity(document, legacy, AFTER_HOP)).toBeNull();
    }
  });

  it('names no channel for a URL that is not a channel page', () => {
    page();
    expect(channelPageIdentity(document, 'https://www.youtube.com/watch?v=abc', FRESH)).toBeNull();
    expect(channelPageIdentity(document, 'https://www.youtube.com/', FRESH)).toBeNull();
  });

  it('names no channel when the page carries no metadata', () => {
    mount('<div></div>');
    expect(channelPageIdentity(document, 'https://www.youtube.com/@veritasium', FRESH)).toBeNull();
  });
});

describe('which video the inline ytInitialData is the watch page for', () => {
  /** Key order as the live payloads (probe, 2026-10-23); only a watch page has `currentVideoEndpoint`. */
  function inlineData(data: Record<string, unknown>): void {
    mount(`<script>var ytInitialData = ${JSON.stringify(data)};</script>`);
  }
  const card = (videoId: string) => ({
    videoRenderer: { videoId, navigationEndpoint: { watchEndpoint: { videoId } } },
  });

  it('names the watch page\'s own video, not a related one', () => {
    inlineData({
      responseContext: {},
      contents: { related: card('relatedVideo1') },
      currentVideoEndpoint: { watchEndpoint: { videoId: 'thisVideo01' } },
    });
    expect(initialDataVideoId(document)).toBe('thisVideo01');
  });

  it('names no video on a channel page, whose grid is full of other videos\' endpoints', () => {
    inlineData({
      responseContext: {},
      contents: { grid: [card('NiCgcUTL9_Q'), card('AF8d72mA41M')] },
      header: {},
      metadata: { channelMetadataRenderer: { externalId: 'UCHnyfMqiRRG1u-2MsSQLbXA' } },
    });
    expect(initialDataVideoId(document)).toBeNull();
  });

  it('names no video when the endpoint is malformed', () => {
    inlineData({ currentVideoEndpoint: { watchEndpoint: { videoId: '' } } });
    expect(initialDataVideoId(document)).toBeNull();
    inlineData({ currentVideoEndpoint: 'nope' });
    expect(initialDataVideoId(document)).toBeNull();
  });
});
