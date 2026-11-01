/**
 * "Also allow channels I'm subscribed to": parsing YouTube's subscriptions page and the
 * rules for keeping a synced copy. PURE: no chrome.*, no network, no DOM.
 *
 * The worker (`background/youtubeSubscriptions.ts`) fetches
 * `https://www.youtube.com/feed/channels` in the user's OWN signed-in session (the existing
 * `<all_urls>` host permission, no new permission) and hands the HTML here. Nothing about
 * the list ever leaves the browser: it is stored in `storage.local` and read only by the
 * channel-list code that already decides "may this channel play".
 *
 * WHY THE PARSER WALKS RATHER THAN FOLLOWING ONE PATH. The page's `ytInitialData` has
 * carried the list as `...sectionListRenderer > itemSectionRenderer > shelfRenderer >
 * expandedShelfContentsRenderer > items[] > channelRenderer` for years, but the WRAPPERS are
 * what YouTube reshuffles (grid vs shelf vs rich grid), while the per-channel renderer is
 * stable. So the parser finds every channel renderer under the page's `contents` (never the
 * header or the guide, which name channels that are not subscriptions) and reads each one
 * defensively. A layout it does not recognise yields ZERO channels, and zero channels is
 * never allowed to replace a non-empty list (`applySyncOutcome`): a rotted parser must keep
 * the last good list, not silently allow nothing new and drop everything old.
 *
 * WHAT IS VERIFIED. The renderer shape (`channelId`, `title`, `navigationEndpoint.
 * browseEndpoint.canonicalBaseUrl`) is the documented one used by yt-dlp and friends; the
 * fixture in `tests/core/fixtures/` is built from that shape, not from a live signed-in
 * capture (no signed-in session was available when this shipped). The live check is in the
 * PR's QA list.
 */

import { SAFE_CHANNEL_IDENTIFIER } from './youtubeAccess';
import type { ChannelEntry } from './settingsSchema';

/** The page the worker fetches. Its own session's subscriptions, A-Z. */
export const SUBSCRIPTIONS_PAGE_URL = 'https://www.youtube.com/feed/channels';
/** Where the page's own "load more" posts its continuation token. */
export const BROWSE_ENDPOINT_URL = 'https://www.youtube.com/youtubei/v1/browse?prettyPrint=false';

/**
 * The most subscribed channels Nudge will allow.
 *
 * A hard ceiling, because every allowed channel becomes part of a DNR regex and Chrome caps
 * an extension at 1000 regex rules (`MAX_NUMBER_OF_REGEX_RULES`, measured live), rejecting
 * the WHOLE rule update past it, which would leave yesterday's rules installed for every
 * site. `youtubeWhitelistPaths` packs several channels per rule; at this cap the channel
 * carve-out stays far below the limit (pinned by tests/core/youtubeSubscriptions.test.ts).
 */
export const MAX_SUBSCRIBED_CHANNELS = 500;

/** Continuation pages to follow before stopping. A guard against a token loop. */
export const MAX_CONTINUATION_PAGES = 25;

/** An automatic sync runs at most this often (on a youtube.com visit). */
export const AUTO_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000;
/** After a failed attempt, an automatic retry waits at least this long. */
export const AUTO_SYNC_RETRY_MS = 60 * 60 * 1000;

const CHANNEL_ID = /^UC[A-Za-z0-9_-]{22}$/;
const CHANNEL_RENDERER_KEYS = ['channelRenderer', 'gridChannelRenderer'] as const;

/** What the page's own client config says, needed to ask for the next page. */
export interface InnertubeConfig {
  apiKey: string | null;
  clientVersion: string | null;
  context: Record<string, unknown> | null;
}

export type SubscriptionsPage =
  | { kind: 'signed-out' }
  | { kind: 'unparseable' }
  | {
      kind: 'ok';
      channels: ChannelEntry[];
      continuation: string | null;
      innertube: InnertubeConfig;
    };

/** Why the last sync did not produce a list. */
export type SyncError = 'signed-out' | 'unparseable' | 'network' | 'empty';

/** Why the stored list may be shorter than the user's real subscriptions. */
export type SyncTruncation = 'cap' | 'partial';

/** What the worker keeps in `storage.local`. */
export interface SubscriptionSyncState {
  channels: ChannelEntry[];
  /** When `channels` was last replaced by a successful sync. */
  syncedAt: number | null;
  /** When a sync was last attempted, successful or not. */
  lastAttemptAt: number | null;
  /** The last attempt's failure, or null when it succeeded. */
  error: SyncError | null;
  truncated: SyncTruncation | null;
}

/** What one sync attempt produced, before it is folded into the stored state. */
export type SyncOutcome =
  | { ok: true; channels: ChannelEntry[]; truncated: SyncTruncation | null }
  | { ok: false; error: SyncError };

export function emptySyncState(): SubscriptionSyncState {
  return { channels: [], syncedAt: null, lastAttemptAt: null, error: null, truncated: null };
}

// ---------------------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------------------

/**
 * The JSON object literal starting at `start` (which must be a `{`), by brace matching.
 *
 * A regex up to `};</script>` breaks the moment YouTube minifies differently or a title
 * contains that text; counting braces outside of strings does not.
 */
function objectLiteralAt(text: string, start: number): string | null {
  if (text[start] !== '{') return null;
  let depth = 0;
  let inString = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      if (ch === '\\') i++;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }
  return null;
}

function parseObjectAfter(text: string, marker: RegExp): Record<string, unknown> | null {
  const match = marker.exec(text);
  if (match === null) return null;
  const start = text.indexOf('{', match.index + match[0].length - 1);
  if (start === -1) return null;
  const literal = objectLiteralAt(text, start);
  if (literal === null) return null;
  try {
    const parsed: unknown = JSON.parse(literal);
    return parsed !== null && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** The page's `ytInitialData`, or null. Both assignment spellings YouTube uses. */
export function extractInitialData(html: string): Record<string, unknown> | null {
  return parseObjectAfter(html, /(?:var\s+ytInitialData|window\[["']ytInitialData["']\])\s*=\s*\{/);
}

function stringField(html: string, name: string): string | null {
  const match = new RegExp(`"${name}"\\s*:\\s*"([^"\\\\]*)"`).exec(html);
  return match?.[1] ?? null;
}

/** `true`/`false` when the page's ytcfg says, null when it says nothing. */
export function extractLoggedIn(html: string): boolean | null {
  const match = /"LOGGED_IN"\s*:\s*(true|false)/.exec(html);
  return match === null ? null : match[1] === 'true';
}

export function extractInnertubeConfig(html: string): InnertubeConfig {
  return {
    apiKey: stringField(html, 'INNERTUBE_API_KEY'),
    clientVersion: stringField(html, 'INNERTUBE_CLIENT_VERSION'),
    context: parseObjectAfter(html, /"INNERTUBE_CONTEXT"\s*:\s*\{/),
  };
}

function textOf(value: unknown): string | null {
  if (value === null || typeof value !== 'object') return null;
  const node = value as { simpleText?: unknown; runs?: unknown; content?: unknown };
  if (typeof node.simpleText === 'string') return node.simpleText;
  if (typeof node.content === 'string') return node.content;
  if (Array.isArray(node.runs)) {
    const joined = node.runs
      .map((run) => (run && typeof (run as { text?: unknown }).text === 'string' ? (run as { text: string }).text : ''))
      .join('');
    return joined === '' ? null : joined;
  }
  return null;
}

/** A handle as the list stores it (lowercase, no `@`), or null when it is not safe. */
function cleanHandle(raw: string | null): string | null {
  if (raw === null) return null;
  let handle = raw.trim();
  if (!handle.startsWith('@')) return null;
  try {
    handle = decodeURIComponent(handle.slice(1));
  } catch {
    return null;
  }
  handle = handle.toLowerCase();
  // These identifiers end up inside DNR regexes. Anything outside the safe set is dropped
  // here, at the boundary, rather than trusted downstream (`youtubeAccess`).
  return SAFE_CHANNEL_IDENTIFIER.test(handle) ? handle : null;
}

/** `/@handle` or `/@handle/videos` -> the handle; a `/channel/UC…` link has none. */
function handleFromPath(path: string): string | null {
  if (!path.startsWith('/@')) return null;
  return cleanHandle(path.slice(1).split(/[/?#]/)[0] ?? '');
}

/**
 * One channel renderer -> a list entry, or null when it names no usable channel.
 *
 * Read the way yt-dlp reads it (`_extract_channel_renderer`): the id from `channelId`
 * (or the browse endpoint's `browseId`), the handle from the renderer's own link
 * (`canonicalBaseUrl`, then `webCommandMetadata.url`), the title from `simpleText`/`runs`.
 * A channel with no handle keeps a `/channel/UC…` link and is listed by id alone.
 */
export function channelFromRenderer(renderer: unknown, addedAt: number): ChannelEntry | null {
  if (renderer === null || typeof renderer !== 'object') return null;
  const r = renderer as Record<string, unknown>;
  const browse = (r.navigationEndpoint as { browseEndpoint?: Record<string, unknown> } | undefined)
    ?.browseEndpoint;

  const rawId =
    typeof r.channelId === 'string'
      ? r.channelId
      : typeof browse?.browseId === 'string'
        ? browse.browseId
        : null;
  const channelId = rawId !== null && CHANNEL_ID.test(rawId) ? rawId : null;

  const webUrl = (
    r.navigationEndpoint as { commandMetadata?: { webCommandMetadata?: { url?: unknown } } } | undefined
  )?.commandMetadata?.webCommandMetadata?.url;
  const links = [browse?.canonicalBaseUrl, webUrl].filter((v): v is string => typeof v === 'string');
  const fromLink =
    links.map(handleFromPath).find((h): h is string => h !== null) ?? null;
  // Last resort only. On `/feed/channels` YouTube prints the handle ("@veritasium") in this
  // field and the subscriber count in `videoCountText` (live capture, 2026-11-01), but the
  // link above is the stable source; a real count here (other layouts) has no `@` and
  // `cleanHandle` rejects it.
  const handle = fromLink ?? cleanHandle(textOf(r.subscriberCountText));

  if (channelId === null && handle === null) return null;
  const title = textOf(r.title)?.trim() ?? '';
  return {
    channelId,
    handle,
    displayName: title !== '' ? title : handle !== null ? `@${handle}` : (channelId as string),
    addedAt,
  };
}

/** Every channel renderer and the first continuation token under `root`, in page order. */
function walkForChannels(root: unknown, addedAt: number): {
  channels: ChannelEntry[];
  continuation: string | null;
  sawList: boolean;
} {
  const channels: ChannelEntry[] = [];
  let continuation: string | null = null;
  let sawList = false;

  const visit = (node: unknown, depth: number): void => {
    if (node === null || typeof node !== 'object' || depth > 60) return;
    if (Array.isArray(node)) {
      for (const item of node) visit(item, depth + 1);
      return;
    }
    const obj = node as Record<string, unknown>;
    for (const key of CHANNEL_RENDERER_KEYS) {
      if (key in obj) {
        sawList = true;
        const entry = channelFromRenderer(obj[key], addedAt);
        if (entry !== null) channels.push(entry);
      }
    }
    const token = (
      (obj.continuationItemRenderer as Record<string, unknown> | undefined)?.continuationEndpoint as
        | { continuationCommand?: { token?: unknown } }
        | undefined
    )?.continuationCommand?.token;
    if (continuation === null && typeof token === 'string' && token !== '') continuation = token;
    // A list container that is present but empty still proves we read the right page.
    if ('expandedShelfContentsRenderer' in obj || 'gridRenderer' in obj) sawList = true;

    for (const [key, value] of Object.entries(obj)) {
      if (CHANNEL_RENDERER_KEYS.includes(key as (typeof CHANNEL_RENDERER_KEYS)[number])) continue;
      visit(value, depth + 1);
    }
  };
  visit(root, 0);
  return { channels, continuation, sawList };
}

/**
 * Parse the HTML of `/feed/channels`.
 *
 * `signed-out` when the page's own config says so (YouTube serves the page signed out with
 * a sign-in prompt instead of a list). `unparseable` when there is no `ytInitialData`, or a
 * signed-in page whose content holds no channel list at all. A signed-in page with an EMPTY
 * list container is `ok` with zero channels; whether zero may replace a stored list is
 * `applySyncOutcome`'s call, not the parser's.
 */
export function parseSubscriptionsPage(html: string, now: number): SubscriptionsPage {
  if (extractLoggedIn(html) === false) return { kind: 'signed-out' };
  const data = extractInitialData(html);
  if (data === null) return { kind: 'unparseable' };
  // Only the page's main content: the header and the guide name channels that are not
  // necessarily subscriptions.
  const { channels, continuation, sawList } = walkForChannels(data.contents, now);
  if (!sawList && channels.length === 0 && !isEmptyFeed(data.contents)) return { kind: 'unparseable' };
  return { kind: 'ok', channels, continuation, innertube: extractInnertubeConfig(html) };
}

/**
 * Whether `contents` is the feed page with NOTHING in it: a tab whose `sectionListRenderer`
 * has no `contents` at all (or an empty array).
 *
 * That is how YouTube serves `/feed/channels` to a signed-in account with zero subscriptions
 * (live capture, 2026-11-01): the sort-chip header is there, the list container is not, so
 * the "an empty list container proves the right page" signal in `walkForChannels` never
 * fires. Without this the user saw "Couldn't read your subscriptions" instead of 0 channels.
 * A section list that DOES hold something unrecognised stays `unparseable`: only a provably
 * empty feed counts as an answer.
 */
function isEmptyFeed(contents: unknown): boolean {
  const tabs = (contents as { twoColumnBrowseResultsRenderer?: { tabs?: unknown } } | undefined)
    ?.twoColumnBrowseResultsRenderer?.tabs;
  if (!Array.isArray(tabs)) return false;
  return tabs.some((tab) => {
    const sectionList = (
      tab as { tabRenderer?: { content?: { sectionListRenderer?: { contents?: unknown } } } } | null
    )?.tabRenderer?.content?.sectionListRenderer;
    if (sectionList === undefined || sectionList === null || typeof sectionList !== 'object') return false;
    const items = sectionList.contents;
    return items === undefined || (Array.isArray(items) && items.length === 0);
  });
}

/**
 * Parse one continuation response (the JSON `/youtubei/v1/browse` returns for a token).
 * Null when it carries no channel at all, which the caller treats as "the rest of the list
 * is not available" rather than as "there is no rest".
 */
export function parseContinuationResponse(
  json: unknown,
  now: number,
): { channels: ChannelEntry[]; continuation: string | null } | null {
  if (json === null || typeof json !== 'object') return null;
  const obj = json as Record<string, unknown>;
  const { channels, continuation } = walkForChannels(
    obj.onResponseReceivedActions ?? obj.onResponseReceivedEndpoints ?? obj.continuationContents,
    now,
  );
  return channels.length === 0 ? null : { channels, continuation };
}

/** The request body YouTube's own client sends for the next page. */
export function continuationRequestBody(
  innertube: InnertubeConfig,
  token: string,
): Record<string, unknown> {
  const context = innertube.context ?? {
    client: { clientName: 'WEB', clientVersion: innertube.clientVersion ?? '2.20260101.00.00' },
  };
  return { context, continuation: token };
}

// ---------------------------------------------------------------------------------------
// Sync state
// ---------------------------------------------------------------------------------------

/** De-duplicate (by either identifier) and cap, preserving page order. */
export function mergeSubscriptionPages(
  pages: readonly (readonly ChannelEntry[])[],
): { channels: ChannelEntry[]; capped: boolean } {
  const out: ChannelEntry[] = [];
  const ids = new Set<string>();
  const handles = new Set<string>();
  for (const page of pages) {
    for (const entry of page) {
      if ((entry.channelId !== null && ids.has(entry.channelId)) || (entry.handle !== null && handles.has(entry.handle))) {
        continue;
      }
      if (out.length >= MAX_SUBSCRIBED_CHANNELS) return { channels: out, capped: true };
      out.push(entry);
      if (entry.channelId !== null) ids.add(entry.channelId);
      if (entry.handle !== null) handles.add(entry.handle);
    }
  }
  return { channels: out, capped: false };
}

/**
 * Fold one attempt into the stored state.
 *
 * THE RULE: a failure never wipes the list. Signed out, a parse failure, a network error,
 * and a result of zero channels while a list is stored all keep the last good channels and
 * only record the error, because the one thing worse than a stale list of allowed channels
 * is a sync hiccup that silently blocks every one of them. (A user who really unsubscribed
 * from everything can turn the option off.)
 */
export function applySyncOutcome(
  previous: SubscriptionSyncState,
  outcome: SyncOutcome,
  now: number,
): SubscriptionSyncState {
  if (!outcome.ok) return { ...previous, lastAttemptAt: now, error: outcome.error };
  if (outcome.channels.length === 0 && previous.channels.length > 0) {
    return { ...previous, lastAttemptAt: now, error: 'empty' };
  }
  return {
    channels: outcome.channels,
    syncedAt: now,
    lastAttemptAt: now,
    error: null,
    truncated: outcome.truncated,
  };
}

/**
 * Whether a youtube.com visit should trigger a background sync.
 *
 * At most once per `AUTO_SYNC_INTERVAL_MS` after a success, and never more than once per
 * `AUTO_SYNC_RETRY_MS` after a failure, so a signed-out user is not fetched on every page.
 */
export function isAutoSyncDue(state: SubscriptionSyncState, now: number): boolean {
  if (state.lastAttemptAt !== null && now - state.lastAttemptAt < AUTO_SYNC_RETRY_MS) return false;
  if (state.syncedAt === null) return true;
  return now - state.syncedAt >= AUTO_SYNC_INTERVAL_MS;
}

const SYNC_ERRORS: readonly SyncError[] = ['signed-out', 'unparseable', 'network', 'empty'];
const TRUNCATIONS: readonly SyncTruncation[] = ['cap', 'partial'];

function coerceTime(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function coerceStoredEntry(value: unknown): ChannelEntry | null {
  if (value === null || typeof value !== 'object') return null;
  const raw = value as Partial<ChannelEntry>;
  const channelId = typeof raw.channelId === 'string' && CHANNEL_ID.test(raw.channelId) ? raw.channelId : null;
  const handle =
    typeof raw.handle === 'string' && SAFE_CHANNEL_IDENTIFIER.test(raw.handle) ? raw.handle.toLowerCase() : null;
  if (channelId === null && handle === null) return null;
  return {
    channelId,
    handle,
    displayName:
      typeof raw.displayName === 'string' && raw.displayName.trim() !== ''
        ? raw.displayName
        : handle !== null ? `@${handle}` : (channelId as string),
    addedAt: coerceTime(raw.addedAt) ?? 0,
  };
}

/** The stored state, repaired on read. Anything unrecognisable is an empty state. */
export function coerceSyncState(value: unknown): SubscriptionSyncState {
  if (value === null || typeof value !== 'object') return emptySyncState();
  const raw = value as Partial<Record<keyof SubscriptionSyncState, unknown>>;
  const channels = Array.isArray(raw.channels)
    ? raw.channels.map(coerceStoredEntry).filter((c): c is ChannelEntry => c !== null)
    : [];
  return {
    channels: mergeSubscriptionPages([channels]).channels,
    syncedAt: coerceTime(raw.syncedAt),
    lastAttemptAt: coerceTime(raw.lastAttemptAt),
    error: SYNC_ERRORS.includes(raw.error as SyncError) ? (raw.error as SyncError) : null,
    truncated: TRUNCATIONS.includes(raw.truncated as SyncTruncation) ? (raw.truncated as SyncTruncation) : null,
  };
}
