/**
 * Feed-page fixtures for per-card channel detection (ext-03 §3).
 *
 * HAND-AUTHORED, NOT LIVE CAPTURES — same caveat as tests/content/fixtures/youtube.ts and
 * watchPage.ts. REFRESH FROM REAL DOM WHEN POSSIBLE.
 *
 * `FEED_MULTI_CHANNEL_HTML` is the scoping test: four `VIDEO_RENDERERS` cards (ext-03 §3),
 * three pointing at three DIFFERENT channels via three DIFFERENT rungs of the
 * `CHANNEL_ELEMENTS` chain (a standard `ytd-channel-name` link, a class-anchored link, and a
 * legacy `/c/` link), one with no channel link at all, PLUS a page-level "decoy" channel
 * link that sits OUTSIDE every card, appearing first in document order.
 *
 * The decoy is the point: `queryWithFallback` matches in document order, so if per-card
 * detection were ever accidentally run unscoped (e.g. against the whole document instead of
 * the individual card element), EVERY card would silently resolve to the decoy's channel
 * instead of its own — the exact bug class this fixture exists to catch. A test that only
 * used distinct-but-ordered channels per card could pass even with a scoping bug, if the
 * bug happened to preserve relative order; a decoy that isn't any card's real channel and
 * sorts before all of them removes that loophole.
 */

export const FEED_MULTI_CHANNEL_HTML = `
  <div id="page-manager">
    <div id="masthead-decoy">
      <a href="/channel/UCPAGELEVELDECOY000001" aria-label="Go to channel Page Level Decoy">Page Level Decoy</a>
    </div>
    <ytd-rich-grid-renderer>
      <div id="contents">
        <ytd-rich-item-renderer data-testid="card-alpha">
          <ytd-channel-name id="channel-name">
            <a class="yt-formatted-string" href="/channel/UCALPHACHANNEL000000001" aria-label="Go to channel Alpha Channel">Alpha Channel</a>
          </ytd-channel-name>
          <a id="video-title-link" href="/watch?v=alpha0001">Alpha's newest upload</a>
        </ytd-rich-item-renderer>
        <ytd-grid-video-renderer data-testid="card-bravo">
          <a class="ytd-channel-name" href="/@bravochannel">Bravo Channel</a>
          <a id="video-title-link" href="/watch?v=bravo0001">Bravo's newest upload</a>
        </ytd-grid-video-renderer>
        <ytd-compact-video-renderer data-testid="card-charlie">
          <a href="/c/charliechannel" aria-label="Go to channel Charlie Channel - Channel">Charlie Channel</a>
          <a id="video-title-link" href="/watch?v=charlie0001">Charlie's newest upload</a>
        </ytd-compact-video-renderer>
        <ytd-video-renderer data-testid="card-no-channel">
          <a id="video-title-link" href="/watch?v=nochannel001">A video with no discoverable channel link</a>
        </ytd-video-renderer>
      </div>
    </ytd-rich-grid-renderer>
  </div>
`;

/** A feed container with no cards at all — `feedCards` must return an empty array, not throw. */
export const EMPTY_FEED_HTML = `
  <div id="page-manager">
    <ytd-rich-grid-renderer><div id="contents"></div></ytd-rich-grid-renderer>
  </div>
`;
