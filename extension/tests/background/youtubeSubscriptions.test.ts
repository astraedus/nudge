import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  fetchSubscriptions,
  maybeAutoSync,
  resetSubscriptionSyncForTests,
  subscriptionStatus,
  syncSubscriptions,
  type Fetcher,
} from '../../src/background/youtubeSubscriptions';
import type { BrowsePage, BrowseResult, SignedBrowser } from '../../src/background/youtubeTabBrowse';
import { handleRequest } from '../../src/background/messagesRouter';
import { handleSignedBrowse } from '../../src/content/signedBrowse';
import { YOUTUBE_ORIGIN, type SignedBrowseRequest } from '../../src/core/signedBrowse';
import {
  YT_SUBSCRIPTIONS_KEY,
  loadSettings,
  loadSubscriptionState,
  saveSettings,
  saveSubscriptionState,
} from '../../src/background/storage';
import { compileRules } from '../../src/background/dnr';
import { effectiveChannelLists } from '../../src/core/youtubeAccess';
import {
  AUTO_SYNC_INTERVAL_MS,
  BROWSE_ENDPOINT_URL,
  MAX_CONTINUATION_PAGES,
  MAX_SUBSCRIBED_CHANNELS,
  PENDING_RETRY_MS,
  SUBSCRIPTIONS_PAGE_URL,
  applySyncOutcome,
  emptySyncState,
} from '../../src/core/youtubeSubscriptions';
import type { SaveResult } from '../../src/core/protocol';
import type { ChannelEntry, NudgeSettings } from '../../src/core/settingsSchema';
import {
  CONTINUATION_TOKEN,
  INNERTUBE_CONTEXT,
  KURZGESAGT_ID,
  USER_SESSION_ID,
  VERITASIUM_ID,
  continuationResponse,
  defaultChannels,
  initialData,
  manyChannels,
  subscriptionsPageHtml,
} from '../core/fixtures/youtubeSubscriptionsPage';
import { featuresWith, settings, siteRule } from '../helpers/rules';
import { installAttention, installDnr, resetBrowser } from './fakeApis';

/**
 * The worker side of "Also allow channels I'm subscribed to": the first-page fetch, the
 * signed continuation loop (through a youtube.com tab), the folding into storage, and the
 * boundary that keeps the list the WORKER's. The network is a fake `Fetcher` and the tab a
 * fake `SignedBrowser` throughout; `fetch` itself is stubbed so a test that reaches the real
 * default fails loudly instead of touching youtube.com.
 */

const NOW = Date.UTC(2026, 10, 1, 12, 0, 0);
const NOW_DATE = new Date(NOW);

interface Call {
  url: string;
  init: RequestInit;
}

function response(options: { url?: string; ok?: boolean; html?: string; json?: unknown }): Response {
  return {
    ok: options.ok ?? true,
    url: options.url ?? SUBSCRIPTIONS_PAGE_URL,
    text: async () => options.html ?? '',
    json: async () => options.json ?? {},
  } as unknown as Response;
}

const ok = (json: unknown): BrowseResult => ({ kind: 'ok', json });
const REFUSED: BrowseResult = { kind: 'refused' };
const NO_TAB: BrowseResult = { kind: 'no-tab' };

/**
 * A fake youtube.com: the worker's own network answers the first page (and must never be
 * asked twice), and a fake tab answers each signed continuation in turn.
 */
function fakeYouTube(page: Response | Error, continuations: (BrowseResult | Error)[] = []) {
  const calls: Call[] = [];
  const asked: BrowsePage[] = [];
  const queue = [...continuations];
  const fetcher: Fetcher = async (url, init) => {
    calls.push({ url, init });
    if (calls.length > 1) throw new Error('the worker made a second network request');
    if (page instanceof Error) throw page;
    return page;
  };
  const browse: SignedBrowser = async (request) => {
    asked.push(request);
    const next = queue.shift() ?? new Error('unexpected continuation');
    if (next instanceof Error) throw next;
    return next;
  };
  return { fetcher, browse, calls, asked, deps: { fetcher, browse } };
}

function youtubeSettings(
  youtube: { allowSubscriptions?: boolean; channels?: ChannelEntry[]; subscribedChannels?: ChannelEntry[] } = {},
  strict = false,
): NudgeSettings {
  const base = settings();
  return settings({
    strictMode: { ...base.strictMode, enabled: strict },
    rules: [
      siteRule({
        id: 'yt',
        domain: 'youtube.com',
        mode: 'HARD_BLOCK',
        features: featuresWith('youtube', {
          youtube: {
            channelMode: 'WHITELIST',
            channels: youtube.channels ?? [],
            allowSubscriptions: youtube.allowSubscriptions ?? true,
            subscribedChannels: youtube.subscribedChannels ?? [],
          },
        }),
      }),
    ],
  });
}

const STORED: ChannelEntry = { channelId: VERITASIUM_ID, handle: 'veritasium', displayName: 'Veritasium', addedAt: 1 };
const INJECTED: ChannelEntry = { channelId: null, handle: 'mrbeast', displayName: '@mrbeast', addedAt: 2 };

async function storeList(channels: ChannelEntry[]): Promise<void> {
  await saveSubscriptionState(applySyncOutcome(emptySyncState(), { ok: true, channels, truncated: null }, NOW - 1000));
}

let networkFetch: ReturnType<typeof vi.fn>;

beforeEach(() => {
  resetBrowser();
  installDnr();
  installAttention();
  resetSubscriptionSyncForTests();
  networkFetch = vi.fn(async () => {
    throw new Error('a test reached the real network');
  });
  globalThis.fetch = networkFetch as unknown as typeof fetch;
});

// Restored by hand: `vi.unstubAllGlobals()` would also remove the `chrome` global that the
// WXT test plugin installs.
const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

describe('fetchSubscriptions', () => {
  it("reads the user's own page with their cookies, then asks a youtube.com tab for the next page", async () => {
    const yt = fakeYouTube(response({ html: subscriptionsPageHtml() }), [ok(continuationResponse(manyChannels(2)))]);
    const outcome = await fetchSubscriptions(yt.fetcher, NOW, yt.browse);
    expect(outcome).toMatchObject({ ok: true, truncated: null });
    if (!outcome.ok) throw new Error('expected ok');
    expect(outcome.channels.map((c) => c.channelId)).toContain(KURZGESAGT_ID);
    expect(outcome.channels).toHaveLength(5);

    // The worker itself makes exactly one request: the page.
    expect(yt.calls).toHaveLength(1);
    expect(yt.calls[0]).toMatchObject({ url: SUBSCRIPTIONS_PAGE_URL, init: { credentials: 'include' } });
    // The tab is asked with the page's own token, client context and session, nothing else.
    expect(yt.asked).toEqual([
      {
        continuation: CONTINUATION_TOKEN,
        context: INNERTUBE_CONTEXT,
        session: { userSessionId: USER_SESSION_ID, sessionIndex: '0', delegatedSessionId: null },
      },
    ]);
  });

  it('follows several pages, each with the token the previous one returned', async () => {
    const page = subscriptionsPageHtml(initialData(manyChannels(100), { continuation: 't1' }));
    const yt = fakeYouTube(response({ html: page }), [
      ok(continuationResponse(manyChannels(100, 100), 't2')),
      ok(continuationResponse(manyChannels(100, 200), 't3')),
      ok(continuationResponse(manyChannels(50, 300))),
    ]);
    const outcome = await fetchSubscriptions(yt.fetcher, NOW, yt.browse);
    expect(outcome).toMatchObject({ ok: true, truncated: null });
    if (outcome.ok) expect(outcome.channels).toHaveLength(350);
    expect(yt.asked.map((p) => p.continuation)).toEqual(['t1', 't2', 't3']);
  });

  it('sends nothing of ours: the page request has no body, the tab request only the token, context and session', async () => {
    const yt = fakeYouTube(response({ html: subscriptionsPageHtml() }), [ok(continuationResponse(manyChannels(1)))]);
    await fetchSubscriptions(yt.fetcher, NOW, yt.browse);
    expect(yt.calls[0]?.init.body).toBeUndefined();
    expect(yt.calls[0]?.init.headers).toBeUndefined();
    expect(Object.keys(yt.asked[0] ?? {}).sort()).toEqual(['context', 'continuation', 'session']);
  });

  it('is signed-out when YouTube redirects to the Google sign-in page (status 200 after the redirect)', async () => {
    const yt = fakeYouTube(
      response({ url: 'https://accounts.google.com/ServiceLogin?service=youtube', html: '<html>Sign in</html>' }),
    );
    expect(await fetchSubscriptions(yt.fetcher, NOW, yt.browse)).toEqual({ ok: false, error: 'signed-out' });
  });

  it('is a network error on a non-ok status or a thrown fetch', async () => {
    const refused = fakeYouTube(response({ ok: false }));
    expect(await fetchSubscriptions(refused.fetcher, NOW, refused.browse)).toEqual({ ok: false, error: 'network' });
    const offline = fakeYouTube(new Error('offline'));
    expect(await fetchSubscriptions(offline.fetcher, NOW, offline.browse)).toEqual({ ok: false, error: 'network' });
  });

  it('is unparseable for a page with no subscription data', async () => {
    const yt = fakeYouTube(response({ html: '<html>Before you continue</html>' }));
    expect(await fetchSubscriptions(yt.fetcher, NOW, yt.browse)).toEqual({ ok: false, error: 'unparseable' });
  });

  it.each([
    ['a refused page', REFUSED],
    ['a page with no channels', ok({ responseContext: {} })],
    [
      'a signed-out page (what an unsigned request gets)',
      ok({ responseContext: { mainAppWebResponseContext: { loggedOut: true } } }),
    ],
    ['a thrown request', new Error('offline')],
  ])('keeps the first page and says "partial" after %s', async (_name, continuation) => {
    const yt = fakeYouTube(response({ html: subscriptionsPageHtml() }), [continuation]);
    const outcome = await fetchSubscriptions(yt.fetcher, NOW, yt.browse);
    expect(outcome).toMatchObject({ ok: true, truncated: 'partial' });
    if (outcome.ok) expect(outcome.channels).toHaveLength(3);
  });

  it('with no youtube.com tab to ask, keeps the first page and says "pending"', async () => {
    const yt = fakeYouTube(response({ html: subscriptionsPageHtml() }), [NO_TAB]);
    const outcome = await fetchSubscriptions(yt.fetcher, NOW, yt.browse);
    expect(outcome).toMatchObject({ ok: true, truncated: 'pending' });
    if (outcome.ok) expect(outcome.channels).toHaveLength(3);
  });

  it('a tab closed mid-list keeps every page read so far and says "pending"', async () => {
    const page = subscriptionsPageHtml(initialData(manyChannels(100), { continuation: 't1' }));
    const yt = fakeYouTube(response({ html: page }), [ok(continuationResponse(manyChannels(100, 100), 't2')), NO_TAB]);
    const outcome = await fetchSubscriptions(yt.fetcher, NOW, yt.browse);
    expect(outcome).toMatchObject({ ok: true, truncated: 'pending' });
    if (outcome.ok) expect(outcome.channels).toHaveLength(200);
  });

  it(`stops at ${MAX_SUBSCRIBED_CHANNELS} channels and says "cap"`, async () => {
    const page = subscriptionsPageHtml(initialData(manyChannels(300), { continuation: 't1' }));
    const yt = fakeYouTube(response({ html: page }), [ok(continuationResponse(manyChannels(300, 300), 't2'))]);
    const outcome = await fetchSubscriptions(yt.fetcher, NOW, yt.browse);
    expect(outcome).toMatchObject({ ok: true, truncated: 'cap' });
    if (outcome.ok) expect(outcome.channels).toHaveLength(MAX_SUBSCRIBED_CHANNELS);
    // Never asks for a page past the cap.
    expect(yt.asked).toHaveLength(1);
  });

  it(`a token loop stops after ${MAX_CONTINUATION_PAGES} pages and says "partial"`, async () => {
    const page = subscriptionsPageHtml(initialData(manyChannels(1), { continuation: 'loop' }));
    const pages = Array.from({ length: MAX_CONTINUATION_PAGES + 5 }, (_, i) =>
      ok(continuationResponse(manyChannels(1, i + 1), 'loop')),
    );
    const yt = fakeYouTube(response({ html: page }), pages);
    expect(await fetchSubscriptions(yt.fetcher, NOW, yt.browse)).toMatchObject({ ok: true, truncated: 'partial' });
    expect(yt.asked).toHaveLength(MAX_CONTINUATION_PAGES);
  });

  it('an end-of-list page (no token) is whole, and no tab is asked anything', async () => {
    const yt = fakeYouTube(response({ html: subscriptionsPageHtml(initialData(defaultChannels())) }));
    expect(await fetchSubscriptions(yt.fetcher, NOW, yt.browse)).toMatchObject({ ok: true, truncated: null });
    expect(yt.calls).toHaveLength(1);
    expect(yt.asked).toHaveLength(0);
  });
});

describe('syncSubscriptions: storage', () => {
  it('stores the synced list under its own local key and hydrates it into loaded settings', async () => {
    await saveSettings(youtubeSettings());
    await syncSubscriptions({ ...fakeYouTube(response({ html: subscriptionsPageHtml(initialData(defaultChannels())) })).deps, now: () => NOW });

    const raw = await chrome.storage.local.get(YT_SUBSCRIPTIONS_KEY);
    expect((raw[YT_SUBSCRIPTIONS_KEY] as { channels: unknown[] }).channels).toHaveLength(3);
    const loaded = await loadSettings();
    expect(loaded.rules[0]?.features?.youtube?.subscribedChannels.map((c) => c.handle)).toEqual([
      'veritasium',
      'kurzgesagt',
      null,
    ]);
  });

  it('never writes the list into the synced settings blob (8 KB per-item quota)', async () => {
    await saveSettings(youtubeSettings());
    await storeList([STORED]);
    const hydrated = await loadSettings();
    expect(hydrated.rules[0]?.features?.youtube?.subscribedChannels).toEqual([STORED]);
    await saveSettings(hydrated);
    const synced = await chrome.storage.sync.get('nudge:settings');
    const yt = (synced['nudge:settings'] as NudgeSettings).rules[0]?.features?.youtube;
    expect(yt?.subscribedChannels).toEqual([]);
  });

  it('a signed-out sync keeps the last list and records why', async () => {
    await saveSettings(youtubeSettings());
    await storeList([STORED]);
    const signedOut = fakeYouTube(response({ url: 'https://accounts.google.com/ServiceLogin' }));
    const state = await syncSubscriptions({ ...signedOut.deps, now: () => NOW });
    expect(state.channels).toEqual([STORED]);
    expect(state.error).toBe('signed-out');
    expect((await subscriptionStatus()).error).toBe('signed-out');
  });

  it('does nothing at all while the option is off', async () => {
    await saveSettings(youtubeSettings({ allowSubscriptions: false }));
    const { deps, calls } = fakeYouTube(response({ html: subscriptionsPageHtml() }));
    await syncSubscriptions({ ...deps, now: () => NOW });
    expect(calls).toHaveLength(0);
    expect(await loadSubscriptionState()).toEqual(emptySyncState());
  });

  it('concurrent callers share one fetch', async () => {
    await saveSettings(youtubeSettings());
    const { deps, calls } = fakeYouTube(response({ html: subscriptionsPageHtml(initialData(defaultChannels())) }));
    await Promise.all([syncSubscriptions({ ...deps, now: () => NOW }), syncSubscriptions({ ...deps, now: () => NOW })]);
    expect(calls).toHaveLength(1);
  });

  it('a synced channel becomes a DNR allow-rule', async () => {
    await saveSettings(youtubeSettings());
    await syncSubscriptions({ ...fakeYouTube(response({ html: subscriptionsPageHtml(initialData(defaultChannels())) })).deps, now: () => NOW });
    const rules = compileRules(await loadSettings(), { ms: {}, counts: {} }, NOW_DATE);
    const allows = rules.filter((r) => r.action.type === 'allow').map((r) => new RegExp(r.condition.regexFilter ?? '$^', 'i'));
    expect(allows.some((re) => re.test('https://www.youtube.com/@kurzgesagt/videos'))).toBe(true);
    expect(allows.some((re) => re.test('https://www.youtube.com/@mrbeast'))).toBe(false);
  });
});

describe('maybeAutoSync: at most daily, on a youtube.com visit', () => {
  it('syncs when due and not again within the day', async () => {
    await saveSettings(youtubeSettings());
    const page = () => response({ html: subscriptionsPageHtml(initialData(defaultChannels())) });
    const first = fakeYouTube(page());
    await maybeAutoSync({ ...first.deps, now: () => NOW });
    expect(first.calls).toHaveLength(1);

    const second = fakeYouTube(page());
    await maybeAutoSync({ ...second.deps, now: () => NOW + 60 * 60 * 1000 * 2 });
    expect(second.calls).toHaveLength(0);

    const third = fakeYouTube(page());
    await maybeAutoSync({ ...third.deps, now: () => NOW + AUTO_SYNC_INTERVAL_MS + 1 });
    expect(third.calls).toHaveLength(1);
  });

  it('never throws into the page, whatever the network does', async () => {
    await saveSettings(youtubeSettings());
    await expect(maybeAutoSync({ ...fakeYouTube(new Error('boom')).deps, now: () => NOW })).resolves.toBeUndefined();
  });
});

describe('pending, then finished on the next youtube.com visit (router + tab picker + content script)', () => {
  const SECRET = 'SAPISIDtestVALUE/abc';

  /**
   * Real `tabs.sendMessage` semantics as far as this feature cares: the message is
   * structured-cloned into the tab, where the REAL content-script handler signs and sends
   * the request with a fake page network, and the reply is cloned back.
   */
  function installYoutubeTabs() {
    const tabs: { id: number; url: string }[] = [];
    const sent: { tabId: number; message: SignedBrowseRequest }[] = [];
    const replies: unknown[] = [];
    const pageRequests: { url: string; init: RequestInit }[] = [];
    Object.defineProperty(chrome, 'tabs', {
      configurable: true,
      writable: true,
      value: {
        query: async () => tabs,
        update: async () => undefined,
        sendMessage: async (tabId: number, message: SignedBrowseRequest) => {
          sent.push({ tabId, message: structuredClone(message) });
          const reply = await handleSignedBrowse(structuredClone(message), {
            origin: () => YOUTUBE_ORIGIN,
            cookie: () => `PREF=1; SAPISID=${SECRET}; __Secure-3PAPISID=${SECRET}`,
            nowSeconds: () => NOW / 1000,
            send: async (url, init) => {
              pageRequests.push({ url, init });
              return response({ json: continuationResponse(manyChannels(2)) });
            },
          });
          replies.push(structuredClone(reply));
          return reply;
        },
      },
    });
    return { tabs, sent, replies, pageRequests };
  }

  it('"Sync now" with no youtube.com tab keeps the first page as pending; the next visit finishes it, signed in that tab', async () => {
    await saveSettings(youtubeSettings());
    networkFetch.mockImplementation(async () => response({ html: subscriptionsPageHtml() }));
    const yt = installYoutubeTabs();
    const clock = vi.spyOn(Date, 'now').mockReturnValue(NOW);
    try {
      // 1. Dashboard "Sync now", no youtube.com tab open: first page only, marked pending.
      const status = await handleRequest({ type: 'SYNC_YT_SUBSCRIPTIONS' } as never, NOW_DATE);
      expect(status).toMatchObject({ count: 3, truncated: 'pending', error: null });
      expect(yt.sent).toHaveLength(0);

      // 2. The user opens a video. Its GET_SITE_CONFIG is the cue; that tab is asked first.
      yt.tabs.push({ id: 8, url: 'https://www.youtube.com/' }, { id: 7, url: 'https://www.youtube.com/watch?v=abc' });
      clock.mockReturnValue(NOW + PENDING_RETRY_MS);
      await handleRequest(
        { type: 'GET_SITE_CONFIG', url: 'https://www.youtube.com/watch?v=abc' } as never,
        new Date(NOW + PENDING_RETRY_MS),
        { tab: { id: 7 } } as chrome.runtime.MessageSender,
      );
      await vi.waitFor(async () => expect((await loadSubscriptionState()).truncated).toBeNull());
      expect((await loadSubscriptionState()).channels).toHaveLength(5);
      expect(yt.sent.map((s) => s.tabId)).toEqual([7]);

      // Every request went to youtube.com: the page twice (worker), the browse endpoint once (tab).
      expect(networkFetch.mock.calls.map((c) => c[0])).toEqual([SUBSCRIPTIONS_PAGE_URL, SUBSCRIPTIONS_PAGE_URL]);
      expect(yt.pageRequests.map((r) => r.url)).toEqual([BROWSE_ENDPOINT_URL]);

      // The cookie never reached the worker: not in a message, a reply, storage or a worker
      // request. Nor did its hash, which only the tab's own request to youtube.com carries.
      const authorization = (yt.pageRequests[0]?.init.headers as Record<string, string>).Authorization ?? '';
      expect(authorization).toMatch(/^SAPISIDHASH \d+_[0-9a-f]{40}_u SAPISID3PHASH \d+_[0-9a-f]{40}_u$/);
      const hash = authorization.split(' ')[1] as string;
      expect(Object.keys(yt.sent[0]?.message ?? {}).sort()).toEqual(['context', 'continuation', 'session', 'type']);
      const leftTheTab = JSON.stringify([
        yt.sent,
        yt.replies,
        networkFetch.mock.calls,
        await chrome.storage.local.get(null),
        await chrome.storage.sync.get(null),
      ]);
      expect(leftTheTab).not.toContain(SECRET);
      expect(leftTheTab).not.toContain(hash.split('_')[1] as string);
    } finally {
      clock.mockRestore();
    }
  });

  it('a pending list is not re-read on every page view (floored at PENDING_RETRY_MS)', async () => {
    await saveSettings(youtubeSettings());
    networkFetch.mockImplementation(async () => response({ html: subscriptionsPageHtml() }));
    installYoutubeTabs();
    const clock = vi.spyOn(Date, 'now').mockReturnValue(NOW);
    try {
      await handleRequest({ type: 'SYNC_YT_SUBSCRIPTIONS' } as never, NOW_DATE);
      clock.mockReturnValue(NOW + PENDING_RETRY_MS - 1);
      await maybeAutoSync();
      expect(networkFetch).toHaveBeenCalledTimes(1);
    } finally {
      clock.mockRestore();
    }
  });
});

describe('the router: the list is the worker\'s, never the caller\'s', () => {
  async function save(next: NudgeSettings, challengeResponse?: string): Promise<SaveResult> {
    return (await handleRequest(
      { type: 'SAVE_SETTINGS', settings: next, challengeResponse } as never,
      NOW_DATE,
    )) as SaveResult;
  }

  it('a save carrying a made-up subscribed list cannot smuggle channels into the whitelist', async () => {
    await saveSettings(youtubeSettings());
    await storeList([STORED]);
    const result = await save(youtubeSettings({ subscribedChannels: [INJECTED, STORED] }));
    expect(result.ok).toBe(true);
    const loaded = await loadSettings();
    expect(loaded.rules[0]?.features?.youtube?.subscribedChannels).toEqual([STORED]);
    // What the save INSTALLED, not a recompile from storage: the save compiles DNR from the
    // object it judged, so that object must already hold the worker's list.
    const installed = await chrome.declarativeNetRequest.getDynamicRules();
    const allows = installed
      .filter((r) => r.action.type === 'allow')
      .map((r) => new RegExp(r.condition.regexFilter ?? '$^', 'i'));
    expect(allows.some((re) => re.test('https://www.youtube.com/@veritasium'))).toBe(true);
    expect(allows.some((re) => re.test('https://www.youtube.com/@mrbeast'))).toBe(false);
  });

  it('a stale save cannot erase a sync that landed after the screen loaded', async () => {
    await saveSettings(youtubeSettings());
    const staleSnapshot = await loadSettings(); // dashboard opens: no subscriptions yet
    await storeList([STORED]); // a sync lands
    expect((await save(staleSnapshot)).ok).toBe(true);
    expect((await loadSettings()).rules[0]?.features?.youtube?.subscribedChannels).toEqual([STORED]);
  });

  it('turning the option ON under Strict Mode asks for the challenge', async () => {
    await saveSettings(youtubeSettings({ allowSubscriptions: false }, true));
    const result = await save(youtubeSettings({ allowSubscriptions: true }, true));
    expect(result).toMatchObject({ ok: false, reason: 'challenge-required' });
    expect(networkFetch).not.toHaveBeenCalled();
  });

  it('turning it ON syncs straight away (through the real network seam)', async () => {
    await saveSettings(youtubeSettings({ allowSubscriptions: false }));
    networkFetch.mockImplementation(async () =>
      response({ html: subscriptionsPageHtml(initialData(defaultChannels())) }),
    );
    expect((await save(youtubeSettings({ allowSubscriptions: true }))).ok).toBe(true);
    await vi.waitFor(async () => expect((await loadSubscriptionState()).channels).toHaveLength(3));
    expect(networkFetch.mock.calls[0]?.[0]).toBe(SUBSCRIPTIONS_PAGE_URL);
  });

  it("the content script's config carries the synced list, so the in-page gate agrees with DNR", async () => {
    // DNR allows a subscribed channel's pages; if GET_SITE_CONFIG dropped the list, the
    // watch gate in the page would hold the very video the network layer let through.
    await saveSettings(youtubeSettings());
    await storeList([STORED]);
    const config = (await handleRequest(
      { type: 'GET_SITE_CONFIG', url: 'https://www.youtube.com/watch?v=abc' } as never,
      NOW_DATE,
    )) as { youtube: { subscribedChannels: ChannelEntry[] } | null; siteApplies: boolean };
    expect(config.youtube?.subscribedChannels).toEqual([STORED]);
    const { channels } = effectiveChannelLists(config.youtube as never, config.siteApplies);
    expect(channels.map((c) => c.handle)).toContain('veritasium');
  });

  it('GET_YT_SUBSCRIPTIONS reports the count and status without fetching', async () => {
    await storeList([STORED]);
    const status = await handleRequest({ type: 'GET_YT_SUBSCRIPTIONS' } as never, NOW_DATE);
    expect(status).toMatchObject({ count: 1, error: null, syncing: false });
    expect(networkFetch).not.toHaveBeenCalled();
  });
});
