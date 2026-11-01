import { beforeEach, describe, expect, it, vi } from 'vitest';

import { tabSignedBrowser, type BrowsePage } from '../../src/background/youtubeTabBrowse';
import { SIGNED_BROWSE_MESSAGE, type SignedBrowseReply } from '../../src/core/signedBrowse';
import { resetBrowser } from './fakeApis';

/**
 * Which tab signs the "load more" pages. Only a www.youtube.com tab the user already has
 * open; never a tab opened, focused or navigated for the purpose.
 */

const PAGE: BrowsePage = {
  continuation: 'tok',
  context: { client: { clientName: 'WEB' } },
  session: { userSessionId: '123', sessionIndex: '0', delegatedSessionId: null },
};

interface Tab {
  id: number;
  url: string;
  discarded?: boolean;
}

function installTabs(tabs: Tab[], answer: (tabId: number) => SignedBrowseReply | undefined | Error) {
  const sent: { tabId: number; message: unknown; options: unknown }[] = [];
  const opened = vi.fn();
  Object.defineProperty(chrome, 'tabs', {
    configurable: true,
    writable: true,
    value: {
      query: vi.fn(async () => tabs),
      sendMessage: vi.fn(async (tabId: number, message: unknown, options: unknown) => {
        sent.push({ tabId, message, options });
        const reply = answer(tabId);
        if (reply instanceof Error) throw reply;
        return reply;
      }),
      create: opened,
      update: opened,
      highlight: opened,
      reload: opened,
    },
  });
  return { sent, opened };
}

const OK: SignedBrowseReply = { ok: true, json: { page: 1 } };

beforeEach(() => resetBrowser());

describe('tabSignedBrowser', () => {
  it('asks the visiting tab first, with exactly the request and nothing else, top frame only', async () => {
    const { sent } = installTabs(
      [
        { id: 1, url: 'https://www.youtube.com/' },
        { id: 2, url: 'https://www.youtube.com/watch?v=x' },
      ],
      () => OK,
    );
    expect(await tabSignedBrowser(2)(PAGE)).toEqual({ kind: 'ok', json: { page: 1 } });
    expect(sent).toEqual([
      {
        tabId: 2,
        message: { type: SIGNED_BROWSE_MESSAGE, continuation: 'tok', context: PAGE.context, session: PAGE.session },
        options: { frameId: 0 },
      },
    ]);
  });

  it('only www.youtube.com tabs that are loaded qualify (other origins cannot send youtube.com cookies same-origin)', async () => {
    const { sent } = installTabs(
      [
        { id: 1, url: 'https://m.youtube.com/' },
        { id: 2, url: 'https://music.youtube.com/' },
        { id: 3, url: 'http://www.youtube.com/' },
        { id: 4, url: 'https://www.youtube.com.evil.example/' },
        { id: 5, url: 'https://www.youtube.com/', discarded: true },
        { id: 6, url: 'https://example.com/' },
        { id: 7, url: 'https://www.youtube.com/feed/subscriptions' },
      ],
      () => OK,
    );
    // A preferred tab that is not a qualifying tab is not asked either.
    await tabSignedBrowser(1)(PAGE);
    expect(sent.map((s) => s.tabId)).toEqual([7]);
  });

  it('skips a tab with no live content script, or one that does not answer, for the next', async () => {
    const { sent } = installTabs(
      [
        { id: 1, url: 'https://www.youtube.com/' },
        { id: 2, url: 'https://www.youtube.com/' },
        { id: 3, url: 'https://www.youtube.com/' },
      ],
      (tabId) =>
        tabId === 1 ? new Error('Could not establish connection. Receiving end does not exist.') : tabId === 2 ? undefined : OK,
    );
    const browse = tabSignedBrowser();
    expect(await browse(PAGE)).toEqual({ kind: 'ok', json: { page: 1 } });
    // The working tab is remembered for the next page.
    expect(await browse(PAGE)).toEqual({ kind: 'ok', json: { page: 1 } });
    expect(sent.map((s) => s.tabId)).toEqual([1, 2, 3, 3]);
  });

  it("YouTube's refusal is not retried in another tab (same session, same answer)", async () => {
    const { sent } = installTabs(
      [
        { id: 1, url: 'https://www.youtube.com/' },
        { id: 2, url: 'https://www.youtube.com/' },
      ],
      () => ({ ok: false, reason: 'http', status: 403 }),
    );
    expect(await tabSignedBrowser()(PAGE)).toEqual({ kind: 'refused' });
    expect(sent.map((s) => s.tabId)).toEqual([1]);
  });

  it('no youtube.com tab: no-tab, and no tab is opened, focused, reloaded or navigated', async () => {
    const { sent, opened } = installTabs([{ id: 1, url: 'https://example.com/' }], () => OK);
    expect(await tabSignedBrowser()(PAGE)).toEqual({ kind: 'no-tab' });
    expect(sent).toHaveLength(0);
    expect(opened).not.toHaveBeenCalled();
  });

  it('every tab failing to answer is no-tab too', async () => {
    const { opened } = installTabs([{ id: 1, url: 'https://www.youtube.com/' }], () => ({ ok: false, reason: 'not-youtube' }));
    expect(await tabSignedBrowser()(PAGE)).toEqual({ kind: 'no-tab' });
    expect(opened).not.toHaveBeenCalled();
  });
});
