/**
 * Syncing "channels I'm subscribed to" from youtube.com, in the worker.
 *
 * THE ONE NETWORK CALL THIS EXTENSION MAKES, and only while the user has turned on "Also
 * allow channels I'm subscribed to". It reads `https://www.youtube.com/feed/channels`, the
 * user's own subscriptions page, with the cookies the browser already holds for youtube.com,
 * exactly as if they had opened that page in a tab. It sends nothing of ours: no id, no
 * settings, no usage. The answer is parsed here (`core/youtubeSubscriptions.ts`) and kept in
 * `storage.local`. It needs no new permission: the `<all_urls>` host permission the
 * extension already holds for blocking is what lets the worker read the page.
 *
 * WHAT IT DELIBERATELY DOES NOT DO. YouTube's own page authenticates its "load more"
 * requests with a hash of the SAPISID cookie. Reading that cookie would need the `cookies`
 * permission and means handling the user's Google session secret, which is a different
 * order of invasive from "read a page I can already open". So the continuation pages are
 * requested with cookies alone; if YouTube refuses them, the list is what the first page
 * held and the status says so (`truncated: 'partial'`) rather than pretending it is whole.
 *
 * LIVE RESULT (signed-in QA, 2026-11-01): YouTube DOES refuse them. A cookie-only browse
 * POST with a real continuation token answers 403 (Google's "Sorry..." page) from the worker,
 * with or without credentials, and from a youtube.com page it answers 200 but signed OUT. So
 * today the synced list is the first page of `/feed/channels`, and any account with more
 * subscriptions than that page holds reads "partial". Going further needs the SAPISIDHASH
 * header, which is the privacy call above, not a bug fix.
 *
 * DNR cannot get in the way: every redirect rule this extension installs is `main_frame`
 * only (`background/dnr.ts`), and a worker `fetch` is not a main-frame navigation, so the
 * sync works while YouTube is blocked and after the daily limit is spent.
 */

import {
  BROWSE_ENDPOINT_URL,
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
import type { SubscriptionSyncStatus } from '../core/protocol';
import { loadSettings, loadSubscriptionState, saveSubscriptionState } from './storage';

/** The slice of `fetch` the sync uses, injectable so tests never touch the network. */
export type Fetcher = (url: string, init: RequestInit) => Promise<Response>;

/** The real network, the only place this module reaches it. */
// The one sanctioned request: the user's own youtube.com subscriptions page, opt-in, nothing
// sent but their existing cookies. See this file's header and extension/CLAUDE.md.
const networkFetch: Fetcher = (url, init) =>
  // eslint-disable-next-line no-restricted-globals -- opt-in youtube.com read, see above
  fetch(url, init);

/** True when ANY YouTube rule has "Also allow channels I'm subscribed to" on. */
export function subscriptionsWanted(settings: NudgeSettings): boolean {
  return settings.rules.some((rule) => rule.features?.youtube?.allowSubscriptions === true);
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
 * Signed out, YouTube redirects the request to Google's sign-in page and `fetch` follows
 * it, so the status is 200: the FINAL URL is what tells (verified with a signed-out request,
 * 2026-11-01).
 */
export async function fetchSubscriptions(fetcher: Fetcher, now: number): Promise<SyncOutcome> {
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
  const url = first.innertube.apiKey
    ? `${BROWSE_ENDPOINT_URL}&key=${encodeURIComponent(first.innertube.apiKey)}`
    : BROWSE_ENDPOINT_URL;

  for (let page = 0; token !== null && total < MAX_SUBSCRIBED_CHANNELS; page++) {
    if (page >= MAX_CONTINUATION_PAGES) {
      partial = true;
      break;
    }
    try {
      const response = await fetcher(url, {
        method: 'POST',
        credentials: 'include',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(continuationRequestBody(first.innertube, token)),
      });
      if (!response.ok) {
        partial = true;
        break;
      }
      const next = parseContinuationResponse(await response.json(), now);
      if (next === null) {
        // A continuation that names no channel is YouTube declining to give us the rest
        // (most likely: it wanted the auth header we choose not to compute). Not "the end
        // of the list", which is signalled by the absence of a token.
        partial = true;
        break;
      }
      pages.push(next.channels);
      total += next.channels.length;
      token = next.continuation;
    } catch {
      partial = true;
      break;
    }
  }

  const merged = mergeSubscriptionPages(pages);
  const capped = merged.capped || (token !== null && total >= MAX_SUBSCRIBED_CHANNELS);
  return {
    ok: true,
    channels: merged.channels,
    truncated: capped ? 'cap' : partial ? 'partial' : null,
  };
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
export function syncSubscriptions(
  fetcher: Fetcher = networkFetch,
  now: () => number = Date.now,
): Promise<SubscriptionSyncState> {
  if (inFlight !== null) return inFlight;
  inFlight = (async () => {
    try {
      const previous = await loadSubscriptionState();
      if (!subscriptionsWanted(await loadSettings())) return previous;
      const outcome = await fetchSubscriptions(fetcher, now());
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
 * The automatic refresh: on a youtube.com visit, at most daily (`isAutoSyncDue`). Fire and
 * forget; a failure is recorded in the stored state and never surfaces to the page.
 */
export async function maybeAutoSync(
  fetcher: Fetcher = networkFetch,
  now: () => number = Date.now,
): Promise<void> {
  try {
    if (inFlight !== null) return;
    const state = await loadSubscriptionState();
    if (!isAutoSyncDue(state, now())) return;
    if (!subscriptionsWanted(await loadSettings())) return;
    await syncSubscriptions(fetcher, now);
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
