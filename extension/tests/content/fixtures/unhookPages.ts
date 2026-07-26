/**
 * Fixtures for the Unhook-parity hide toggles (Home Feed, Sidebar Recs, End Screen,
 * Comments) plus the Autoplay-off switch.
 *
 * HAND-AUTHORED FROM THE ext-03 §5 TAXONOMY, NOT LIVE CAPTURES — same caveat as every other
 * fixture file in tests/content/fixtures/. REFRESH FROM REAL DOM WHEN POSSIBLE.
 *
 * Every fixture that hides something also carries at least one element that must survive,
 * so a chain that over-matches (eats more than its own surface) gets caught.
 */

/** Left nav present on most pages — not itself a hide-toggle surface, just page furniture. */
const MINI_GUIDE = `
  <ytd-mini-guide-renderer id="mini-guide">
    <ytd-mini-guide-entry-renderer><a href="/" title="Home">Home</a></ytd-mini-guide-entry-renderer>
    <ytd-mini-guide-entry-renderer><a href="/shorts/" title="Shorts">Shorts</a></ytd-mini-guide-entry-renderer>
  </ytd-mini-guide-renderer>
`;

/** A normal home-feed video card, outside the feed `#contents` region under test. */
const MASTHEAD = `
  <ytd-masthead id="masthead" data-testid="masthead">
    <div id="logo">Nudge does not touch this</div>
  </ytd-masthead>
`;

/**
 * HOME PAGE — `home-feed` surface (`ytd-browse[page-subtype="home"] #contents`, the first
 * rung of the chain in selectors.ts), plus unrelated chrome (masthead, mini nav) that must
 * survive every pass regardless of toggle state.
 *
 * Also carries a decoy `#related` element — the exact id the `sidebar-recs` surface's
 * second chain rung looks for — sitting OUTSIDE any watch-only wrapper. It exists purely to
 * prove page-type scoping: `sidebar-recs.pages` is `['watch']` only, so a test that turns
 * `hideSidebarRecs` on while viewing this HOME page must never touch the decoy, because the
 * surface should never even be queried here.
 */
export const HOME_PAGE_HTML = `
  <div id="page-manager">
    ${MASTHEAD}
    ${MINI_GUIDE}
    <ytd-browse page-subtype="home">
      <div id="primary">
        <div id="contents" data-testid="home-feed-contents">
          <ytd-rich-item-renderer class="normal-video" data-testid="home-video-1">
            A perfectly normal home-feed video
          </ytd-rich-item-renderer>
          <ytd-rich-item-renderer class="normal-video" data-testid="home-video-2">
            Another normal home-feed video
          </ytd-rich-item-renderer>
        </div>
      </div>
    </ytd-browse>
    <div id="related" data-testid="home-related-decoy">
      A sidebar-recs-shaped decoy that must never be considered on the home page
    </div>
  </div>
`;

/**
 * WATCH PAGE — every hide-toggle surface present at once, PLUS the autoplay switch ON, PLUS
 * an overlay-guard decoy.
 *
 *  - sidebar-recs: `#secondary #related` (rung 1) wraps a real recommendation and one that
 *    happens to look like a Short — irrelevant here, `sidebar-recs` hides the whole block.
 *  - end-screen: TWO `.ytp-endscreen-content` elements match the SAME winning rung — one is
 *    the real in-player suggestion grid, the other is nested inside our own overlay
 *    (`#nudge-shorts-gate`, `NUDGE_OVERLAY_ID`). Both match the selector; only the real one
 *    may ever be hidden. This is the "never hide our own overlay, whatever a selector
 *    claims" case — the decoy shares a rung with a genuine match, so both come back from
 *    the SAME `queryWithFallback` call and the overlay-guard in `applyHideToggles` is what
 *    has to tell them apart, not chain selection.
 *  - comments: `#comments #contents` (rung 1).
 *  - autoplay: `.ytp-autonav-toggle-button[aria-checked="true"]` (rung 1).
 */
export const WATCH_PAGE_ALL_SURFACES_HTML = `
  <div id="page-manager">
    ${MASTHEAD}
    ${MINI_GUIDE}
    <ytd-watch-flexy>
      <div id="primary">
        <div id="player">
          <video id="movie_player"></video>
          <div class="ytp-chrome-controls">
            <button class="ytp-autonav-toggle-button" aria-checked="true" data-testid="autoplay-toggle">
              Autoplay
            </button>
          </div>
          <div class="ytp-ce-element" data-testid="watch-end-cards">
            Creator end-cards, which COEXIST with the suggestion grid below
          </div>
          <div class="ytp-endscreen-content" data-testid="watch-end-screen">
            Real in-player end-screen suggestions
          </div>
        </div>
        <div id="nudge-shorts-gate" class="nudge-overlay" data-testid="nudge-overlay">
          <div class="nudge-overlay__card">
            <div class="ytp-endscreen-content" data-testid="overlay-endscreen-decoy">
              Coincidentally matches the end-screen selector — lives INSIDE our own overlay
              and must never be hidden.
            </div>
          </div>
        </div>
        <div id="comments" data-testid="watch-comments">
          <div id="contents" data-testid="watch-comments-contents">
            <ytd-comment-thread-renderer data-testid="comment-1">A real comment</ytd-comment-thread-renderer>
          </div>
        </div>
      </div>
      <div id="secondary">
        <div id="related" data-testid="watch-related">
          <ytd-compact-video-renderer class="normal-video" data-testid="watch-rec-1">
            A recommended video
          </ytd-compact-video-renderer>
          <ytd-compact-video-renderer class="normal-video" data-testid="watch-rec-2">
            Another recommended video
          </ytd-compact-video-renderer>
        </div>
      </div>
    </ytd-watch-flexy>
  </div>
`;

/**
 * WATCH PAGE, AUTOPLAY ALREADY OFF — otherwise minimal (autoplay is the only thing under
 * test here). `aria-checked="false"` must not match EITHER `AUTOPLAY_TOGGLE` rung.
 */
export const WATCH_PAGE_AUTOPLAY_OFF_HTML = `
  <div id="page-manager">
    ${MINI_GUIDE}
    <ytd-watch-flexy>
      <div id="primary">
        <div id="player">
          <video id="movie_player"></video>
          <div class="ytp-chrome-controls">
            <button class="ytp-autonav-toggle-button" aria-checked="false" data-testid="autoplay-toggle-off">
              Autoplay
            </button>
          </div>
        </div>
      </div>
    </ytd-watch-flexy>
  </div>
`;

/**
 * THE CHAIN-PROGRESSION CASE. The `comments` surface's first two rungs (`#comments
 * #contents`, `#comments`) are both absent — YouTube renamed the wrapper the way it
 * periodically does — leaving only the third rung, `#watch-discussion` (the DF Tube legacy
 * target), to find anything. Every OTHER surface on this page is left in its normal,
 * unrenamed shape, so a test can isolate "does the chain still find comments via its last
 * rung" from every other surface's behaviour.
 */
export const WATCH_PAGE_RENAMED_COMMENTS_HTML = `
  <div id="page-manager">
    ${MINI_GUIDE}
    <ytd-watch-flexy>
      <div id="primary">
        <div id="player"><video id="movie_player"></video></div>
        <div class="ytp-endscreen-content" data-testid="renamed-end-screen">
          End-screen suggestions, unrenamed
        </div>
        <div id="watch-discussion" data-testid="renamed-comments-legacy">
          <ytd-comment-thread-renderer data-testid="renamed-comment-1">
            A comment findable only via the legacy rung
          </ytd-comment-thread-renderer>
        </div>
      </div>
      <div id="secondary">
        <div id="related" data-testid="renamed-related">
          <ytd-compact-video-renderer class="normal-video" data-testid="renamed-rec-1">
            A recommended video
          </ytd-compact-video-renderer>
        </div>
      </div>
    </ytd-watch-flexy>
  </div>
`;
