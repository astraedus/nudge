// @vitest-environment jsdom
/**
 * Tests for the generic SPA-navigation layer (`src/content/spaNav.ts`) shared by every
 * non-YouTube platform content script.
 *
 * The contract under test, phrased as USER-VISIBLE behaviour per repo convention
 * (extension/CLAUDE.md "A test that asserts the bug is worse than no test" — state the
 * outcome, not the internal branch):
 *
 *  1. A same-document `pushState` navigation (no popstate, no custom event — the thing an
 *     isolated-world content script structurally cannot observe any other way) is still
 *     detected, by the href-diff poll.
 *  2. A navigation announced by a real event (`popstate`) is handled on the SAME tick —
 *     never delayed behind the mutation debounce. This is the specific bug class
 *     documented repo-wide ("A NAVIGATION MUST NOT BE DEBOUNCED"): routing detection
 *     through a debounce lets a mutation storm starve it for seconds.
 *  3. `dispose()` removes every listener and clears every timer — after disposal, nothing
 *     (not a further href change, not elapsed poll/debounce time, not a mutation) calls
 *     `onNavigate`/`onMutate` again.
 *
 * Repo default vitest environment is `node`; this file opts into jsdom for `history`/DOM.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { observeNavigation } from '../../src/content/spaNav';

const POLL_MS = 500;
const MUTATION_DEBOUNCE_MS = 250;

beforeEach(() => {
  vi.useFakeTimers();
  // Every test starts from a clean, known URL regardless of history left by a prior test.
  window.history.replaceState({}, '', '/start');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('observeNavigation — pushState-only navigation (no event fires)', () => {
  it('is detected by the href-diff poll, not immediately and not before the poll interval', () => {
    const onNavigate = vi.fn();
    const handle = observeNavigation({ onNavigate, pollMs: POLL_MS });

    window.history.pushState({}, '', '/reels/');

    // Not yet — pushState fires no event a content script can observe; only the poll
    // notices, and only once its interval elapses.
    expect(onNavigate).not.toHaveBeenCalled();

    vi.advanceTimersByTime(POLL_MS - 1);
    expect(onNavigate).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(onNavigate).toHaveBeenCalledTimes(1);

    handle.dispose();
  });

  it('fires again on a second distinct pushState, but not on a no-op poll tick', () => {
    const onNavigate = vi.fn();
    const handle = observeNavigation({ onNavigate, pollMs: POLL_MS });

    window.history.pushState({}, '', '/reels/');
    vi.advanceTimersByTime(POLL_MS);
    expect(onNavigate).toHaveBeenCalledTimes(1);

    // Nothing changed — the URL is stable, so further poll ticks must stay silent.
    vi.advanceTimersByTime(POLL_MS * 3);
    expect(onNavigate).toHaveBeenCalledTimes(1);

    window.history.pushState({}, '', '/explore/');
    vi.advanceTimersByTime(POLL_MS);
    expect(onNavigate).toHaveBeenCalledTimes(2);

    handle.dispose();
  });
});

describe('observeNavigation — a navigation is handled immediately, never debounced', () => {
  it('calls onNavigate synchronously inside the popstate handler, before any timer advances', () => {
    const onNavigate = vi.fn();
    const handle = observeNavigation({
      onNavigate,
      pollMs: POLL_MS,
      mutationDebounceMs: MUTATION_DEBOUNCE_MS,
    });

    window.history.pushState({}, '', '/foryou');
    window.dispatchEvent(new PopStateEvent('popstate'));

    // No timer advance at all — if this required the debounce, it would still be 0 here.
    expect(onNavigate).toHaveBeenCalledTimes(1);

    handle.dispose();
  });

  it('a mutation storm does not delay or suppress a same-tick popstate navigation', () => {
    const onNavigate = vi.fn();
    const onMutate = vi.fn();
    const handle = observeNavigation({
      onNavigate,
      onMutate,
      pollMs: POLL_MS,
      mutationDebounceMs: MUTATION_DEBOUNCE_MS,
    });

    // Simulate the page re-rendering heavily around the same moment as the navigation.
    for (let i = 0; i < 20; i++) {
      document.body.appendChild(document.createElement('div'));
    }

    window.history.pushState({}, '', '/watch');
    window.dispatchEvent(new PopStateEvent('popstate'));

    // The navigation callback already ran — unaffected by however much mutation churn is
    // queued or however long its debounce has left to run.
    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(onMutate).not.toHaveBeenCalled();

    handle.dispose();
  });

  it('fires exactly once even when both a custom nav event and popstate announce the same hop', () => {
    const onNavigate = vi.fn();
    const handle = observeNavigation({ onNavigate, navEvent: 'site-navigate-finish', pollMs: POLL_MS });

    window.history.pushState({}, '', '/live');
    document.dispatchEvent(new Event('site-navigate-finish'));
    window.dispatchEvent(new PopStateEvent('popstate'));

    expect(onNavigate).toHaveBeenCalledTimes(1);

    handle.dispose();
  });

  it('hashchange alone (no path change) also counts as a navigation', () => {
    const onNavigate = vi.fn();
    const handle = observeNavigation({ onNavigate, pollMs: POLL_MS });

    window.location.hash = '#comments';
    window.dispatchEvent(new HashChangeEvent('hashchange'));

    expect(onNavigate).toHaveBeenCalledTimes(1);

    handle.dispose();
  });
});

describe('observeNavigation — the debounced mutation pass is a re-apply safety net only', () => {
  it('calls onMutate once after mutationDebounceMs of DOM quiet following mutations', () => {
    const onMutate = vi.fn();
    const handle = observeNavigation({
      onNavigate: () => {},
      onMutate,
      mutationDebounceMs: MUTATION_DEBOUNCE_MS,
      pollMs: POLL_MS,
    });

    document.body.appendChild(document.createElement('span'));
    // jsdom's MutationObserver callback is a microtask; flush it before the debounce timer.
    return Promise.resolve().then(() => {
      expect(onMutate).not.toHaveBeenCalled();
      vi.advanceTimersByTime(MUTATION_DEBOUNCE_MS - 1);
      expect(onMutate).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1);
      expect(onMutate).toHaveBeenCalledTimes(1);
      handle.dispose();
    });
  });

  it('a burst of mutations collapses into a single debounced onMutate call', () => {
    const onMutate = vi.fn();
    const handle = observeNavigation({
      onNavigate: () => {},
      onMutate,
      mutationDebounceMs: MUTATION_DEBOUNCE_MS,
      pollMs: POLL_MS,
    });

    document.body.appendChild(document.createElement('span'));
    return Promise.resolve()
      .then(() => {
        vi.advanceTimersByTime(MUTATION_DEBOUNCE_MS / 2);
        document.body.appendChild(document.createElement('span'));
        return Promise.resolve();
      })
      .then(() => {
        // The second mutation re-armed the debounce, so the first window's expiry must
        // not have fired yet.
        vi.advanceTimersByTime(MUTATION_DEBOUNCE_MS / 2);
        expect(onMutate).not.toHaveBeenCalled();
        vi.advanceTimersByTime(MUTATION_DEBOUNCE_MS / 2);
        expect(onMutate).toHaveBeenCalledTimes(1);
        handle.dispose();
      });
  });
});

describe('observeNavigation — dispose() removes every listener and timer', () => {
  it('stops the poll from detecting a later pushState', () => {
    const onNavigate = vi.fn();
    const handle = observeNavigation({ onNavigate, pollMs: POLL_MS });

    handle.dispose();

    window.history.pushState({}, '', '/after-dispose');
    vi.advanceTimersByTime(POLL_MS * 5);

    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('stops popstate/hashchange from calling onNavigate', () => {
    const onNavigate = vi.fn();
    const handle = observeNavigation({ onNavigate, pollMs: POLL_MS });

    handle.dispose();

    window.history.pushState({}, '', '/after-dispose-2');
    window.dispatchEvent(new PopStateEvent('popstate'));
    window.location.hash = '#after';
    window.dispatchEvent(new HashChangeEvent('hashchange'));

    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('stops a pending debounced onMutate from ever firing', () => {
    const onMutate = vi.fn();
    const handle = observeNavigation({
      onNavigate: () => {},
      onMutate,
      mutationDebounceMs: MUTATION_DEBOUNCE_MS,
      pollMs: POLL_MS,
    });

    document.body.appendChild(document.createElement('span'));
    return Promise.resolve().then(() => {
      handle.dispose();
      vi.advanceTimersByTime(MUTATION_DEBOUNCE_MS * 5);
      expect(onMutate).not.toHaveBeenCalled();
    });
  });

  it('stops a custom nav event from calling onNavigate', () => {
    const onNavigate = vi.fn();
    const handle = observeNavigation({ onNavigate, navEvent: 'site-navigate-finish', pollMs: POLL_MS });

    handle.dispose();

    window.history.pushState({}, '', '/after-dispose-3');
    document.dispatchEvent(new Event('site-navigate-finish'));

    expect(onNavigate).not.toHaveBeenCalled();
  });
});

describe('observeNavigation — mutateOnIdlePoll keeps a QUIET page being re-checked', () => {
  /**
   * The failure this prevents, as the user would see it on YouTube: the post-navigation
   * mutation storm dies down, the page stops firing the observer, and the re-apply pass
   * stops running with it — so the channel verdict, the colour flip and the hide toggles
   * are frozen at whatever they were when the DOM last moved. YouTube's original
   * controller had a 1s safety-net interval whose `else` branch scheduled the debounced
   * pass on every tick that saw no navigation; this flag is that branch, and the six
   * other platforms deliberately do not set it.
   */
  it('still runs the re-apply pass on a page with no mutations and no navigation', () => {
    const onMutate = vi.fn();
    const handle = observeNavigation({
      onNavigate: () => {},
      onMutate,
      pollMs: POLL_MS,
      mutationDebounceMs: MUTATION_DEBOUNCE_MS,
      mutateOnIdlePoll: true,
    });

    // Nothing happens to the page at all. The poll tick alone must schedule the pass.
    vi.advanceTimersByTime(POLL_MS);
    expect(onMutate).not.toHaveBeenCalled();

    // ...and it is still DEBOUNCED, not immediate — same single timer the observer uses.
    vi.advanceTimersByTime(MUTATION_DEBOUNCE_MS);
    expect(onMutate).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(POLL_MS + MUTATION_DEBOUNCE_MS);
    expect(onMutate).toHaveBeenCalledTimes(2);

    handle.dispose();
  });

  it('is off by default, so a quiet page re-checks nothing for the other six platforms', () => {
    const onMutate = vi.fn();
    const handle = observeNavigation({
      onNavigate: () => {},
      onMutate,
      pollMs: POLL_MS,
      mutationDebounceMs: MUTATION_DEBOUNCE_MS,
    });

    vi.advanceTimersByTime(POLL_MS * 5 + MUTATION_DEBOUNCE_MS);
    expect(onMutate).not.toHaveBeenCalled();

    handle.dispose();
  });

  it('a poll tick that DID find a navigation reports it, and does not also fire the idle pass', () => {
    const onNavigate = vi.fn();
    const onMutate = vi.fn();
    const handle = observeNavigation({
      onNavigate,
      onMutate,
      pollMs: POLL_MS,
      mutationDebounceMs: MUTATION_DEBOUNCE_MS,
      mutateOnIdlePoll: true,
    });

    window.history.pushState({}, '', '/watch?v=idlepoll');
    vi.advanceTimersByTime(POLL_MS);

    expect(onNavigate).toHaveBeenCalledTimes(1);
    // A navigation is not an idle tick: the debounced pass is not scheduled by it.
    vi.advanceTimersByTime(MUTATION_DEBOUNCE_MS);
    expect(onMutate).not.toHaveBeenCalled();

    handle.dispose();
  });

  it('stops entirely after dispose, idle poll included', () => {
    const onMutate = vi.fn();
    const handle = observeNavigation({
      onNavigate: () => {},
      onMutate,
      pollMs: POLL_MS,
      mutationDebounceMs: MUTATION_DEBOUNCE_MS,
      mutateOnIdlePoll: true,
    });

    handle.dispose();
    vi.advanceTimersByTime(POLL_MS * 10 + MUTATION_DEBOUNCE_MS);
    expect(onMutate).not.toHaveBeenCalled();
  });
});
