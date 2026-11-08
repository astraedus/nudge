/**
 * Syncing "channels I'm subscribed to" from youtube.com, in the worker.
 *
 * THE ONE NETWORK FEATURE THIS EXTENSION HAS, and only while the user has turned on "Also
 * allow channels I'm subscribed to". Two kinds of request, both to youtube.com, both
 * carrying nothing of ours (no id, no settings, no usage):
 *
 *  1. FIRST PAGE, from the worker: a GET of `https://www.youtube.com/feed/channels`, the
 *     user's own subscriptions page, with the cookies the browser already holds, exactly as
 *     if they had opened it in a tab. The `<all_urls>` host permission the extension already
 *     holds for blocking is what lets the worker read it.
 *  2. THE REST, from a youtube.com tab the user already has open: each "load more" page is a
 *     `youtubei/v1/browse` POST, signed the way YouTube's own page signs it (a SAPISIDHASH of
 *     the sign-in cookie). YouTube refuses it unsigned: 403 from the worker, signed OUT from
 *     a page (live, 2026-11-01). Signed, it answers signed in, with the channels (live,
 *     2026-11-02). The signing happens in the tab's content script
 *     (`content/signedBrowse.ts`), so the cookie never reaches the worker, storage or a
 *     message: this module sends a token and reads back YouTube's JSON
 *     (`youtubeTabBrowse.ts`). No `cookies` permission.
 *
 * No youtube.com tab open (e.g. "Sync now" from the dashboard): the first page is kept and
 * marked `pending`, and the next youtube.com visit finishes it. No tab is ever opened for
 * it. A refused or unreadable page keeps what was read and says `partial`. A failed read
 * never wipes the list (`applySyncOutcome`).
 *
 * DNR cannot get in the way: every redirect rule this extension installs is `main_frame`
 * only (`background/dnr.ts`), and neither a worker `fetch` nor a content script's is a
 * main-frame navigation, so the sync works while YouTube is blocked (from an allowed page)
 * and after the daily limit is spent.
 */

import {
  MAX_CONTINUATION_PAGES,
  MAX_SUBSCRIBED_CHANNELS,
  SUBSCRIPTIONS_PAGE_URL,
  applySyncOutcome,
  continuationRequestBody,
  isAutoSyncDue,
  mergeSubscriptionPages,
  parseContinuationResponse,
  parseSubscriptionsPage,
  type SubscriptionSyncState,
  type SyncOutcome,
} from '../core/youtubeSubscriptions';
import type { ChannelEntry, NudgeSettings } from '../core/settingsSchema';
import { ruleUsesSubscriptions } from '../core/youtubeAccess';
import type { SubscriptionSyncStatus } from '../core/protocol';
import { loadSettings, loadSubscriptionState, saveSubscriptionState } from './storage';
import { tabSignedBrowser, type BrowseResult, type SignedBrowser } from './youtubeTabBrowse';

/** The slice of `fetch` the first-page read uses, injectable so tests never touch the network. */
export type Fetcher = (url: string, init: RequestInit) => Promise<Response>;

/** The real network for the first page. */
// A sanctioned request: the user's own youtube.com subscriptions page, opt-in, nothing sent
// but their existing cookies. See this file's header and extension/CLAUDE.md.
const networkFetch: Fetcher = (url, init) =>
  // eslint-disable-next-line no-restricted-globals -- opt-in youtube.com read, see above
  fetch(url, init);

/**
 * True when ANY YouTube rule has "Also allow channels I'm subscribed to" on, for its own list
 * or a schedule window's (`ruleUsesSubscriptions`). Either one needs the synced list.
 */
export function subscriptionsWanted(settings: NudgeSettings): boolean {
  return settings.rules.some(ruleUsesSubscriptions);
}

function isSignInPage(response: Response): boolean {
  try {
    return new URL(response.url).hostname === 'accounts.google.com';
  } catch {
    return false;
  }
}

/**
 * Fetch and parse every page of the user's subscriptions. Never throws.
 *
 * The first page comes from `fetcher` (the worker's own GET), every later page from
 * `browse` (signed, in a youtube.com tab). Signed out, YouTube redirects the first request
 * to Google's sign-in page and `fetch` follows it, so the status is 200: the FINAL URL is
 * what tells (verified with a signed-out request, 2026-11-01).
 */
export async function fetchSubscriptions(
  fetcher: Fetcher,
  now: number,
  browse: SignedBrowser,
): Promise<SyncOutcome> {
  let html: string;
  try {
    const response = await fetcher(SUBSCRIPTIONS_PAGE_URL, {
      credentials: 'include',
      cache: 'no-store',
      redirect: 'follow',
    });
    if (isSignInPage(response)) return { ok: false, error: 'signed-out' };
    if (!response.ok) return { ok: false, error: 'network' };
    html = await response.text();
  } catch {
    return { ok: false, error: 'network' };
  }

  const first = parseSubscriptionsPage(html, now);
  if (first.kind !== 'ok') return { ok: false, error: first.kind };

  const pages: ChannelEntry[][] = [first.channels];
  let total = first.channels.length;
  let token = first.continuation;
  let partial = false;
  let pending = false;
  const context = continuationRequestBody(first.innertube, '').context as Record<string, unknown>;

  for (let page = 0; token !== null && total < MAX_SUBSCRIBED_CHANNELS; page++) {
    if (page >= MAX_CONTINUATION_PAGES) {
      partial = true;
      break;
    }
    let result: BrowseResult;
    try {
      result = await browse({ continuation: token, context, session: first.innertube.session });
    } catch {
      result = { kind: 'refused' };
    }
    if (result.kind === 'no-tab') {
      // Not YouTube's answer: there was no youtube.com tab to ask. The next visit will.
      pending = true;
      break;
    }
    const next = result.kind === 'ok' ? parseContinuationResponse(result.json, now) : null;
    if (next === null) {
      // Refused, or a page naming no channel: YouTube declining to give us the rest. Not
      // "the end of the list", which is signalled by the absence of a token.
      partial = true;
      break;
    }
    pages.push(next.channels);
    total += next.channels.length;
    token = next.continuation;
  }

  const merged = mergeSubscriptionPages(pages);
  const capped = merged.capped || (token !== null && total >= MAX_SUBSCRIBED_CHANNELS);
  return {
    ok: true,
    channels: merged.channels,
    truncated: capped ? 'cap' : pending ? 'pending' : partial ? 'partial' : null,
  };
}

/** What a sync talks to. Each defaults to the real thing; tests pass fakes. */
export interface SyncDeps {
  fetcher?: Fetcher;
  now?: () => number;
  /**
   * Who signs the continuation pages. Default: any open www.youtube.com tab. A sync cued by
   * a youtube.com visit passes `tabSignedBrowser(thatTabId)` so the visiting tab goes first.
   */
  browse?: SignedBrowser;
}

let inFlight: Promise<SubscriptionSyncState> | null = null;

/** Test hook: forget a sync left in flight by a previous test. */
export function resetSubscriptionSyncForTests(): void {
  inFlight = null;
}

/**
 * Sync now, if the option is on anywhere. Concurrent callers share one sync.
 *
 * Writes `storage.local` only; the worker's `storage.onChanged` listener recompiles DNR
 * from there (`entrypoints/background.ts`), and content scripts reload their config on any
 * storage change, so nothing here has to know who reads the list.
 */
export function syncSubscriptions(deps: SyncDeps = {}): Promise<SubscriptionSyncState> {
  if (inFlight !== null) return inFlight;
  const fetcher = deps.fetcher ?? networkFetch;
  const now = deps.now ?? Date.now;
  const browse = deps.browse ?? tabSignedBrowser();
  inFlight = (async () => {
    try {
      const previous = await loadSubscriptionState();
      if (!subscriptionsWanted(await loadSettings())) return previous;
      const outcome = await fetchSubscriptions(fetcher, now(), browse);
      // Re-read: the list must be folded into whatever is stored NOW, not what was stored
      // before a fetch that can take seconds.
      const next = applySyncOutcome(await loadSubscriptionState(), outcome, now());
      await saveSubscriptionState(next);
      return next;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}

/**
 * The automatic refresh, cued by a youtube.com visit: at most daily, or straight away when
 * a `pending` list is waiting for a youtube.com tab (`isAutoSyncDue`). Fire and forget; a
 * failure is recorded in the stored state and never surfaces to the page.
 */
export async function maybeAutoSync(deps: SyncDeps = {}): Promise<void> {
  const now = deps.now ?? Date.now;
  try {
    if (inFlight !== null) return;
    const state = await loadSubscriptionState();
    if (!isAutoSyncDue(state, now())) return;
    if (!subscriptionsWanted(await loadSettings())) return;
    await syncSubscriptions(deps);
  } catch (error) {
    console.warn('[nudge] subscription auto-sync failed', error);
  }
}

/** What the dashboard shows. */
export async function subscriptionStatus(): Promise<SubscriptionSyncStatus> {
  const state = await loadSubscriptionState();
  return {
    count: state.channels.length,
    syncedAt: state.syncedAt,
    error: state.error,
    truncated: state.truncated,
    syncing: inFlight !== null,
  };
}
