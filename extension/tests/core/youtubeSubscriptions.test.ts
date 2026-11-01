import { describe, expect, it } from 'vitest';

import {
  AUTO_SYNC_INTERVAL_MS,
  AUTO_SYNC_RETRY_MS,
  MAX_SUBSCRIBED_CHANNELS,
  applySyncOutcome,
  channelFromRenderer,
  coerceSyncState,
  continuationRequestBody,
  emptySyncState,
  extractInitialData,
  isAutoSyncDue,
  mergeSubscriptionPages,
  parseContinuationResponse,
  parseSubscriptionsPage,
  type SubscriptionSyncState,
} from '../../src/core/youtubeSubscriptions';
import { SAFE_CHANNEL_IDENTIFIER } from '../../src/core/youtubeAccess';
import type { ChannelEntry } from '../../src/core/settingsSchema';
import {
  API_KEY,
  CONTINUATION_TOKEN,
  HEADER_ONLY_ID,
  INNERTUBE_CONTEXT,
  KURZGESAGT_ID,
  NO_HANDLE_ID,
  VERITASIUM_ID,
  channelRenderer,
  continuationResponse,
  defaultChannels,
  initialData,
  manyChannels,
  subscriptionsPageHtml,
} from './fixtures/youtubeSubscriptionsPage';
import {
  CAPTURED_CHANNELS,
  CAPTURED_FEED_CHANNELS_DATA,
  capturedEmptyData,
  sectionListOf,
} from './fixtures/youtubeSubscriptionsCapture';

const NOW = Date.UTC(2026, 10, 1, 12, 0, 0);

function entry(handle: string | null, channelId: string | null = null): ChannelEntry {
  return { channelId, handle, displayName: handle ?? channelId ?? '', addedAt: 0 };
}

function okPage(html: string) {
  const page = parseSubscriptionsPage(html, NOW);
  if (page.kind !== 'ok') throw new Error(`expected ok, got ${page.kind}`);
  return page;
}

describe('parseSubscriptionsPage on the LIVE capture (2026-11-01, sanitized)', () => {
  it('reads exactly the subscribed channels, in page order', () => {
    const page = okPage(subscriptionsPageHtml(CAPTURED_FEED_CHANNELS_DATA));
    expect(page.channels.map(({ channelId, handle, displayName }) => ({ channelId, handle, displayName }))).toEqual(
      CAPTURED_CHANNELS,
    );
  });

  it('does not follow the sort chips: their continuationCommands re-sort the list, they are not "load more"', () => {
    const json = JSON.stringify(CAPTURED_FEED_CHANNELS_DATA);
    // Non-vacuous: the capture really carries continuation tokens, in the header.
    expect(json.match(/"continuationCommand"/g)?.length).toBeGreaterThanOrEqual(3);
    expect(okPage(subscriptionsPageHtml(CAPTURED_FEED_CHANNELS_DATA)).continuation).toBeNull();
  });

  it('a signed-in account with ZERO subscriptions is ok with no channels, not unparseable', () => {
    // Live: the zero-subscription page keeps the chip header and drops the section list's
    // `contents` entirely. It used to read as "Couldn't read your subscriptions".
    const page = okPage(subscriptionsPageHtml(capturedEmptyData()));
    expect(page.channels).toEqual([]);
    expect(page.continuation).toBeNull();
  });

  it('a section list holding something it does not recognise stays unparseable', () => {
    const data = capturedEmptyData();
    sectionListOf(data).contents = [{ somethingNewRenderer: { items: [] } }];
    expect(parseSubscriptionsPage(subscriptionsPageHtml(data), NOW).kind).toBe('unparseable');
  });
});

describe('parseSubscriptionsPage', () => {
  it('reads every subscription with its handle, id and name', () => {
    const page = okPage(subscriptionsPageHtml());
    expect(page.channels).toEqual([
      { channelId: VERITASIUM_ID, handle: 'veritasium', displayName: 'Veritasium', addedAt: NOW },
      {
        channelId: KURZGESAGT_ID,
        handle: 'kurzgesagt',
        displayName: 'Kurzgesagt – In a Nutshell',
        addedAt: NOW,
      },
      { channelId: NO_HANDLE_ID, handle: null, displayName: 'Old Channel Without Handle', addedAt: NOW },
    ]);
  });

  it('returns the continuation token and the page client config for the next request', () => {
    const page = okPage(subscriptionsPageHtml());
    expect(page.continuation).toBe(CONTINUATION_TOKEN);
    expect(page.innertube.apiKey).toBe(API_KEY);
    expect(page.innertube.context).toEqual(INNERTUBE_CONTEXT);
  });

  it('reads the window["ytInitialData"] spelling too', () => {
    expect(okPage(subscriptionsPageHtml(undefined, { spelling: 'window' })).channels).toHaveLength(3);
  });

  it('never reads channels from the page header (they are not subscriptions)', () => {
    const ids = okPage(subscriptionsPageHtml()).channels.map((c) => c.channelId);
    expect(ids).not.toContain(HEADER_ONLY_ID);
  });

  it('is not fooled by a title containing "};</script>" or braces', () => {
    const tricky = channelRenderer({
      channelId: VERITASIUM_ID,
      title: 'Braces {like} this "};</script>',
      canonicalBaseUrl: '/@veritasium',
    });
    const page = okPage(subscriptionsPageHtml(initialData([tricky])));
    expect(page.channels.map((c) => c.displayName)).toEqual(['Braces {like} this "};</script>']);
  });

  it('is signed-out when the page config says LOGGED_IN false', () => {
    expect(parseSubscriptionsPage(subscriptionsPageHtml(undefined, { loggedIn: false }), NOW)).toEqual({
      kind: 'signed-out',
    });
  });

  it('is unparseable without ytInitialData (a consent page, an error page)', () => {
    expect(parseSubscriptionsPage('<html><body>Before you continue to YouTube</body></html>', NOW).kind).toBe(
      'unparseable',
    );
  });

  it('is unparseable when the content holds no channel list at all (a layout it does not know)', () => {
    const page = subscriptionsPageHtml({ contents: { somethingNew: { items: [] } } });
    expect(parseSubscriptionsPage(page, NOW).kind).toBe('unparseable');
  });

  it('is ok with zero channels for a present but EMPTY list (whether that may replace a list is not its call)', () => {
    const page = okPage(subscriptionsPageHtml(initialData([])));
    expect(page.channels).toEqual([]);
    expect(page.continuation).toBeNull();
  });

  it('every handle it returns is safe to put in a DNR regex', () => {
    const odd = [
      channelRenderer({ channelId: 'UCodd000000000000000000a', title: 'Unicode', canonicalBaseUrl: '/@caf%C3%A9' }),
      channelRenderer({ channelId: 'UCodd000000000000000000b', title: 'Pipe', canonicalBaseUrl: '/@a|b' }),
      channelRenderer({ channelId: 'UCodd000000000000000000c', title: 'Dots', canonicalBaseUrl: '/@Some.Name-1_x' }),
    ];
    const page = okPage(subscriptionsPageHtml(initialData(odd)));
    // Unsafe handles are dropped but the channel survives by id; a safe one is lowercased.
    expect(page.channels.map((c) => [c.channelId, c.handle])).toEqual([
      ['UCodd000000000000000000a', null],
      ['UCodd000000000000000000b', null],
      ['UCodd000000000000000000c', 'some.name-1_x'],
    ]);
    for (const c of page.channels) {
      if (c.handle !== null) expect(SAFE_CHANNEL_IDENTIFIER.test(c.handle)).toBe(true);
      if (c.channelId !== null) expect(SAFE_CHANNEL_IDENTIFIER.test(c.channelId)).toBe(true);
    }
  });
});

describe('channelFromRenderer', () => {
  it('drops a renderer with neither a valid id nor a handle', () => {
    expect(channelFromRenderer({ channelId: 'not-an-id', title: { simpleText: 'x' } }, NOW)).toBeNull();
    expect(channelFromRenderer(null, NOW)).toBeNull();
  });

  it('takes the handle from webCommandMetadata.url when canonicalBaseUrl is missing', () => {
    const r = {
      channelId: VERITASIUM_ID,
      title: { simpleText: 'Veritasium' },
      navigationEndpoint: { commandMetadata: { webCommandMetadata: { url: '/@veritasium/videos' } } },
    };
    expect(channelFromRenderer(r, NOW)?.handle).toBe('veritasium');
  });

  it('falls back to a handle printed in subscriberCountText, but never reads a count as one', () => {
    const withHandle = { channelId: VERITASIUM_ID, subscriberCountText: { simpleText: '@veritasium' } };
    const withCount = { channelId: VERITASIUM_ID, subscriberCountText: { simpleText: '17.9M subscribers' } };
    expect(channelFromRenderer(withHandle, NOW)?.handle).toBe('veritasium');
    expect(channelFromRenderer(withCount, NOW)?.handle).toBeNull();
  });

  it('names a title-less channel by its handle, then its id', () => {
    expect(channelFromRenderer({ channelId: VERITASIUM_ID, navigationEndpoint: { browseEndpoint: { canonicalBaseUrl: '/@v' } } }, NOW)?.displayName).toBe('@v');
    expect(channelFromRenderer({ channelId: VERITASIUM_ID }, NOW)?.displayName).toBe(VERITASIUM_ID);
  });
});

describe('parseContinuationResponse', () => {
  it('reads the appended channels and the next token', () => {
    const next = parseContinuationResponse(continuationResponse(manyChannels(2, 100), 'next-token'), NOW);
    expect(next?.channels.map((c) => c.handle)).toEqual(['creator.name_100', 'creator.name_101']);
    expect(next?.continuation).toBe('next-token');
  });

  it('is null when the response carries no channel (YouTube declining, not "the end")', () => {
    expect(parseContinuationResponse({ responseContext: {} }, NOW)).toBeNull();
    expect(parseContinuationResponse(continuationResponse([]), NOW)).toBeNull();
    expect(parseContinuationResponse('nope', NOW)).toBeNull();
  });
});

describe('continuationRequestBody', () => {
  it("sends the page's own client context and the token, nothing else", () => {
    const { innertube } = okPage(subscriptionsPageHtml());
    expect(continuationRequestBody(innertube, 'tok')).toEqual({ context: INNERTUBE_CONTEXT, continuation: 'tok' });
  });

  it('builds a minimal WEB context when the page had none', () => {
    const body = continuationRequestBody({ apiKey: null, clientVersion: '2.1', context: null }, 'tok');
    expect(body).toEqual({ context: { client: { clientName: 'WEB', clientVersion: '2.1' } }, continuation: 'tok' });
  });
});

describe('extractInitialData', () => {
  it('is null for unbalanced or invalid JSON rather than throwing', () => {
    expect(extractInitialData('var ytInitialData = {"a": {')).toBeNull();
    expect(extractInitialData('var ytInitialData = {not json};')).toBeNull();
  });
});

describe('mergeSubscriptionPages', () => {
  it('de-duplicates across pages by either identifier, keeping page order', () => {
    const merged = mergeSubscriptionPages([
      [entry('a', 'UCa00000000000000000000a'), entry('b')],
      [entry('a-renamed', 'UCa00000000000000000000a'), entry('b', 'UCb00000000000000000000b'), entry('c')],
    ]);
    expect(merged.channels.map((c) => c.handle)).toEqual(['a', 'b', 'c']);
    expect(merged.capped).toBe(false);
  });

  it(`caps at ${MAX_SUBSCRIBED_CHANNELS} and says so`, () => {
    const page = okPage(subscriptionsPageHtml(initialData(manyChannels(MAX_SUBSCRIBED_CHANNELS + 20))));
    const merged = mergeSubscriptionPages([page.channels]);
    expect(merged.channels).toHaveLength(MAX_SUBSCRIBED_CHANNELS);
    expect(merged.capped).toBe(true);
  });

  it('exactly at the cap is not capped', () => {
    const page = okPage(subscriptionsPageHtml(initialData(manyChannels(MAX_SUBSCRIBED_CHANNELS))));
    expect(mergeSubscriptionPages([page.channels]).capped).toBe(false);
  });
});

describe('applySyncOutcome: a failure never wipes the list', () => {
  const stored: SubscriptionSyncState = {
    channels: [entry('veritasium', VERITASIUM_ID)],
    syncedAt: NOW - 1000,
    lastAttemptAt: NOW - 1000,
    error: null,
    truncated: null,
  };

  it.each(['signed-out', 'unparseable', 'network'] as const)('%s keeps the channels and records the error', (error) => {
    const next = applySyncOutcome(stored, { ok: false, error }, NOW);
    expect(next.channels).toEqual(stored.channels);
    expect(next.syncedAt).toBe(stored.syncedAt);
    expect(next.lastAttemptAt).toBe(NOW);
    expect(next.error).toBe(error);
  });

  it('zero channels while a list is stored keeps the list (a rotted parser must not drop everything)', () => {
    const next = applySyncOutcome(stored, { ok: true, channels: [], truncated: null }, NOW);
    expect(next.channels).toEqual(stored.channels);
    expect(next.error).toBe('empty');
  });

  it('zero channels with nothing stored is a successful empty sync', () => {
    const next = applySyncOutcome(emptySyncState(), { ok: true, channels: [], truncated: null }, NOW);
    expect(next).toEqual({ channels: [], syncedAt: NOW, lastAttemptAt: NOW, error: null, truncated: null });
  });

  it('a success replaces the list, clears the error and records truncation', () => {
    const failed = { ...stored, error: 'network' as const };
    const channels = [entry('kurzgesagt', KURZGESAGT_ID)];
    const next = applySyncOutcome(failed, { ok: true, channels, truncated: 'partial' }, NOW);
    expect(next).toEqual({ channels, syncedAt: NOW, lastAttemptAt: NOW, error: null, truncated: 'partial' });
  });
});

describe('isAutoSyncDue', () => {
  const synced = (syncedAt: number | null, lastAttemptAt: number | null = syncedAt): SubscriptionSyncState => ({
    ...emptySyncState(),
    syncedAt,
    lastAttemptAt,
  });

  it('is due when never synced', () => {
    expect(isAutoSyncDue(emptySyncState(), NOW)).toBe(true);
  });

  it('is not due within a day of a success, due after', () => {
    expect(isAutoSyncDue(synced(NOW - AUTO_SYNC_INTERVAL_MS + 60_000), NOW)).toBe(false);
    expect(isAutoSyncDue(synced(NOW - AUTO_SYNC_INTERVAL_MS), NOW)).toBe(true);
  });

  it('after a failed attempt, waits the retry interval even when never synced (no fetch per page view)', () => {
    expect(isAutoSyncDue(synced(null, NOW - 60_000), NOW)).toBe(false);
    expect(isAutoSyncDue(synced(null, NOW - AUTO_SYNC_RETRY_MS), NOW)).toBe(true);
  });
});

describe('coerceSyncState: the stored state is repaired on read', () => {
  it('anything unrecognisable is an empty state', () => {
    for (const bad of [undefined, null, 42, 'x', []]) expect(coerceSyncState(bad)).toEqual(emptySyncState());
  });

  it('drops entries with unsafe or invalid identifiers and de-duplicates the rest', () => {
    const state = coerceSyncState({
      channels: [
        { channelId: VERITASIUM_ID, handle: 'Veritasium', displayName: 'Veritasium', addedAt: 5 },
        { channelId: VERITASIUM_ID, handle: null, displayName: 'dup', addedAt: 6 },
        { channelId: 'nope', handle: '.*', displayName: 'evil', addedAt: 7 },
        { channelId: null, handle: 'ok', displayName: '', addedAt: 'x' },
        'garbage',
      ],
      syncedAt: NOW,
      lastAttemptAt: 'yesterday',
      error: 'something-else',
      truncated: 'cap',
    });
    expect(state.channels).toEqual([
      { channelId: VERITASIUM_ID, handle: 'veritasium', displayName: 'Veritasium', addedAt: 5 },
      { channelId: null, handle: 'ok', displayName: '@ok', addedAt: 0 },
    ]);
    expect(state.syncedAt).toBe(NOW);
    expect(state.lastAttemptAt).toBeNull();
    expect(state.error).toBeNull();
    expect(state.truncated).toBe('cap');
  });

  it('round-trips a real state unchanged', () => {
    const page = okPage(subscriptionsPageHtml(initialData(defaultChannels())));
    const state = applySyncOutcome(emptySyncState(), { ok: true, channels: page.channels, truncated: null }, NOW);
    expect(coerceSyncState(JSON.parse(JSON.stringify(state)))).toEqual(state);
  });
});
