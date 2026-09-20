// @vitest-environment jsdom
/**
 * Tests for the LinkedIn content script (`src/content/linkedin/`).
 *
 * LinkedIn has exactly ONE gate (`feed`) and ONE hide surface (`suggestedFollows`).
 * Phrased as USER-VISIBLE outcomes throughout (repo rule): "hidden" means the element
 * carries `hideClassFor('suggestedFollows')`, the exact class `content/linkedin.css` keys
 * `display: none` off; "gated" means an interstitial with `overlayIdFor` is
 * attached to the document.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { overlayIdFor, hideClassFor } from '../../../src/content/platformGate';
import { resetFallbackWarnings } from '../../../src/content/selectors';
import { initLinkedinContentScript } from '../../../src/content/linkedin';
import type { SiteConfig } from '../../../src/core/protocol';
import { LINKEDIN_FEED_HTML } from '../fixtures/linkedin/feedPage';

const OVERLAY_ID = overlayIdFor('linkedin');

const POLL_MS = 500;

function mount(): void {
  const root = document.createElement('div');
  root.innerHTML = LINKEDIN_FEED_HTML;
  document.body.replaceChildren(root);
}

function followsModule(): Element {
  return document.querySelector('.scaffold-layout__aside .feed-follows-module')!;
}

function configWith(overrides: Partial<SiteConfig>): SiteConfig {
  return {
    enabled: true,
    domain: 'linkedin.com',
    platform: 'linkedin',
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

describe('LinkedIn content script — suggestedFollows hide toggle', () => {
  it('hides only the suggested-follows rail when the toggle is on', async () => {
    mount();
    window.history.replaceState({}, '', '/in/someone/');

    const fetchConfig = vi.fn(async () => configWith({ hides: { suggestedFollows: true } }));
    const controller = initLinkedinContentScript(document, fetchConfig);
    await controller.reload();

    expect(followsModule().classList.contains(hideClassFor('suggestedFollows'))).toBe(true);
    expect(document.querySelector('[data-testid="feed-post"]')!.className).toBe('');

    controller.stop();
  });

  it('reveals it again the moment the toggle turns back off', async () => {
    mount();
    window.history.replaceState({}, '', '/in/someone/');

    let hides: Partial<Record<string, boolean>> = { suggestedFollows: true };
    const fetchConfig = vi.fn(async () => configWith({ hides }));
    const controller = initLinkedinContentScript(document, fetchConfig);
    await controller.reload();
    expect(followsModule().classList.contains(hideClassFor('suggestedFollows'))).toBe(true);

    hides = { suggestedFollows: false };
    await controller.reload();

    expect(followsModule().classList.contains(hideClassFor('suggestedFollows'))).toBe(false);

    controller.stop();
  });
});

describe('LinkedIn content script — the feed gate', () => {
  it('gates the feed in a BlockMode', async () => {
    mount();
    window.history.replaceState({}, '', '/feed/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'feed', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );
    const controller = initLinkedinContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });

  it('does not gate when the resolved mode is ALLOW', async () => {
    mount();
    window.history.replaceState({}, '', '/feed/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'feed', mode: 'ALLOW', delaySeconds: 15, limitReached: false }] }),
    );
    const controller = initLinkedinContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });

  it('does not gate a profile page — the URL matches no gate', async () => {
    mount();
    window.history.replaceState({}, '', '/in/someone/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'feed', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );
    const controller = initLinkedinContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });
});

describe('LinkedIn content script — SPA navigation', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('gates the NEW url after a pushState from a non-gated path to /feed/', async () => {
    mount();
    window.history.replaceState({}, '', '/in/someone/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'feed', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );
    const controller = initLinkedinContentScript(document, fetchConfig);
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    window.history.pushState({}, '', '/feed/');
    // pushState fires no event an isolated-world content script can observe directly —
    // only the href-diff poll inside spaNav.ts notices it, and only once it ticks.
    await vi.advanceTimersByTimeAsync(POLL_MS);

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });
});
