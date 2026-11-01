/**
 * The worker's half of the signed subscriptions read: hand each continuation to a
 * www.youtube.com tab THE USER ALREADY HAS OPEN, whose content script signs and sends it
 * (`content/signedBrowse.ts`). The worker never sees the sign-in cookie or the hash; it
 * sends a token and gets YouTube's JSON back.
 *
 * No tab is ever opened, focused or navigated for this. With no youtube.com tab available
 * the answer is `no-tab`, the sync keeps the first page as `pending`, and the next
 * youtube.com visit finishes it (`maybeAutoSync`, cued by that visit's GET_SITE_CONFIG).
 *
 * Only `https://www.youtube.com` tabs qualify: the request has to be same-origin with the
 * page to carry its cookies, and m./music. are other origins.
 */

import {
  SIGNED_BROWSE_MESSAGE,
  YOUTUBE_ORIGIN,
  type BrowseSession,
  type SignedBrowseReply,
  type SignedBrowseRequest,
} from '../core/signedBrowse';

/** What one signed page request came to, from the sync loop's point of view. */
export type BrowseResult =
  | { kind: 'ok'; json: unknown }
  /** A tab answered and YouTube did not give the page: the list stays `partial`. */
  | { kind: 'refused' }
  /** No youtube.com tab could take the request: the list stays `pending`. */
  | { kind: 'no-tab' };

export interface BrowsePage {
  continuation: string;
  context: Record<string, unknown>;
  session: BrowseSession;
}

/** One signed continuation request. Injectable, so tests never need a real tab. */
export type SignedBrowser = (page: BrowsePage) => Promise<BrowseResult>;

function isYoutubeTab(tab: chrome.tabs.Tab): tab is chrome.tabs.Tab & { id: number } {
  if (typeof tab.id !== 'number' || tab.discarded === true || typeof tab.url !== 'string') return false;
  try {
    return new URL(tab.url).origin === YOUTUBE_ORIGIN;
  } catch {
    return false;
  }
}

/** The youtube.com tabs to try, the one that cued the sync (if any) first. */
async function candidateTabs(preferredTabId: number | undefined): Promise<number[]> {
  let tabs: chrome.tabs.Tab[];
  try {
    tabs = await chrome.tabs.query({ url: `${YOUTUBE_ORIGIN}/*` });
  } catch {
    return [];
  }
  const ids = tabs.filter(isYoutubeTab).map((tab) => tab.id);
  if (preferredTabId !== undefined && ids.includes(preferredTabId)) {
    return [preferredTabId, ...ids.filter((id) => id !== preferredTabId)];
  }
  return ids;
}

function isReply(value: unknown): value is SignedBrowseReply {
  return value !== null && typeof value === 'object' && typeof (value as { ok?: unknown }).ok === 'boolean';
}

/**
 * A `SignedBrowser` over the user's open youtube.com tabs.
 *
 * A tab whose content script cannot answer (a tab loaded before an extension update, a page
 * still loading, no listener) is skipped for the next one, and so is a tab that turns out
 * not to be www.youtube.com. Any OTHER failure is YouTube's answer and is not retried in
 * another tab: the same session would get the same answer.
 */
export function tabSignedBrowser(preferredTabId?: number): SignedBrowser {
  let tabs: number[] | null = null;
  return async (page) => {
    tabs ??= await candidateTabs(preferredTabId);
    const message: SignedBrowseRequest = {
      type: SIGNED_BROWSE_MESSAGE,
      continuation: page.continuation,
      context: page.context,
      session: page.session,
    };
    while (tabs.length > 0) {
      const tabId = tabs[0] as number;
      let reply: unknown;
      try {
        reply = await chrome.tabs.sendMessage(tabId, message, { frameId: 0 });
      } catch {
        reply = undefined; // "Receiving end does not exist": no live content script there.
      }
      if (!isReply(reply) || (!reply.ok && reply.reason === 'not-youtube')) {
        tabs.shift();
        continue;
      }
      return reply.ok ? { kind: 'ok', json: reply.json } : { kind: 'refused' };
    }
    return { kind: 'no-tab' };
  };
}
