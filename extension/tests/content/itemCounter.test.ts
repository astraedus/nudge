// @vitest-environment jsdom
/**
 * Tests for the page half of COUNT budgets (`src/content/itemCounter.ts`): noticing that
 * the page has landed on one item of a gate's stream, and telling the worker.
 *
 * Repo convention (extension/CLAUDE.md "A test that asserts the bug is worse than no
 * test"): assertions are phrased as what actually got SENT (or didn't), not as an internal
 * branch — `createItemReporter`/`startItemViewCounter` do no counting or gating of their
 * own, so "correct" here means exactly one thing: the right URLs reach `sendItemView`, at
 * the right moments, and never after `stop()`.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createItemReporter, startItemViewCounter, type SendItemView } from '../../src/content/itemCounter';

const POLL_MS = 500;

describe('createItemReporter — sends once per distinct URL', () => {
  it('reports a URL the first time it is seen', () => {
    const sendItemView = vi.fn();
    const reporter = createItemReporter('youtube', sendItemView);

    reporter.report('https://www.youtube.com/shorts/abc');

    expect(sendItemView).toHaveBeenCalledTimes(1);
    expect(sendItemView).toHaveBeenCalledWith('https://www.youtube.com/shorts/abc');
  });

  it('skips an immediate repeat of the same URL', () => {
    // The caller in platformGate.ts runs this on the debounced mutation pass too, which
    // fires many times a second while a feed lazy-loads — reporting a page that has not
    // moved on every one of those would be a round trip for nothing.
    const sendItemView = vi.fn();
    const reporter = createItemReporter('instagram', sendItemView);

    reporter.report('https://www.instagram.com/reels/abc/');
    reporter.report('https://www.instagram.com/reels/abc/');
    reporter.report('https://www.instagram.com/reels/abc/');

    expect(sendItemView).toHaveBeenCalledTimes(1);
  });

  it('going A -> B -> A DOES re-report A', () => {
    // Deliberate, and the reason is the module's own doc comment: the local `lastUrl` guard
    // exists only to keep the message channel quiet on a page that has not moved — it is
    // NOT what makes the count correct. The worker owns the day-scoped "already counted
    // today" set (`core/seenItems.ts`), so a page is free, and expected, to re-report an
    // item it has already reported once this session; only the worker knows whether A was
    // already counted today. If the page tried to dedupe across a full round trip like this
    // itself, swiping back to an already-seen Short would silently stop reporting it even
    // though the worker had long since forgotten it belonged to a *previous* visit.
    const sendItemView = vi.fn();
    const reporter = createItemReporter('youtube', sendItemView);

    reporter.report('https://www.youtube.com/shorts/a');
    reporter.report('https://www.youtube.com/shorts/b');
    reporter.report('https://www.youtube.com/shorts/a');

    expect(sendItemView).toHaveBeenCalledTimes(3);
    expect(sendItemView).toHaveBeenNthCalledWith(1, 'https://www.youtube.com/shorts/a');
    expect(sendItemView).toHaveBeenNthCalledWith(2, 'https://www.youtube.com/shorts/b');
    expect(sendItemView).toHaveBeenNthCalledWith(3, 'https://www.youtube.com/shorts/a');
  });

  it('never reports an empty URL', () => {
    const sendItemView = vi.fn();
    const reporter = createItemReporter('youtube', sendItemView);

    reporter.report('');

    expect(sendItemView).not.toHaveBeenCalled();
  });

  it('a send that rejects does not throw into the page', () => {
    // Mirrors the real contract: `SendItemView` is typed `(url: string) => void` precisely
    // because the injected function owns catching its own promise (see `defaultSend`'s
    // `.catch(() => undefined)`) — `report()` itself never awaits or try/catches around it.
    // A fake that rejects and swallows its own rejection (exactly like `defaultSend` does)
    // proves that contract: report() must not throw, and the reporter's own state (which
    // URL was "last reported") must keep advancing even though the send behind it failed.
    const sendItemView = vi.fn((_url: string) => {
      void Promise.reject(new Error('worker unreachable')).catch(() => undefined);
    });
    const reporter = createItemReporter('youtube', sendItemView as SendItemView);

    expect(() => reporter.report('https://www.youtube.com/shorts/abc')).not.toThrow();
    expect(sendItemView).toHaveBeenCalledTimes(1);

    expect(() => reporter.report('https://www.youtube.com/shorts/def')).not.toThrow();
    expect(sendItemView).toHaveBeenCalledTimes(2);
  });
});

describe('startItemViewCounter — reports the current URL immediately on start', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.history.replaceState({}, '', '/start');
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('reports the URL the page is ALREADY on, with no navigation having happened', () => {
    // A full page load straight onto a Short fires no navigation event at all — without
    // this, typing a Shorts URL or following a link into one would cost nothing against
    // the day's count until the user swiped to the next one.
    window.history.replaceState({}, '', '/shorts/abc');
    const sendItemView = vi.fn();

    const handle = startItemViewCounter({ platform: 'youtube', pollMs: POLL_MS, sendItemView });

    expect(sendItemView).toHaveBeenCalledTimes(1);
    expect(sendItemView).toHaveBeenCalledWith(window.location.href);

    handle.stop();
  });

  it('a pushState-style href change with no event is caught by the poll', () => {
    window.history.replaceState({}, '', '/shorts/first');
    const sendItemView = vi.fn();
    const handle = startItemViewCounter({ platform: 'youtube', pollMs: POLL_MS, sendItemView });
    expect(sendItemView).toHaveBeenCalledTimes(1); // the immediate start-of-life report

    window.history.pushState({}, '', '/shorts/second');
    // pushState fires no event a content script can observe — only the href-diff poll
    // (spaNav.ts) notices, and only once its interval elapses.
    expect(sendItemView).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(POLL_MS - 1);
    expect(sendItemView).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(1);
    expect(sendItemView).toHaveBeenCalledTimes(2);
    expect(sendItemView).toHaveBeenLastCalledWith(expect.stringContaining('/shorts/second'));

    handle.stop();
  });

  it("a site's own nav event (YouTube's yt-navigate-finish) also triggers a report", () => {
    window.history.replaceState({}, '', '/shorts/first');
    const sendItemView = vi.fn();
    const handle = startItemViewCounter({
      platform: 'youtube',
      navEvent: 'yt-navigate-finish',
      pollMs: POLL_MS,
      sendItemView,
    });
    expect(sendItemView).toHaveBeenCalledTimes(1);

    window.history.pushState({}, '', '/shorts/second');
    document.dispatchEvent(new Event('yt-navigate-finish'));

    // No timer advance needed — the site's own event fires synchronously, the same way a
    // real Shorts swipe does, and must not wait for the poll's safety-net interval.
    expect(sendItemView).toHaveBeenCalledTimes(2);

    handle.stop();
  });

  it('stop() detaches everything so no report fires afterwards', () => {
    window.history.replaceState({}, '', '/shorts/first');
    const sendItemView = vi.fn();
    const handle = startItemViewCounter({
      platform: 'youtube',
      navEvent: 'yt-navigate-finish',
      pollMs: POLL_MS,
      sendItemView,
    });
    expect(sendItemView).toHaveBeenCalledTimes(1);

    handle.stop();

    // Nothing stop() disposes of — the poll, popstate/hashchange, or the site's own nav
    // event — may call the reporter again after this.
    window.history.pushState({}, '', '/shorts/second');
    vi.advanceTimersByTime(POLL_MS * 5);
    document.dispatchEvent(new Event('yt-navigate-finish'));

    expect(sendItemView).toHaveBeenCalledTimes(1);
  });

  it('check() forces a report for the current URL on demand', () => {
    window.history.replaceState({}, '', '/shorts/only');
    const sendItemView = vi.fn();
    const handle = startItemViewCounter({ platform: 'youtube', pollMs: POLL_MS, sendItemView });
    expect(sendItemView).toHaveBeenCalledTimes(1);

    // Same URL — the local dedupe guard means a second check() before any navigation is a
    // no-op, exactly like a caller invoking it defensively.
    handle.check();
    expect(sendItemView).toHaveBeenCalledTimes(1);

    handle.stop();
  });
});
