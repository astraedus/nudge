// @vitest-environment jsdom
/**
 * Tests for the Reddit content script (`src/content/reddit/`).
 *
 * Reddit has exactly ONE gate (`home` — the front page, r/popular, r/all) and ZERO hide
 * surfaces (`content/reddit/selectors.ts` explains why). Phrased as USER-VISIBLE outcomes
 * throughout (repo rule): "gated" means an interstitial with `overlayIdFor` is
 * attached to the document; "reachable" means it is not.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { overlayIdFor } from '../../../src/content/platformGate';
import { resetFallbackWarnings } from '../../../src/content/selectors';
import { initRedditContentScript } from '../../../src/content/reddit';
import { HIDE_SURFACES } from '../../../src/content/reddit/selectors';
import type { SiteConfig } from '../../../src/core/protocol';
import { REDDIT_PAGE_HTML } from '../fixtures/reddit/feedPage';

const OVERLAY_ID = overlayIdFor('reddit');

const POLL_MS = 500;

function mount(): void {
  const root = document.createElement('div');
  root.innerHTML = REDDIT_PAGE_HTML;
  document.body.replaceChildren(root);
}

function configWith(overrides: Partial<SiteConfig>): SiteConfig {
  return {
    enabled: true,
    domain: 'reddit.com',
    platform: 'reddit',
    siteMode: 'ALLOW',
    siteDelaySeconds: 15,
    siteApplies: false,
    siteLimitReached: false,
    grayscale: false,
    gates: [],
    hides: {},
    youtube: null,
    ...overrides,
  };
}

beforeEach(() => {
  resetFallbackWarnings();
});

describe('HIDE_SURFACES — Reddit ships none, deliberately', () => {
  it('is empty, matching the registry\'s own reddit.hides: []', () => {
    // A future accidental addition here without a matching registry entry should fail
    // review, not slip in silently — this assertion is the tripwire.
    expect(HIDE_SURFACES).toEqual([]);
  });
});

describe('Reddit content script — the home gate', () => {
  it.each(['/', '/r/popular', '/r/all'])(
    'gates %s in a BlockMode',
    async (path) => {
      mount();
      window.history.replaceState({}, '', path);

      const fetchConfig = vi.fn(async () =>
        configWith({ gates: [{ id: 'home', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
      );

      const controller = initRedditContentScript(document, fetchConfig);
      await controller.reload();

      expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

      controller.stop();
    },
  );

  it.each(['/r/programming/', '/r/programming/comments/abc123/some_title/'])(
    'does NOT gate %s — individual subreddits and comment threads stay reachable',
    async (path) => {
      mount();
      window.history.replaceState({}, '', path);

      const fetchConfig = vi.fn(async () =>
        configWith({ gates: [{ id: 'home', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
      );

      const controller = initRedditContentScript(document, fetchConfig);
      await controller.reload();

      expect(document.getElementById(OVERLAY_ID)).toBeNull();

      controller.stop();
    },
  );
});

describe('Reddit content script — SPA navigation', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('gates the NEW url after a pushState from a non-gated subreddit to a gated feed', async () => {
    mount();
    window.history.replaceState({}, '', '/r/programming/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'home', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
    );

    const controller = initRedditContentScript(document, fetchConfig);
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    window.history.pushState({}, '', '/r/all/');
    // pushState fires no event an isolated-world content script can observe directly —
    // only the href-diff poll inside spaNav.ts notices it, and only once it ticks.
    await vi.advanceTimersByTimeAsync(POLL_MS);

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });
});
