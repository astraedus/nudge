// @vitest-environment jsdom
/**
 * Fixture tests for the YouTube selector layer (ext-03 §6 pattern 4).
 *
 * The contract these tests defend: when YouTube renames a wrapper, CI goes red and a
 * human updates the chain — the extension does NOT quietly stop blocking Shorts while
 * still reporting itself as on. That failure mode is invisible to users until they
 * notice they have been scrolling for an hour, which is the exact thing Nudge sells
 * against.
 *
 * Fixtures are hand-authored from the documented taxonomy, not scraped — see the header
 * of tests/content/fixtures/youtube.ts.
 *
 * The repo's default vitest environment is `node` (src/core is pure); this file opts into
 * jsdom with the directive above.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  HIDDEN_CLASS,
  SHORTS_FALLBACK_SURFACE,
  SHORTS_SURFACES,
  fallbackWarningMessage,
  pageTypeFor,
  queryWithFallback,
  resetFallbackWarnings,
  type SelectorRule,
} from '../../src/content/selectors';
import {
  BAIL_LABEL,
  applyShortsHiding,
  createShortsOverlay,
  shouldGateShorts,
} from '../../src/content/youtube';
import {
  HOME_FEED_HTML,
  HOME_FEED_RENAMED_HTML,
  MINI_GUIDE_HTML,
  SEARCH_RESULTS_HTML,
  SHORTS_PLAYER_HTML,
  SUBSCRIPTIONS_FEED_HTML,
  WATCH_PAGE_HTML,
} from './fixtures/youtube';

/** Mount a fixture in a detached root so tests never fight over document.body. */
function mount(html: string): HTMLElement {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.replaceChildren(root);
  return root;
}

function surface(pageType: keyof typeof SHORTS_SURFACES, id: string) {
  const found = SHORTS_SURFACES[pageType].find((s) => s.id === id);
  if (!found) throw new Error(`no surface "${id}" for page type "${pageType}"`);
  return found;
}

/** Every normal (non-Shorts) card in the fixture. These must never be touched. */
function normalCards(root: HTMLElement): Element[] {
  return Array.from(root.querySelectorAll('.normal-video'));
}

beforeEach(() => {
  resetFallbackWarnings();
});

describe('pageTypeFor', () => {
  it('classifies the standard YouTube surfaces', () => {
    expect(pageTypeFor('https://www.youtube.com/')).toBe('home');
    expect(pageTypeFor('https://www.youtube.com/feed/subscriptions')).toBe('subscriptions');
    expect(pageTypeFor('https://www.youtube.com/results?search_query=cats')).toBe('search');
    expect(pageTypeFor('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('watch');
    expect(pageTypeFor('https://www.youtube.com/channel/UC1234567890')).toBe('channel');
    expect(pageTypeFor('https://www.youtube.com/c/SomeChannel')).toBe('channel');
    expect(pageTypeFor('https://www.youtube.com/user/SomeUser')).toBe('channel');
    expect(pageTypeFor('https://www.youtube.com/@somehandle')).toBe('channel');
    expect(pageTypeFor('https://www.youtube.com/@somehandle/videos')).toBe('channel');
    expect(pageTypeFor('https://www.youtube.com/feed/history')).toBe('other');
  });

  it('recognizes /shorts/<id> and its bare/mobile forms', () => {
    expect(pageTypeFor('https://www.youtube.com/shorts/abc123XYZ')).toBe('shorts');
    expect(pageTypeFor('https://www.youtube.com/shorts/abc123XYZ?feature=share')).toBe('shorts');
    expect(pageTypeFor('https://m.youtube.com/shorts/abc123XYZ')).toBe('shorts');
    expect(pageTypeFor('https://www.youtube.com/shorts/')).toBe('shorts');
    expect(pageTypeFor('https://www.youtube.com/shorts')).toBe('shorts');
  });

  it('does NOT classify a video merely titled or searched as "shorts" as Shorts', () => {
    // The precision case: "shorts" in a query string, a fragment or a longer path
    // segment is not the Shorts surface. Gating these would block ordinary videos.
    expect(pageTypeFor('https://www.youtube.com/watch?v=abc&title=shorts')).toBe('watch');
    expect(pageTypeFor('https://www.youtube.com/results?search_query=shorts')).toBe('search');
    expect(pageTypeFor('https://www.youtube.com/watch?v=shorts')).toBe('watch');
    expect(pageTypeFor('https://www.youtube.com/watch?v=abc#shorts')).toBe('watch');
    expect(pageTypeFor('https://www.youtube.com/shortsomething')).toBe('other');
    expect(pageTypeFor('https://www.youtube.com/@shorts')).toBe('channel');
  });

  it('ignores non-YouTube hosts and unparseable input', () => {
    expect(pageTypeFor('https://notyoutube.com/shorts/abc')).toBe('other');
    expect(pageTypeFor('https://youtube.com.evil.example/shorts/abc')).toBe('other');
    expect(pageTypeFor('chrome-extension://abcdef/blocked.html')).toBe('other');
    expect(pageTypeFor('not a url')).toBe('other');
    // The apex and every subdomain ARE YouTube.
    expect(pageTypeFor('https://youtube.com/shorts/abc')).toBe('shorts');
    expect(pageTypeFor('https://music.youtube.com/')).toBe('home');
  });
});

describe('selector chains match the intended elements per page type', () => {
  it('home: matches the Shorts shelf without touching normal video cards', () => {
    const root = mount(HOME_FEED_HTML);
    const result = queryWithFallback(root, surface('home', 'shorts-shelf').chain, {
      surfaceId: 'shorts-shelf',
      warn: () => {},
    });

    expect(result.usedFallback).toBe(false);
    expect(result.matchedSelector).toBe(
      'ytd-rich-section-renderer:has(>div>ytd-rich-shelf-renderer)',
    );
    expect(result.elements).toHaveLength(1);
    expect(result.elements[0]?.getAttribute('data-testid')).toBe('home-shelf');
    for (const card of normalCards(root)) {
      expect(result.elements).not.toContain(card);
    }
  });

  it('subscriptions: matches the rich-grid Shorts group, not the plain grid video', () => {
    const root = mount(SUBSCRIPTIONS_FEED_HTML);
    const result = queryWithFallback(root, surface('subscriptions', 'shorts-grid-group').chain, {
      surfaceId: 'shorts-grid-group',
      warn: () => {},
    });

    expect(result.usedFallback).toBe(false);
    expect(result.elements.map((e) => e.getAttribute('data-testid'))).toEqual(['subs-group']);
    expect(root.querySelector('[data-testid="subs-grid-normal"]')).not.toBeNull();
  });

  it('search: matches ytd-reel-shelf-renderer and leaves both normal results alone', () => {
    const root = mount(SEARCH_RESULTS_HTML);
    const result = queryWithFallback(root, surface('search', 'shorts-shelf').chain, {
      surfaceId: 'shorts-shelf',
      warn: () => {},
    });

    expect(result.matchedSelector).toBe('ytd-reel-shelf-renderer');
    expect(result.usedFallback).toBe(false);
    expect(result.elements.map((e) => e.getAttribute('data-testid'))).toEqual([
      'search-reel-shelf',
    ]);
    expect(normalCards(root)).toHaveLength(2);
  });

  it('watch: matches only the Shorts recommendation in the sidebar', () => {
    const root = mount(WATCH_PAGE_HTML);
    const result = queryWithFallback(root, surface('watch', 'shorts-cards').chain, {
      surfaceId: 'shorts-cards',
      warn: () => {},
    });

    expect(result.usedFallback).toBe(false);
    expect(result.matchedSelector).toBe('ytd-compact-video-renderer:has(a[href^="/shorts/"])');
    expect(result.elements.map((e) => e.getAttribute('data-testid'))).toEqual([
      'watch-shorts-rec',
    ]);
  });

  it('nav: matches the mini-guide Shorts entry, not Home or Subscriptions', () => {
    const root = mount(MINI_GUIDE_HTML);
    const result = queryWithFallback(root, surface('home', 'shorts-nav').chain, {
      surfaceId: 'shorts-nav',
      warn: () => {},
    });

    // Assert the OUTCOME (the Shorts entry was found, without falling back to the generic
    // catch-all), not WHICH rung won: the chain is deliberately reordered as YouTube's DOM
    // changes, and pinning the winning selector string turns every legitimate refresh into
    // a false failure.
    expect(result.usedFallback).toBe(false);
    expect(result.elements).toHaveLength(1);
    expect(result.elements[0]?.getAttribute('title')).toBe('Shorts');
  });

  it('shorts player page: has no hiding surfaces beyond the nav (it gets gated instead)', () => {
    const root = mount(SHORTS_PLAYER_HTML);
    expect(SHORTS_SURFACES.shorts.map((s) => s.id)).toEqual(['shorts-nav']);
    expect(root.querySelector('ytd-shorts')).not.toBeNull();
  });

  it('returns an empty result when nothing in the chain matches', () => {
    const root = mount('<div><p>no youtube here</p></div>');
    const warn = vi.fn();
    const result = queryWithFallback(root, surface('home', 'shorts-shelf').chain, {
      surfaceId: 'shorts-shelf',
      warn,
    });

    expect(result.elements).toEqual([]);
    expect(result.matchedSelector).toBeNull();
    expect(result.usedFallback).toBe(false);
    expect(warn).not.toHaveBeenCalled();
  });

  it('skips a rung the CSS engine cannot parse instead of aborting the chain', () => {
    const root = mount(HOME_FEED_HTML);
    const chain: SelectorRule[] = [
      { selector: 'ytd-rich-section-renderer:::broken' },
      { selector: 'ytd-rich-section-renderer' },
    ];
    const result = queryWithFallback(root, chain, { warn: () => {} });
    expect(result.matchedSelector).toBe('ytd-rich-section-renderer');
  });
});

describe('fallback path (the upstream-churn scenario)', () => {
  it('warns loudly, naming the selector, when ONLY the generic fallback matches', () => {
    // Every ytd-* Shorts wrapper in this fixture has been renamed — precisely what
    // Google does periodically, and the day this extension quietly stops working.
    const root = mount(HOME_FEED_RENAMED_HTML);
    const warn = vi.fn();

    const result = queryWithFallback(root, SHORTS_FALLBACK_SURFACE.chain, {
      surfaceId: SHORTS_FALLBACK_SURFACE.id,
      warn,
    });

    expect(result.usedFallback).toBe(true);
    expect(result.matchedSelector).toBe('[href^="/shorts/"]');
    expect(warn).toHaveBeenCalledTimes(1);

    const message = warn.mock.calls[0]?.[0] as string;
    expect(message).toBe(fallbackWarningMessage('shorts-fallback', '[href^="/shorts/"]'));
    expect(message).toContain('[href^="/shorts/"]');
    expect(message).toContain('shorts-fallback');
    expect(message).toMatch(/YouTube DOM changed/i);
    expect(message).toMatch(/degraded/i);
  });

  it('none of the specific chains match once the wrappers are renamed', () => {
    const root = mount(HOME_FEED_RENAMED_HTML);
    const warn = vi.fn();

    for (const s of SHORTS_SURFACES.home) {
      const result = queryWithFallback(root, s.chain, { surfaceId: s.id, warn });
      expect(result.matchedSelector).toBeNull();
    }
    // Specific chains carry no fallback rung, so a page without that surface is silent.
    expect(warn).not.toHaveBeenCalled();
  });

  it('does NOT warn on a healthy page — no crying wolf', () => {
    const warn = vi.fn();

    for (const [html, pageType] of [
      [HOME_FEED_HTML, 'home'],
      [SUBSCRIPTIONS_FEED_HTML, 'subscriptions'],
      [SEARCH_RESULTS_HTML, 'search'],
      [WATCH_PAGE_HTML, 'watch'],
    ] as const) {
      const root = mount(html);
      const result = applyShortsHiding(
        root,
        { enabled: true, hideShortsShelf: true },
        { pageType, warn },
      );
      expect(result.degradedSurfaces).toEqual([]);
    }

    expect(warn).not.toHaveBeenCalled();
  });

  it('the fallback still finds every Shorts link and still spares normal videos', () => {
    const root = mount(HOME_FEED_RENAMED_HTML);
    const result = queryWithFallback(root, SHORTS_FALLBACK_SURFACE.chain, {
      surfaceId: SHORTS_FALLBACK_SURFACE.id,
      warn: () => {},
    });

    expect(result.usedFallback).toBe(true);
    // Both renamed Shorts cards plus the renamed nav entry all link to /shorts/.
    expect(result.elements).toHaveLength(3);
    for (const element of result.elements) {
      expect(element.getAttribute('href')?.startsWith('/shorts/')).toBe(true);
    }
    for (const card of normalCards(root)) {
      expect(result.elements).not.toContain(card);
    }
  });

  it('applyShortsHiding warns, hides anyway, and flags the degraded surface', () => {
    const root = mount(HOME_FEED_RENAMED_HTML);
    const warn = vi.fn();

    const result = applyShortsHiding(
      root,
      { enabled: true, hideShortsShelf: true },
      { pageType: 'home', warn },
    );

    expect(result.degradedSurfaces).toEqual(['shorts-fallback']);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0]?.[0]).toContain('[href^="/shorts/"]');

    // Degraded still means blocking: every Shorts anchor is hidden...
    expect(result.hidden).toBe(3);
    expect(root.querySelectorAll(`.${HIDDEN_CLASS}`)).toHaveLength(3);
    // ...and normal videos are still untouched.
    for (const card of normalCards(root)) {
      expect(card.classList.contains(HIDDEN_CLASS)).toBe(false);
    }
  });

  it('the default warn sink dedupes so the observer cannot flood the console', () => {
    const root = mount(HOME_FEED_RENAMED_HTML);
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      for (let i = 0; i < 5; i += 1) {
        applyShortsHiding(root, { enabled: true, hideShortsShelf: true }, { pageType: 'home' });
      }
      expect(spy).toHaveBeenCalledTimes(1);
    } finally {
      spy.mockRestore();
    }
  });
});

describe('applyShortsHiding', () => {
  it('adds the hidden class to the Shorts shelf and nav, never to normal videos', () => {
    const root = mount(HOME_FEED_HTML);

    const result = applyShortsHiding(
      root,
      { enabled: true, hideShortsShelf: true },
      { pageType: 'home', warn: () => {} },
    );

    expect(result.hidden).toBeGreaterThan(0);
    expect(result.degradedSurfaces).toEqual([]);
    expect(
      root.querySelector('[data-testid="home-shelf"]')?.classList.contains(HIDDEN_CLASS),
    ).toBe(true);
    for (const card of normalCards(root)) {
      expect(card.classList.contains(HIDDEN_CLASS)).toBe(false);
    }
  });

  it('is reversible: hiding is a class toggle, and no node is ever removed', () => {
    const root = mount(HOME_FEED_HTML);
    const before = root.querySelectorAll('*').length;

    applyShortsHiding(
      root,
      { enabled: true, hideShortsShelf: true },
      { pageType: 'home', warn: () => {} },
    );
    expect(root.querySelectorAll(`.${HIDDEN_CLASS}`).length).toBeGreaterThan(0);

    const off = applyShortsHiding(
      root,
      { enabled: true, hideShortsShelf: false },
      { pageType: 'home', warn: () => {} },
    );

    expect(off.revealed).toBeGreaterThan(0);
    expect(root.querySelectorAll(`.${HIDDEN_CLASS}`)).toHaveLength(0);
    expect(root.querySelectorAll('*').length).toBe(before);
    expect(root.querySelector('[data-testid="home-shelf"]')).not.toBeNull();
  });

  it('is idempotent — a second pass hides nothing new', () => {
    const root = mount(SUBSCRIPTIONS_FEED_HTML);
    const opts = { pageType: 'subscriptions' as const, warn: () => {} };
    const first = applyShortsHiding(root, { enabled: true, hideShortsShelf: true }, opts);
    const second = applyShortsHiding(root, { enabled: true, hideShortsShelf: true }, opts);

    expect(first.hidden).toBeGreaterThan(0);
    expect(second.hidden).toBe(0);
  });

  it('does nothing (and unhides) when Nudge is globally disabled', () => {
    const root = mount(HOME_FEED_HTML);
    applyShortsHiding(
      root,
      { enabled: true, hideShortsShelf: true },
      { pageType: 'home', warn: () => {} },
    );

    const result = applyShortsHiding(
      root,
      { enabled: false, hideShortsShelf: true },
      { pageType: 'home', warn: () => {} },
    );

    expect(result.hidden).toBe(0);
    expect(result.revealed).toBeGreaterThan(0);
    expect(root.querySelectorAll(`.${HIDDEN_CLASS}`)).toHaveLength(0);
  });

  it('derives the page type from the document URL when none is passed', () => {
    const root = mount(SEARCH_RESULTS_HTML);
    const result = applyShortsHiding(
      root,
      { enabled: true, hideShortsShelf: true },
      { url: 'https://www.youtube.com/results?search_query=cats', warn: () => {} },
    );

    expect(result.hidden).toBeGreaterThan(0);
    expect(
      root.querySelector('[data-testid="search-reel-shelf"]')?.classList.contains(HIDDEN_CLASS),
    ).toBe(true);
    expect(normalCards(root).every((c) => !c.classList.contains(HIDDEN_CLASS))).toBe(true);
  });
});

describe('shouldGateShorts', () => {
  const shortsUrl = 'https://www.youtube.com/shorts/abc123XYZ';
  const watchUrl = 'https://www.youtube.com/watch?v=abc123XYZ';
  const homeUrl = 'https://www.youtube.com/';

  it('gates /shorts/* in every blocking mode', () => {
    for (const mode of ['HARD_BLOCK', 'DELAY', 'BREATHING'] as const) {
      expect(shouldGateShorts(shortsUrl, { enabled: true, shortsMode: mode })).toBe(true);
    }
  });

  it('never gates when the resolved mode is ALLOW', () => {
    expect(shouldGateShorts(shortsUrl, { enabled: true, shortsMode: 'ALLOW' })).toBe(false);
  });

  it('never gates when Nudge is globally disabled', () => {
    for (const mode of ['HARD_BLOCK', 'DELAY', 'BREATHING'] as const) {
      expect(shouldGateShorts(shortsUrl, { enabled: false, shortsMode: mode })).toBe(false);
    }
  });

  it('never gates a non-Shorts surface, whatever the mode', () => {
    for (const url of [watchUrl, homeUrl, 'https://www.youtube.com/results?search_query=shorts']) {
      expect(shouldGateShorts(url, { enabled: true, shortsMode: 'HARD_BLOCK' })).toBe(false);
    }
  });

  it('never gates a non-YouTube page', () => {
    expect(
      shouldGateShorts('https://example.com/shorts/abc', {
        enabled: true,
        shortsMode: 'HARD_BLOCK',
      }),
    ).toBe(false);
  });
});

describe('the /shorts/* interstitial overlay', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('offers "I changed my mind" verbatim in every mode (Android parity)', () => {
    mount(SHORTS_PLAYER_HTML);

    for (const mode of ['HARD_BLOCK', 'DELAY', 'BREATHING'] as const) {
      const overlay = createShortsOverlay(
        document,
        { shortsMode: mode, shortsDelaySeconds: 5 },
        { onComplete: () => {}, onBail: () => {} },
      );
      const bail = overlay.element.querySelector('button');
      // Exact wording, not "Nevermind" or "Go back" — it matches the Android app.
      expect(bail?.textContent).toBe('I changed my mind');
      expect(BAIL_LABEL).toBe('I changed my mind');
      overlay.dispose();
    }
  });

  it('DELAY counts down and then releases the gate', () => {
    vi.useFakeTimers();
    mount(SHORTS_PLAYER_HTML);
    const onComplete = vi.fn();

    const overlay = createShortsOverlay(
      document,
      { shortsMode: 'DELAY', shortsDelaySeconds: 3 },
      { onComplete, onBail: () => {} },
    );
    document.body.append(overlay.element);

    const counter = overlay.element.querySelector('.nudge-overlay__count');
    expect(counter?.textContent).toBe('3');
    vi.advanceTimersByTime(2000);
    expect(counter?.textContent).toBe('1');
    expect(onComplete).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1000);
    expect(onComplete).toHaveBeenCalledTimes(1);
    // The timer is cleared on completion — no runaway interval on a swipe-heavy page.
    vi.advanceTimersByTime(10_000);
    expect(onComplete).toHaveBeenCalledTimes(1);
    overlay.dispose();
  });

  it('HARD_BLOCK has no countdown and never self-releases', () => {
    vi.useFakeTimers();
    mount(SHORTS_PLAYER_HTML);
    const onComplete = vi.fn();

    const overlay = createShortsOverlay(
      document,
      { shortsMode: 'HARD_BLOCK', shortsDelaySeconds: 3 },
      { onComplete, onBail: () => {} },
    );

    expect(overlay.element.querySelector('.nudge-overlay__count')).toBeNull();
    expect(overlay.element.querySelector('.nudge-overlay__breath')).toBeNull();
    vi.advanceTimersByTime(60_000);
    expect(onComplete).not.toHaveBeenCalled();
    // The footer names the acting rule — Android's "Rule: X" transparency parity.
    expect(overlay.element.querySelector('.nudge-overlay__footer')?.textContent).toContain(
      'Hard Block',
    );
    overlay.dispose();
  });

  it('BREATHING runs a 4s-in/4s-out cycle and shows the remaining time', () => {
    vi.useFakeTimers();
    mount(SHORTS_PLAYER_HTML);
    const onComplete = vi.fn();

    const overlay = createShortsOverlay(
      document,
      { shortsMode: 'BREATHING', shortsDelaySeconds: 16 },
      { onComplete, onBail: () => {} },
    );
    const phase = overlay.element.querySelector('.nudge-overlay__phase');
    const remaining = overlay.element.querySelector('.nudge-overlay__remaining');

    expect(phase?.textContent).toBe('Breathe in');
    expect(remaining?.textContent).toBe('16s remaining');

    vi.advanceTimersByTime(4200);
    expect(phase?.textContent).toBe('Breathe out');
    vi.advanceTimersByTime(4000);
    expect(phase?.textContent).toBe('Breathe in');

    vi.advanceTimersByTime(16_000);
    expect(onComplete).toHaveBeenCalledTimes(1);
    overlay.dispose();
  });

  it('the bail button fires onBail and never hides itself as a Shorts surface', () => {
    const root = mount(SHORTS_PLAYER_HTML);
    const onBail = vi.fn();
    const overlay = createShortsOverlay(
      document,
      { shortsMode: 'HARD_BLOCK', shortsDelaySeconds: 5 },
      { onComplete: () => {}, onBail },
    );
    root.append(overlay.element);

    overlay.element.querySelector('button')?.click();
    expect(onBail).toHaveBeenCalledTimes(1);

    root.append(overlay.element);
    applyShortsHiding(
      root,
      { enabled: true, hideShortsShelf: true },
      { pageType: 'shorts', warn: () => {} },
    );
    expect(overlay.element.classList.contains(HIDDEN_CLASS)).toBe(false);
    overlay.dispose();
  });
});
