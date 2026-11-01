/**
 * `https://www.youtube.com/feed/channels`, as the worker receives it.
 *
 * UNVERIFIED AGAINST A SIGNED-IN CAPTURE. No signed-in session was available when this
 * shipped, so the shape is built from the documented renderer layout that yt-dlp and
 * Invidious parse (`twoColumnBrowseResultsRenderer > tabs > tabRenderer > content >
 * sectionListRenderer > itemSectionRenderer > shelfRenderer > expandedShelfContentsRenderer
 * > items[] > channelRenderer`, continuation via `continuationItemRenderer`). The signed-out
 * case IS from a real request (2026-11-01): YouTube redirects to accounts.google.com.
 * Replace with a live capture once one exists (PR QA list).
 */

export const VERITASIUM_ID = 'UCHnyfMqiRRG1u-2MsSQLbXA';
export const KURZGESAGT_ID = 'UCsXVk37bltHxD1rDPwtNM8Q';
/** A subscription with no handle: listed by id, its link is `/channel/UC…`. */
export const NO_HANDLE_ID = 'UCabcdefghijklmnopqrstuv';
/** Named only in the page HEADER, which is not the subscription list. */
export const HEADER_ONLY_ID = 'UCheaderheaderheaderhead';
export const CONTINUATION_TOKEN = '4qmFsgKrCBIYRkVjaGFubmVsc19mZWVkX2xvYWRfbW9yZQ%3D%3D';
export const API_KEY = 'AIzaSyFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAK';

type Renderer = Record<string, unknown>;

export function channelRenderer(options: {
  channelId: string;
  title: string;
  /** `/@handle`, `/channel/UC…`, or omitted entirely. */
  canonicalBaseUrl?: string;
  /** Use `title.runs` instead of `title.simpleText`. */
  runs?: boolean;
  subscriberCountText?: string;
}): Renderer {
  const url = options.canonicalBaseUrl ?? `/channel/${options.channelId}`;
  return {
    channelRenderer: {
      channelId: options.channelId,
      title: options.runs ? { runs: [{ text: options.title }] } : { simpleText: options.title },
      navigationEndpoint: {
        clickTrackingParams: 'CAEQ',
        commandMetadata: { webCommandMetadata: { url, webPageType: 'WEB_PAGE_TYPE_CHANNEL' } },
        browseEndpoint: {
          browseId: options.channelId,
          ...(options.canonicalBaseUrl !== undefined ? { canonicalBaseUrl: options.canonicalBaseUrl } : {}),
        },
      },
      subscriberCountText: { simpleText: options.subscriberCountText ?? '1.2M subscribers' },
      videoCountText: { runs: [{ text: '412' }, { text: ' videos' }] },
      subscriptionButton: { subscribed: true },
    },
  };
}

export function continuationItem(token: string): Renderer {
  return {
    continuationItemRenderer: {
      trigger: 'CONTINUATION_TRIGGER_ON_ITEM_SHOWN',
      continuationEndpoint: {
        clickTrackingParams: 'CBQQ',
        commandMetadata: { webCommandMetadata: { sendPost: true, apiUrl: '/youtubei/v1/browse' } },
        continuationCommand: { token, request: 'CONTINUATION_REQUEST_TYPE_BROWSE' },
      },
    },
  };
}

/** The default first page: two handled channels, one id-only, one unsafe handle. */
export function defaultChannels(): Renderer[] {
  return [
    channelRenderer({ channelId: VERITASIUM_ID, title: 'Veritasium', canonicalBaseUrl: '/@veritasium' }),
    channelRenderer({
      channelId: KURZGESAGT_ID,
      title: 'Kurzgesagt – In a Nutshell',
      canonicalBaseUrl: '/@kurzgesagt',
      runs: true,
    }),
    channelRenderer({ channelId: NO_HANDLE_ID, title: 'Old Channel Without Handle' }),
  ];
}

export function initialData(items: Renderer[], options: { continuation?: string } = {}): Renderer {
  const listItems = options.continuation !== undefined ? [...items, continuationItem(options.continuation)] : items;
  return {
    responseContext: { serviceTrackingParams: [] },
    contents: {
      twoColumnBrowseResultsRenderer: {
        tabs: [
          {
            tabRenderer: {
              selected: true,
              content: {
                sectionListRenderer: {
                  contents: [
                    {
                      itemSectionRenderer: {
                        contents: [
                          {
                            shelfRenderer: {
                              title: { runs: [{ text: 'A-Z' }] },
                              content: { expandedShelfContentsRenderer: { items: listItems } },
                            },
                          },
                        ],
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
    // The header names a channel that is NOT a subscription; the parser must not read it.
    header: {
      feedTabbedHeaderRenderer: {
        title: { runs: [{ text: 'All subscriptions' }] },
        promoted: channelRenderer({ channelId: HEADER_ONLY_ID, title: 'Promoted', canonicalBaseUrl: '/@promoted' }),
      },
    },
  };
}

const INNERTUBE_CONTEXT = {
  client: { hl: 'en', gl: 'AU', clientName: 'WEB', clientVersion: '2.20261030.01.00' },
  user: { lockedSafetyMode: false },
};

export function ytcfg(loggedIn: boolean): string {
  return `ytcfg.set({"INNERTUBE_API_KEY":"${API_KEY}","INNERTUBE_CLIENT_VERSION":"2.20261030.01.00","INNERTUBE_CONTEXT":${JSON.stringify(
    INNERTUBE_CONTEXT,
  )},"LOGGED_IN":${loggedIn},"PAGE_CL":123456});`;
}

export { INNERTUBE_CONTEXT };

/** A whole page, with the data assigned the way YouTube's server-rendered HTML does. */
export function subscriptionsPageHtml(
  data: Renderer = initialData(defaultChannels(), { continuation: CONTINUATION_TOKEN }),
  options: { loggedIn?: boolean; spelling?: 'var' | 'window' } = {},
): string {
  const assign =
    options.spelling === 'window' ? 'window["ytInitialData"] = ' : 'var ytInitialData = ';
  return [
    '<!DOCTYPE html><html><head><title>Subscriptions - YouTube</title>',
    `<script nonce="x">${ytcfg(options.loggedIn ?? true)}</script>`,
    '</head><body>',
    // A title containing the characters a regex-based extractor trips on.
    `<script nonce="x">${assign}${JSON.stringify(data)};</script>`,
    '<script nonce="x">if (window.ytcsi) {window.ytcsi.tick("pdr", null, \'\');}</script>',
    '</body></html>',
  ].join('');
}

/** What `/youtubei/v1/browse` returns for a continuation token. */
export function continuationResponse(items: Renderer[], nextToken?: string): Renderer {
  const continuationItems = nextToken !== undefined ? [...items, continuationItem(nextToken)] : items;
  return {
    responseContext: { visitorData: 'Cgt4' },
    onResponseReceivedActions: [
      {
        clickTrackingParams: 'CAAQ',
        appendContinuationItemsAction: { targetId: 'browse-feedFEchannels', continuationItems },
      },
    ],
  };
}

/** N realistic distinct subscriptions, for cap and DNR-budget tests. */
export function manyChannels(count: number, offset = 0): Renderer[] {
  return Array.from({ length: count }, (_, i) => {
    const n = i + offset;
    const id = `UC${String(n).padStart(22, '0')}`;
    return channelRenderer({
      channelId: id,
      title: `Channel ${n}`,
      canonicalBaseUrl: `/@creator.name_${n}`,
    });
  });
}
