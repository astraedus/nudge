/**
 * YouTube DOM fixtures for the selector tests.
 *
 * HAND-AUTHORED, NOT LIVE CAPTURES. These strings reproduce the element structures
 * documented in ops/routes/nudge/research/ext-03-youtube-techniques.md §1 — the taxonomy
 * read out of shipped OSS extensions (`Vulpelo/hide-youtube-shorts`,
 * `malekwael229/FocusTube`, `tobiasdalhof/sanersocialmedia`). They are deliberately
 * minimal: the wrapper tags, the attributes and the nesting our chains depend on, and
 * nothing else.
 *
 * REFRESH THEM FROM REAL DOM WHEN POSSIBLE. The whole point of the fixture pattern
 * (ext-03 §6 pattern 4, Vulpelo's `tests/fixtures/*`) is that upstream YouTube churn
 * fails CI instead of users — and a hand-authored fixture can only catch churn we already
 * modelled. Capturing `document.body.outerHTML` from a real logged-in session and pruning
 * it down beats this file the moment someone has a browser open.
 *
 * Every fixture contains at least one NORMAL (non-Shorts) video card that must survive
 * hiding. A selector that also eats regular videos is worse than no selector at all.
 */

/** Left nav in its collapsed mini-guide form, present on most pages. */
const MINI_GUIDE = `
  <ytd-mini-guide-renderer id="mini-guide">
    <ytd-mini-guide-entry-renderer class="guide-entry"><a href="/" title="Home"><span>Home</span></a></ytd-mini-guide-entry-renderer>
    <ytd-mini-guide-entry-renderer class="guide-entry"><a href="/shorts/" title="Shorts"><span>Shorts</span></a></ytd-mini-guide-entry-renderer>
    <ytd-mini-guide-entry-renderer class="guide-entry"><a href="/feed/subscriptions" title="Subscriptions"><span>Subscriptions</span></a></ytd-mini-guide-entry-renderer>
  </ytd-mini-guide-renderer>
`;

/** The expanded left nav, used on the home page when the window is wide. */
const FULL_GUIDE = `
  <ytd-guide-renderer id="guide">
    <ytd-guide-entry-renderer><a href="/"><yt-formatted-string>Home</yt-formatted-string></a></ytd-guide-entry-renderer>
    <ytd-guide-entry-renderer><a href="/shorts/" title="Shorts"><yt-formatted-string>Shorts</yt-formatted-string></a></ytd-guide-entry-renderer>
    <ytd-guide-entry-renderer><a href="/feed/subscriptions"><yt-formatted-string>Subscriptions</yt-formatted-string></a></ytd-guide-entry-renderer>
  </ytd-guide-renderer>
`;

/** A normal long-form video card in the rich grid. MUST survive every hiding pass. */
const NORMAL_RICH_ITEM = (id: string, title: string) => `
  <ytd-rich-item-renderer class="normal-video" data-testid="normal-${id}">
    <ytd-rich-grid-media>
      <a id="thumbnail" href="/watch?v=${id}">
        <ytd-thumbnail-overlay-time-status-renderer>12:04</ytd-thumbnail-overlay-time-status-renderer>
      </a>
      <div id="details">
        <a id="video-title-link" href="/watch?v=${id}" title="${title}"><yt-formatted-string id="video-title">${title}</yt-formatted-string></a>
        <ytd-channel-name><a class="yt-formatted-string" href="/@somechannel">Some Channel</a></ytd-channel-name>
      </div>
    </ytd-rich-grid-media>
  </ytd-rich-item-renderer>
`;

/**
 * HOME FEED — the shelf variant. `ytd-rich-section-renderer > div > ytd-rich-shelf-renderer`
 * with `[is-shorts]`, which is the structure both Vulpelo and FocusTube target.
 */
export const HOME_FEED_HTML = `
  <div id="page-manager">
    ${FULL_GUIDE}
    <ytd-browse page-subtype="home">
      <ytd-rich-grid-renderer>
        <div id="contents">
          ${NORMAL_RICH_ITEM('aaa11111111', 'A long video about nothing')}
          ${NORMAL_RICH_ITEM('bbb22222222', 'Another perfectly normal video')}
          <ytd-rich-section-renderer class="shorts-shelf" data-testid="home-shelf">
            <div>
              <ytd-rich-shelf-renderer is-shorts>
                <div id="title-container"><span id="title">Shorts</span></div>
                <div id="contents">
                  <ytd-rich-item-renderer class="shorts-card" data-testid="home-shorts-card-1">
                    <ytm-shorts-lockup-view-model>
                      <a href="/shorts/sss11111111" title="A short">
                        <yt-thumbnail-view-model></yt-thumbnail-view-model>
                      </a>
                    </ytm-shorts-lockup-view-model>
                  </ytd-rich-item-renderer>
                  <ytd-rich-item-renderer class="shorts-card" data-testid="home-shorts-card-2">
                    <ytm-shorts-lockup-view-model>
                      <a href="/shorts/sss22222222" title="Another short">
                        <yt-thumbnail-view-model></yt-thumbnail-view-model>
                      </a>
                    </ytm-shorts-lockup-view-model>
                  </ytd-rich-item-renderer>
                </div>
              </ytd-rich-shelf-renderer>
            </div>
          </ytd-rich-section-renderer>
          ${NORMAL_RICH_ITEM('ccc33333333', 'Yet another normal video')}
        </div>
      </ytd-rich-grid-renderer>
    </ytd-browse>
  </div>
`;

/**
 * SUBSCRIPTIONS FEED — the `ytd-rich-grid-group` variant (Vulpelo's "shorts container
 * home/sub feed"), plus a plain grid video card that must survive.
 */
export const SUBSCRIPTIONS_FEED_HTML = `
  <div id="page-manager">
    ${MINI_GUIDE}
    <ytd-browse page-subtype="subscriptions">
      <ytd-rich-grid-renderer>
        <div id="contents">
          ${NORMAL_RICH_ITEM('ddd44444444', 'Subscribed channel upload')}
          <ytd-rich-grid-group class="shorts-group" data-testid="subs-group">
            <div id="title">Shorts</div>
            <div id="contents">
              <ytd-rich-item-renderer class="shorts-card">
                <a href="/shorts/sss33333333" title="Short in subs"></a>
              </ytd-rich-item-renderer>
            </div>
          </ytd-rich-grid-group>
          <ytd-grid-video-renderer class="normal-video" data-testid="subs-grid-normal">
            <a id="thumbnail" href="/watch?v=eee55555555"></a>
          </ytd-grid-video-renderer>
        </div>
      </ytd-rich-grid-renderer>
    </ytd-browse>
  </div>
`;

/**
 * SEARCH RESULTS — `ytd-reel-shelf-renderer`, the shelf variant unique to search
 * (ext-03 §1, first line of the taxonomy). The normal `ytd-video-renderer` results
 * around it must survive.
 */
export const SEARCH_RESULTS_HTML = `
  <div id="page-manager">
    ${MINI_GUIDE}
    <ytd-search>
      <ytd-section-list-renderer>
        <div id="contents">
          <ytd-video-renderer class="normal-video" data-testid="search-normal-1">
            <a id="thumbnail" href="/watch?v=fff66666666"></a>
            <yt-formatted-string id="video-title">How to actually watch shorts responsibly</yt-formatted-string>
          </ytd-video-renderer>
          <ytd-reel-shelf-renderer class="shorts-shelf" data-testid="search-reel-shelf">
            <div id="title-container"><span>Shorts</span></div>
            <div id="items">
              <ytd-reel-item-renderer><a href="/shorts/sss44444444"></a></ytd-reel-item-renderer>
              <ytd-reel-item-renderer><a href="/shorts/sss55555555"></a></ytd-reel-item-renderer>
            </div>
          </ytd-reel-shelf-renderer>
          <ytd-video-renderer class="normal-video" data-testid="search-normal-2">
            <a id="thumbnail" href="/watch?v=ggg77777777"></a>
            <yt-formatted-string id="video-title">A video literally titled shorts</yt-formatted-string>
          </ytd-video-renderer>
        </div>
      </ytd-section-list-renderer>
    </ytd-search>
  </div>
`;

/**
 * WATCH PAGE SIDEBAR — `ytd-compact-video-renderer` recommendations, one of which is a
 * Short, plus the collapsed mini-guide Shorts tab.
 */
export const WATCH_PAGE_HTML = `
  <div id="page-manager">
    ${MINI_GUIDE}
    <ytd-watch-flexy>
      <div id="primary"><div id="player"><video></video></div></div>
      <div id="secondary">
        <div id="related">
          <ytd-compact-video-renderer class="normal-video" data-testid="watch-normal-1">
            <a id="thumbnail" href="/watch?v=hhh88888888"></a>
          </ytd-compact-video-renderer>
          <ytd-compact-video-renderer class="shorts-card" data-testid="watch-shorts-rec">
            <a id="thumbnail" href="/shorts/sss66666666"></a>
          </ytd-compact-video-renderer>
          <ytd-compact-video-renderer class="normal-video" data-testid="watch-normal-2">
            <a id="thumbnail" href="/watch?v=iii99999999"></a>
          </ytd-compact-video-renderer>
        </div>
      </div>
    </ytd-watch-flexy>
  </div>
`;

/** Just the sidebar, for testing the nav chain in isolation. */
export const MINI_GUIDE_HTML = `<div id="page-manager">${MINI_GUIDE}</div>`;

/** The `/shorts/<id>` player page. Nothing to hide here — this surface gets gated. */
export const SHORTS_PLAYER_HTML = `
  <div id="page-manager">
    ${MINI_GUIDE}
    <ytd-shorts>
      <div id="shorts-inner-container">
        <ytd-reel-video-renderer is-active>
          <div id="player-container"><video id="shorts-video"></video></div>
          <div id="actions">
            <ytd-toggle-button-renderer id="like-button"></ytd-toggle-button-renderer>
          </div>
        </ytd-reel-video-renderer>
        <ytd-reel-video-renderer>
          <div id="player-container"><video></video></div>
        </ytd-reel-video-renderer>
      </div>
    </ytd-shorts>
  </div>
`;

/**
 * THE UPSTREAM-CHURN SCENARIO. Same home feed, but every `ytd-*` Shorts wrapper has been
 * renamed the way Google periodically renames them (`ytd-rich-section-renderer` ->
 * `yt-feed-section-view-model`, `ytd-rich-item-renderer` -> `yt-lockup-view-model`).
 *
 * Only the generic `[href^="/shorts/"]` fallback can still find anything — which is
 * precisely when the loud console warning must fire. Normal video cards are renamed too,
 * so a chain that "recovers" by matching them would be caught here.
 */
export const HOME_FEED_RENAMED_HTML = `
  <div id="page-manager">
    <yt-nav-view-model id="guide">
      <yt-nav-entry-view-model><a href="/">Home</a></yt-nav-entry-view-model>
      <yt-nav-entry-view-model><a href="/shorts/">Shorts</a></yt-nav-entry-view-model>
    </yt-nav-view-model>
    <yt-browse-view-model page-subtype="home">
      <div id="contents">
        <yt-lockup-view-model class="normal-video" data-testid="renamed-normal-1">
          <a href="/watch?v=jjj00000000">A normal video</a>
        </yt-lockup-view-model>
        <yt-feed-section-view-model data-testid="renamed-shelf">
          <div>
            <yt-shelf-view-model shelf-type="shorts">
              <yt-lockup-view-model class="shorts-card" data-testid="renamed-shorts-1">
                <a href="/shorts/sss77777777">A short</a>
              </yt-lockup-view-model>
              <yt-lockup-view-model class="shorts-card" data-testid="renamed-shorts-2">
                <a href="/shorts/sss88888888">Another short</a>
              </yt-lockup-view-model>
            </yt-shelf-view-model>
          </div>
        </yt-feed-section-view-model>
        <yt-lockup-view-model class="normal-video" data-testid="renamed-normal-2">
          <a href="/watch?v=kkk00000000">Another normal video</a>
        </yt-lockup-view-model>
      </div>
    </yt-browse-view-model>
  </div>
`;
