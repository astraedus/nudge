/**
 * A watch page shaped like the REAL youtube.com DOM, plus a real channel page's metadata.
 *
 * DERIVED FROM A LIVE CAPTURE taken on 2026-10-23 (owner `/@veritasium`): the `#owner`
 * subtree of `ytd-watch-metadata`, the `ytd-page-manager` children (a previously visited
 * `ytd-browse` kept alive and `hidden` beside the visible `ytd-watch-flexy[video-id]`), and a
 * channel page's `ytInitialData.metadata.channelMetadataRenderer`. Visual noise is trimmed
 * (svg paths, avatar img src, tooltips, lottie); element names, ids and nesting from
 * `ytd-page-manager` down to the anchors are kept as captured.
 *
 * Why this exists: the older fixtures in `watchPage.ts` are hand-authored. The two live bugs
 * this one pins both needed the real shape to show up: an unscoped query picked up the hidden
 * channel page's links (a chimera of two channels), and `ytd-watch-flexy[video-id]` lags the
 * URL during a hop.
 */

/** The channel the watch page's owner byline names. */
export const LIVE_OWNER_HANDLE = 'veritasium';
export const LIVE_OWNER_CHANNEL_ID = 'UCHnyfMqiRRG1u-2MsSQLbXA';
export const LIVE_OWNER_NAME = 'Veritasium';

/** The OTHER channel whose (hidden) page is still mounted under the page manager. */
export const STALE_BROWSE_HANDLE = 'mrbeast';
export const STALE_BROWSE_CHANNEL_ID = 'UCX6OQ3DkcsbYNE6H8uQQuVA';

/** A channel in the related-videos column beside the player: never the video's owner. */
export const RELATED_HANDLE = 'relatedcreator';

export interface LiveWatchOptions {
  /** The video id in the URL (used for the related-column link only; the URL is the caller's). */
  videoId?: string;
  /** Handle (no `@`) shown in the owner byline. */
  ownerHandle?: string;
  /** Value of `ytd-watch-flexy[video-id]`. Omit to leave the attribute off. */
  flexyVideoId?: string | null;
  /** Mark the watch player `hidden`, as YouTube does when another page is showing. */
  flexyHidden?: boolean;
  /** Handle (no `@`) of the previously visited channel page kept hidden beside the player. */
  staleBrowseHandle?: string | null;
  /** Leave the owner byline out entirely (the player has not rendered it yet). */
  noOwner?: boolean;
}

function ownerHtml(handle: string, name: string): string {
  return `
<div id="owner" class="item style-scope ytd-watch-metadata">
  <ytd-video-owner-renderer watch-metadata-refresh="" class="style-scope ytd-watch-metadata">
    <a class="yt-simple-endpoint style-scope ytd-video-owner-renderer" tabindex="-1" href="/@${handle}">
      <yt-img-shadow id="avatar" width="40" class="style-scope ytd-video-owner-renderer no-transition" alt="${name}" loaded=""></yt-img-shadow>
    </a>
    <div id="upload-info" class="style-scope ytd-video-owner-renderer">
      <yt-attributed-string id="attributed-channel-name" class="style-scope ytd-video-owner-renderer"></yt-attributed-string>
      <ytd-channel-name id="channel-name" class="style-scope ytd-video-owner-renderer">
        <div id="container" class="style-scope ytd-channel-name">
          <div id="text-container" class="style-scope ytd-channel-name">
            <yt-formatted-string id="text" link-inherit-color="" respect-lang-dir="" title="${name}" class="style-scope ytd-channel-name complex-string" dir="auto" has-link-only_=""><a class="yt-simple-endpoint style-scope yt-formatted-string" spellcheck="false" href="/@${handle}">${name}</a></yt-formatted-string>
          </div>
        </div>
      </ytd-channel-name>
      <yt-formatted-string id="owner-sub-count" class="style-scope ytd-video-owner-renderer" aria-label="21.4 million subscribers">21.4M subscribers</yt-formatted-string>
    </div>
  </ytd-video-owner-renderer>
  <div id="subscribe-button" class="style-scope ytd-watch-metadata"></div>
</div>`;
}

/** A hidden `ytd-browse`: a channel page YouTube kept alive after the user left it. */
function staleBrowseHtml(handle: string): string {
  return `
<ytd-browse class="style-scope ytd-page-manager" page-subtype="channels" hidden="">
  <ytd-channel-name id="channel-name" class="style-scope ytd-c4-tabbed-header-renderer">
    <div id="container" class="style-scope ytd-channel-name">
      <yt-formatted-string id="text" class="style-scope ytd-channel-name"><a class="yt-simple-endpoint style-scope yt-formatted-string" href="/@${handle}">${handle}</a></yt-formatted-string>
    </div>
  </ytd-channel-name>
  <a class="yt-simple-endpoint style-scope ytd-c4-tabbed-header-renderer" href="/channel/${STALE_BROWSE_CHANNEL_ID}">${handle}</a>
</ytd-browse>`;
}

/** The whole `ytd-app` subtree down to the owner byline, as a string for `innerHTML`. */
export function liveWatchPage(options: LiveWatchOptions = {}): string {
  const {
    videoId = 'liveVideo0001',
    ownerHandle = LIVE_OWNER_HANDLE,
    flexyVideoId = videoId,
    flexyHidden = false,
    staleBrowseHandle = null,
    noOwner = false,
  } = options;

  const flexyAttrs = [
    'class="style-scope ytd-page-manager hide-skeleton"',
    flexyVideoId === null ? '' : `video-id="${flexyVideoId}"`,
    flexyHidden ? 'hidden=""' : '',
  ]
    .filter((a) => a !== '')
    .join(' ');

  const name = ownerHandle === LIVE_OWNER_HANDLE ? LIVE_OWNER_NAME : ownerHandle;

  return `
<ytd-app>
  <div id="content" class="style-scope ytd-app">
    <ytd-page-manager id="page-manager" class="style-scope ytd-app">
      ${staleBrowseHandle === null ? '' : staleBrowseHtml(staleBrowseHandle)}
      <ytd-watch-flexy ${flexyAttrs}>
        <div id="columns" class="style-scope ytd-watch-flexy">
          <div id="primary" class="style-scope ytd-watch-flexy">
            <div id="primary-inner" class="style-scope ytd-watch-flexy">
              <div id="below" class="style-scope ytd-watch-flexy">
                <ytd-watch-metadata class="style-scope ytd-watch-flexy">
                  ${noOwner ? '' : ownerHtml(ownerHandle, name)}
                </ytd-watch-metadata>
              </div>
            </div>
          </div>
          <div id="secondary" class="style-scope ytd-watch-flexy">
            <ytd-watch-next-secondary-results-renderer>
              <ytd-compact-video-renderer>
                <ytd-channel-name><a href="/@${RELATED_HANDLE}">Related</a></ytd-channel-name>
              </ytd-compact-video-renderer>
            </ytd-watch-next-secondary-results-renderer>
          </div>
        </div>
      </ytd-watch-flexy>
    </ytd-page-manager>
  </div>
</ytd-app>`;
}

/**
 * A channel page's inline data, the live `channelMetadataRenderer` shape. `vanityChannelUrl`
 * is the `http://www.youtube.com/@handle` form YouTube really ships.
 */
export function channelPageHtml(
  metadata: { externalId?: string; handle?: string; title?: string } = {},
): string {
  const data = {
    metadata: {
      channelMetadataRenderer: {
        externalId: metadata.externalId ?? LIVE_OWNER_CHANNEL_ID,
        vanityChannelUrl: `http://www.youtube.com/@${metadata.handle ?? LIVE_OWNER_HANDLE}`,
        title: metadata.title ?? LIVE_OWNER_NAME,
      },
    },
  };
  return `<script>var ytInitialData = ${JSON.stringify(data)};</script>`;
}
