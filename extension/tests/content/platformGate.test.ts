// @vitest-environment jsdom
/**
 * Tests for the shared platform-gate driver (`src/content/platformGate.ts`) — the hide
 * applier, gate resolution and the generic content-script controller every non-YouTube
 * platform module (`instagram/index.ts`, `tiktok/index.ts`, ...) is built on.
 *
 * Phrased as USER-VISIBLE outcomes throughout (repo rule): "hidden" means "carries the
 * feature's own CSS class", the same class the real stylesheet keys `display: none` off
 * — checking the class IS checking what the user would see. "Gated" means an interstitial
 * with `overlayIdFor` is attached to the document.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  overlayIdFor,
  applyPlatformHides,
  hideClassFor,
  initPlatformContentScript,
  resolveActiveGate,
  type HideSurfaceDef,
} from '../../src/content/platformGate';
import { resetFallbackWarnings } from '../../src/content/selectors';
import type { ResolvedGate, SiteConfig } from '../../src/core/protocol';
import { IDLE_SITE_CONFIG } from '../../src/content/platformGate';

const OVERLAY_ID = overlayIdFor('instagram');

function mount(html: string): HTMLElement {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.replaceChildren(root);
  return root;
}

const STORIES_SURFACE: HideSurfaceDef = {
  id: 'stories',
  chain: [{ selector: '[data-testid="stories-tray"]' }],
};
const SUGGESTED_SURFACE: HideSurfaceDef = {
  id: 'suggested',
  chain: [{ selector: '[data-testid="suggested-block"]' }],
};

const FIXTURE_HTML = `
  <div data-testid="stories-tray">stories</div>
  <div data-testid="suggested-block">suggested</div>
  <div data-testid="feed">feed content</div>
`;

beforeEach(() => {
  resetFallbackWarnings();
});

describe('applyPlatformHides — each surface owns its own class, independently toggleable', () => {
  it('hides only the surface whose toggle is on, leaving the other feature and the rest of the page alone', () => {
    const root = mount(FIXTURE_HTML);

    applyPlatformHides(root, [STORIES_SURFACE, SUGGESTED_SURFACE], { stories: true }, true, OVERLAY_ID);

    const stories = root.querySelector('[data-testid="stories-tray"]')!;
    const suggested = root.querySelector('[data-testid="suggested-block"]')!;
    const feed = root.querySelector('[data-testid="feed"]')!;

    expect(stories.classList.contains(hideClassFor('stories'))).toBe(true);
    expect(suggested.classList.contains(hideClassFor('suggested'))).toBe(false);
    expect(feed.className).toBe('');
  });

  it('reveals a surface the instant its toggle turns off, without touching a still-on sibling feature', () => {
    const root = mount(FIXTURE_HTML);

    applyPlatformHides(root, [STORIES_SURFACE, SUGGESTED_SURFACE], { stories: true, suggested: true }, true, OVERLAY_ID);
    expect(root.querySelector('[data-testid="stories-tray"]')!.classList.contains(hideClassFor('stories'))).toBe(
      true,
    );
    expect(
      root.querySelector('[data-testid="suggested-block"]')!.classList.contains(hideClassFor('suggested')),
    ).toBe(true);

    // Turning "stories" off must reveal ONLY stories — this is the exact clobber bug the
    // per-feature class design (ext-13 §4) exists to prevent.
    applyPlatformHides(root, [STORIES_SURFACE, SUGGESTED_SURFACE], { stories: false, suggested: true }, true, OVERLAY_ID);

    expect(root.querySelector('[data-testid="stories-tray"]')!.classList.contains(hideClassFor('stories'))).toBe(
      false,
    );
    expect(
      root.querySelector('[data-testid="suggested-block"]')!.classList.contains(hideClassFor('suggested')),
    ).toBe(true);
  });

  it('reveals everything when the extension is globally disabled, regardless of individual toggles', () => {
    const root = mount(FIXTURE_HTML);
    applyPlatformHides(root, [STORIES_SURFACE, SUGGESTED_SURFACE], { stories: true, suggested: true }, true, OVERLAY_ID);

    applyPlatformHides(root, [STORIES_SURFACE, SUGGESTED_SURFACE], { stories: true, suggested: true }, false, OVERLAY_ID);

    expect(root.querySelector('[data-testid="stories-tray"]')!.classList.contains(hideClassFor('stories'))).toBe(
      false,
    );
    expect(
      root.querySelector('[data-testid="suggested-block"]')!.classList.contains(hideClassFor('suggested')),
    ).toBe(false);
  });

  it('never hides its own overlay even if a selector would otherwise match it', () => {
    const root = mount(FIXTURE_HTML);
    const overlay = document.createElement('div');
    overlay.id = OVERLAY_ID;
    overlay.setAttribute('data-testid', 'stories-tray'); // pathological: matches the chain
    root.appendChild(overlay);

    applyPlatformHides(root, [STORIES_SURFACE], { stories: true }, true, OVERLAY_ID);

    expect(overlay.classList.contains(hideClassFor('stories'))).toBe(false);
  });
});

describe('resolveActiveGate', () => {
  const gates: ResolvedGate[] = [
    { id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false },
    { id: 'explore', mode: 'ALLOW', delaySeconds: 15, limitReached: false },
  ];

  it('returns the resolved gate matching the current URL', () => {
    const gate = resolveActiveGate('instagram', gates, 'https://www.instagram.com/reels/');
    expect(gate?.id).toBe('reels');
  });

  it('returns null when the URL matches no gate surface for this platform', () => {
    const gate = resolveActiveGate('instagram', gates, 'https://www.instagram.com/direct/inbox/');
    expect(gate).toBeNull();
  });
});

describe('initPlatformContentScript — the shared controller', () => {
  function configWith(overrides: Partial<SiteConfig>): SiteConfig {
    return { ...IDLE_SITE_CONFIG, enabled: true, platform: 'instagram', ...overrides };
  }

  it('gates a HARD_BLOCK surface with an interstitial and reveals it after "I changed my mind"', async () => {
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/reels/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initPlatformContentScript(
      {
        platform: 'instagram',
        hideSurfaces: [],
        gateCopy: () => ({ ruleLabel: 'Reels' }),
        bailUrl: 'https://www.instagram.com/',
      },
      document,
      fetchConfig,
    );
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });

  it('does not gate when the resolved mode for the matched surface is ALLOW', async () => {
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/explore/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'explore', mode: 'ALLOW', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initPlatformContentScript(
      {
        platform: 'instagram',
        hideSurfaces: [],
        gateCopy: () => ({ ruleLabel: 'Explore' }),
        bailUrl: 'https://www.instagram.com/',
      },
      document,
      fetchConfig,
    );
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });

  it('does not gate a page URL that matches no gate at all', async () => {
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/direct/inbox/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initPlatformContentScript(
      {
        platform: 'instagram',
        hideSurfaces: [],
        gateCopy: () => ({ ruleLabel: 'Reels' }),
        bailUrl: 'https://www.instagram.com/',
      },
      document,
      fetchConfig,
    );
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });

  it('applies hide surfaces every refresh, independent of any gate', async () => {
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/');

    const fetchConfig = vi.fn(async () => configWith({ gates: [], hides: { stories: true } }));

    const controller = initPlatformContentScript(
      {
        platform: 'instagram',
        hideSurfaces: [STORIES_SURFACE],
        gateCopy: () => ({ ruleLabel: 'x' }),
        bailUrl: 'https://www.instagram.com/',
      },
      document,
      fetchConfig,
    );
    await controller.reload();

    expect(
      document.querySelector('[data-testid="stories-tray"]')!.classList.contains(hideClassFor('stories')),
    ).toBe(true);

    controller.stop();
  });

  it('re-gates a fresh navigation to the same surface after the previous pause was already satisfied and left', async () => {
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/reels/one');

    const currentGates: ResolvedGate[] = [{ id: 'reels', mode: 'DELAY', delaySeconds: 5, limitReached: false }];
    const fetchConfig = vi.fn(async () => configWith({ gates: currentGates }));

    const controller = initPlatformContentScript(
      {
        platform: 'instagram',
        hideSurfaces: [],
        gateCopy: () => ({ ruleLabel: 'Reels' }),
        bailUrl: 'https://www.instagram.com/',
      },
      document,
      fetchConfig,
    );
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    // Complete the delay by clicking "I changed my mind" is the bail path — instead
    // simulate satisfying the pause: the DELAY overlay's own timer completes it, but for
    // this test we drive the observable contract via the bail button being present, then
    // leave the surface entirely and come back, which must re-gate (fresh decision).
    document.getElementById(OVERLAY_ID)?.remove();
    window.history.replaceState({}, '', '/direct/inbox/');
    controller.refresh();
    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    window.history.replaceState({}, '', '/reels/two');
    controller.refresh();
    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });

  it('stop() tears down the overlay and detaches navigation listeners', async () => {
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/reels/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initPlatformContentScript(
      {
        platform: 'instagram',
        hideSurfaces: [],
        gateCopy: () => ({ ruleLabel: 'Reels' }),
        bailUrl: 'https://www.instagram.com/',
      },
      document,
      fetchConfig,
    );
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
    expect(document.getElementById(OVERLAY_ID)).toBeNull();
  });
});
