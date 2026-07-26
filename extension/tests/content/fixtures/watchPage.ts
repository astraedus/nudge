/**
 * Watch-page fixtures for channel detection (ext-03 §3).
 *
 * HAND-AUTHORED, NOT LIVE CAPTURES — same caveat as tests/content/fixtures/youtube.ts and
 * feedPage.ts. These reproduce the SHAPES documented in
 * ops/routes/nudge/research/ext-03-youtube-techniques.md §3:
 *   - `ytInitialPlayerResponse.videoDetails.{channelId,author}` (tier 1)
 *   - `ytInitialData`'s `videoSecondaryInfoRenderer.owner.videoOwnerRenderer` (tier 2)
 *   - the `CHANNEL_ELEMENTS` DOM chain (tier 3, selectors.ts)
 *
 * REFRESH FROM REAL DOM/PAYLOADS WHEN POSSIBLE.
 *
 * The inline `<script>` JSON payloads are built as plain JS object literals below and
 * serialized with `JSON.stringify` rather than typed out as JSON text by hand. The SHAPE is
 * still hand-authored from the taxonomy; letting `JSON.stringify` own the escaping is what
 * makes the braces-in-strings / escaped-quotes fixture trustworthy — a hand-typed JSON
 * string is exactly the kind of thing that silently drifts out of being valid JSON, which
 * would make it a bad test of a JSON parser.
 */

const MINI_NAV = `
  <ytd-mini-guide-renderer id="mini-guide">
    <ytd-mini-guide-entry-renderer><a href="/" title="Home">Home</a></ytd-mini-guide-entry-renderer>
    <ytd-mini-guide-entry-renderer><a href="/shorts/" title="Shorts">Shorts</a></ytd-mini-guide-entry-renderer>
  </ytd-mini-guide-renderer>
`;

const PLAYER = `<div id="player"><video id="movie_player"></video></div>`;

/** A standard, non-renamed channel-name element (tier 3's best-case rung). */
function standardChannelDom(opts: { href: string; name: string; ariaLabel?: string }): string {
  const aria = opts.ariaLabel !== undefined ? ` aria-label="${opts.ariaLabel}"` : '';
  return `
    <ytd-channel-name id="channel-name">
      <a class="yt-formatted-string" href="${opts.href}"${aria}>${opts.name}</a>
    </ytd-channel-name>
  `;
}

/** `ytInitialPlayerResponse` inline script — tier 1's shape. */
export const WATCH_FIXTURE_VIDEO_ID = 'watchvideo0001';

function playerResponseScript(channelId: string, author: string): string {
  const payload = {
    videoDetails: {
      videoId: WATCH_FIXTURE_VIDEO_ID,
      title: 'A perfectly normal video',
      channelId,
      author,
    },
    playabilityStatus: { status: 'OK' },
  };
  return `<script>var ytInitialPlayerResponse = ${JSON.stringify(payload)};</script>`;
}

/**
 * `ytInitialData` inline script — tier 2's shape. `videoSecondaryInfoRenderer` is nested a
 * few renderer wrappers deep, matching the real page's render tree; `findRenderer` in
 * channelDetection.ts finds it by key, not by this exact path, so the wrappers above it
 * could be renamed and this would still work.
 */
function initialDataScript(opts: {
  varDeclaration?: 'var' | 'bracket';
  channelId: string;
  handlePath: string;
  name: string;
}): string {
  const payload = {
    contents: {
      twoColumnWatchNextResults: {
        results: {
          results: {
            contents: [
              {
                videoSecondaryInfoRenderer: {
                  owner: {
                    videoOwnerRenderer: {
                      title: {
                        runs: [
                          {
                            text: opts.name,
                            navigationEndpoint: {
                              browseEndpoint: { browseId: opts.channelId },
                            },
                          },
                        ],
                      },
                      navigationEndpoint: {
                        browseEndpoint: {
                          browseId: opts.channelId,
                          canonicalBaseUrl: opts.handlePath,
                        },
                      },
                    },
                  },
                },
              },
            ],
          },
        },
      },
    },
  };
  const json = JSON.stringify(payload);
  const assignment =
    opts.varDeclaration === 'bracket'
      ? `window["ytInitialData"] = ${json};`
      : `var ytInitialData = ${json};`;
  return `<script>${assignment}</script>`;
}

/**
 * TIER 1 WINS. All three tiers are present, each with a DIFFERENT channel, so a test can
 * assert the composite picked the player response specifically — not just "found A channel".
 */
export const WATCH_PLAYER_RESPONSE_HTML = `
  <div id="page-manager">
    ${MINI_NAV}
    ${playerResponseScript('UCplayerresponse00000001', 'Player Response Channel')}
    ${initialDataScript({
      channelId: 'UCinitialdatawins0000002',
      handlePath: '/@initialdatachannel',
      name: 'Initial Data Channel',
    })}
    <ytd-watch-flexy>
      <div id="primary">${PLAYER}</div>
      <div id="secondary">
        ${standardChannelDom({
          href: '/channel/UCdomfallback0000000003',
          name: 'Dom Fallback Channel',
          ariaLabel: 'Go to channel Dom Fallback Channel',
        })}
      </div>
    </ytd-watch-flexy>
  </div>
`;

/**
 * TIER 2 WINS. No `ytInitialPlayerResponse` at all (some surfaces genuinely omit it) — the
 * composite must fall through to `ytInitialData`, and prefer it over the DOM, which also
 * carries a (different, deliberately wrong-to-pick) channel.
 */
export const WATCH_INITIAL_DATA_ONLY_HTML = `
  <div id="page-manager">
    ${MINI_NAV}
    ${initialDataScript({
      channelId: 'UCinitialdataonly0000004',
      handlePath: '/@initialdataonlychannel',
      name: 'Initial Data Only Channel',
    })}
    <ytd-watch-flexy>
      <div id="primary">${PLAYER}</div>
      <div id="secondary">
        ${standardChannelDom({
          href: '/channel/UCdomshouldnotwin0000005',
          name: 'Dom Should Not Win',
          ariaLabel: 'Go to channel Dom Should Not Win',
        })}
      </div>
    </ytd-watch-flexy>
  </div>
`;

/** TIER 3 WINS. Neither script is present — only the standard (non-renamed) channel DOM. */
export const WATCH_DOM_ONLY_HTML = `
  <div id="page-manager">
    ${MINI_NAV}
    <ytd-watch-flexy>
      <div id="primary">${PLAYER}</div>
      <div id="secondary">
        ${standardChannelDom({
          href: '/channel/UCdomonlystandard0000006',
          name: 'Standard Dom Channel',
          ariaLabel: 'Go to channel Standard Dom Channel',
        })}
      </div>
    </ytd-watch-flexy>
  </div>
`;

/**
 * THE UPSTREAM-CHURN CASE. No scripts, and every specific CHANNEL_ELEMENTS wrapper has been
 * renamed the way Google periodically renames them — `ytd-channel-name` becomes
 * `yt-owner-view-model`, no `/channel/` link exists. Only the generic `a[href^="/@"]` rung
 * can still find the channel, which is precisely the case ext-03 §3 flags as the reason
 * that rung exists.
 */
export const WATCH_RENAMED_WRAPPERS_HTML = `
  <div id="page-manager">
    ${MINI_NAV}
    <yt-watch-view-model>
      <div id="primary">${PLAYER}</div>
      <div id="secondary">
        <yt-owner-view-model>
          <a href="/@renamedhandlechannel" aria-label="Go to channel Renamed Handle Channel">Renamed Handle Channel</a>
        </yt-owner-view-model>
      </div>
    </yt-watch-view-model>
  </div>
`;

/** No scripts, no channel DOM anywhere. Every tier should come up empty, cleanly. */
export const WATCH_NOTHING_HTML = `
  <div id="page-manager">
    ${MINI_NAV}
    <ytd-watch-flexy>
      <div id="primary">${PLAYER}</div>
      <div id="secondary"><div id="related"></div></div>
    </ytd-watch-flexy>
  </div>
`;

/**
 * A truncated `ytInitialData` payload — the object literal never closes. Exercises the
 * brace scanner's unbalanced-input path (returns null rather than slicing something
 * unparseable) as well as the "never throw" contract end to end.
 */
export const WATCH_MALFORMED_JSON_HTML = `
  <div id="page-manager">
    ${MINI_NAV}
    <script>var ytInitialData = {"contents": {"twoColumnWatchNextResults": {"results": {"results": {"contents": [{"videoSecondaryInfoRenderer": {"owner": {"videoOwnerRenderer":</script>
    <ytd-watch-flexy>
      <div id="primary">${PLAYER}</div>
      <div id="secondary"><div id="related"></div></div>
    </ytd-watch-flexy>
  </div>
`;

/**
 * The channel display name deliberately contains `{`, `}`, escaped double quotes and an
 * escaped backslash — the exact content shape that breaks a naive delimiter-split JSON
 * extraction and that only a real brace-counting, string-aware scanner survives. Exported
 * so the test file can assert the exact round-tripped value rather than re-typing it.
 */
export const TRICKY_CHANNEL_NAME = 'Weird {Channel} "Name" \\ Co.';

/**
 * THE BRACE-SCANNER STRESS CASE. Uses the `window["ytInitialData"] = …` assignment form (a
 * second proof the assignment-finder isn't tied to `var …`), and a display name containing
 * unescaped braces plus escaped quotes/backslashes inside the JSON string value.
 */
export const WATCH_TRICKY_JSON_HTML = `
  <div id="page-manager">
    ${MINI_NAV}
    ${initialDataScript({
      varDeclaration: 'bracket',
      channelId: 'UCtrickytrickytricky00007',
      handlePath: '/@trickychannel',
      name: TRICKY_CHANNEL_NAME,
    })}
    <ytd-watch-flexy>
      <div id="primary">${PLAYER}</div>
      <div id="secondary"></div>
    </ytd-watch-flexy>
  </div>
`;

/**
 * Isolated case for `channelFromDom`'s "aria-label first" rule: the aria-label and the
 * text content deliberately disagree, so a test can prove which one wins.
 */
export const ARIA_LABEL_VS_TEXT_HTML = `
  <div id="page-manager">
    <a href="/channel/UCariaVsText0000000008" aria-label="Go to channel Aria Label Wins">Different Text Content</a>
  </div>
`;

/**
 * THE SPA-STALENESS CASE (live QA, 2026-07-26).
 *
 * YouTube does not rewrite the inline scripts on a watch -> watch client-side navigation, so
 * after hopping from one video to another the page carries: inline JSON describing the
 * PREVIOUS video (and its channel), and a freshly re-rendered DOM byline describing the
 * CURRENT one. Detection must notice the video ids disagree and trust the DOM.
 *
 * Pair with `STALE_URL` / `STALE_INLINE_VIDEO_ID` below.
 */
export const STALE_INLINE_VIDEO_ID = 'previousvideo01';
export const CURRENT_VIDEO_ID = 'currentvideo002';
export const STALE_URL = `https://www.youtube.com/watch?v=${CURRENT_VIDEO_ID}`;
export const STALE_INLINE_CHANNEL_ID = 'UCstalepinnedchannel001';
export const FRESH_DOM_CHANNEL_ID = 'UCfreshdomchannel000002';

export const WATCH_SPA_STALE_INLINE_HTML = `
  <div id="page-manager">
    ${MINI_NAV}
    <script>var ytInitialPlayerResponse = ${JSON.stringify({
      videoDetails: {
        videoId: STALE_INLINE_VIDEO_ID,
        title: 'The video we navigated AWAY from',
        channelId: STALE_INLINE_CHANNEL_ID,
        author: 'Stale Pinned Channel',
      },
      playabilityStatus: { status: 'OK' },
    })};</script>
    <ytd-watch-flexy>
      <div id="primary">${PLAYER}</div>
      <div id="secondary">
        ${standardChannelDom({
          href: `/channel/${FRESH_DOM_CHANNEL_ID}`,
          name: 'Fresh Dom Channel',
          ariaLabel: 'Go to channel Fresh Dom Channel',
        })}
      </div>
    </ytd-watch-flexy>
  </div>
`;

/** The same page after a FULL load: the inline data agrees with the URL, so it is usable. */
export const WATCH_INLINE_MATCHES_URL_HTML = `
  <div id="page-manager">
    ${MINI_NAV}
    <script>var ytInitialPlayerResponse = ${JSON.stringify({
      videoDetails: {
        videoId: CURRENT_VIDEO_ID,
        title: 'The video actually on screen',
        channelId: STALE_INLINE_CHANNEL_ID,
        author: 'Stale Pinned Channel',
      },
      playabilityStatus: { status: 'OK' },
    })};</script>
    <ytd-watch-flexy>
      <div id="primary">${PLAYER}</div>
      <div id="secondary">
        ${standardChannelDom({
          href: `/channel/${FRESH_DOM_CHANNEL_ID}`,
          name: 'Fresh Dom Channel',
          ariaLabel: 'Go to channel Fresh Dom Channel',
        })}
      </div>
    </ytd-watch-flexy>
  </div>
`;
