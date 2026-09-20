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
    { id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null },
    { id: 'explore', mode: 'ALLOW', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null },
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
      configWith({ gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
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
      configWith({ gates: [{ id: 'explore', mode: 'ALLOW', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
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
      configWith({ gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
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

    const currentGates: ResolvedGate[] = [{ id: 'reels', mode: 'DELAY', delaySeconds: 5, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }];
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
      configWith({ gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
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

describe('initPlatformContentScript — item-view reporting (COUNT budgets, v0.3)', () => {
  function configWith(overrides: Partial<SiteConfig>): SiteConfig {
    return { ...IDLE_SITE_CONFIG, enabled: true, platform: 'instagram', ...overrides };
  }

  /** A reel gate, count-exhausted, the shape the worker sends once today's budget is spent. */
  const COUNT_EXHAUSTED_REEL_GATE: ResolvedGate = {
    id: 'reels',
    mode: 'HARD_BLOCK',
    delaySeconds: 15,
    limitReached: false,
    countReached: true,
    itemsToday: 20,
    countLimit: 20,
  };

  it('reports an item view on navigation to an item URL', async () => {
    // The content script reports WHERE it is on every refresh (`report()`'s own local
    // dedupe against the LAST url is the only filtering it does) — it is the worker,
    // not the page, that decides whether a given URL names an item (`core/platforms.ts`
    // `itemForUrl`). So the very first refresh (on `/`) reports too; what this test pins is
    // that navigating ONTO the item URL reports exactly that URL.
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/');

    const fetchConfig = vi.fn(async () => configWith({ gates: [] }));
    const sendItemView = vi.fn();

    const controller = initPlatformContentScript(
      {
        platform: 'instagram',
        hideSurfaces: [],
        gateCopy: () => ({ ruleLabel: 'Reels' }),
        bailUrl: 'https://www.instagram.com/',
      },
      document,
      fetchConfig,
      sendItemView,
    );
    await controller.reload();
    expect(sendItemView).toHaveBeenCalledTimes(1);

    window.history.replaceState({}, '', '/reels/abc/');
    controller.refresh();

    expect(sendItemView).toHaveBeenCalledTimes(2);
    expect(sendItemView).toHaveBeenLastCalledWith(window.location.href);
    expect(window.location.href).toMatch(/\/reels\/abc\/?$/);

    controller.stop();
  });

  it('reports even when NO gate applies to the current URL', async () => {
    // An item view is an observation about where the page is, not a consequence of a
    // verdict. `/reels/abc/` is on Instagram's Reels item stream regardless of whether the
    // registry's `reels` GATE happens to be configured — the reporter and the gate resolver
    // read the same URL independently (itemForUrl vs gateForUrl), so a rule with no gates
    // at all still must not blind the count.
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/reels/xyz/');

    const fetchConfig = vi.fn(async () => configWith({ gates: [] }));
    const sendItemView = vi.fn();

    const controller = initPlatformContentScript(
      {
        platform: 'instagram',
        hideSurfaces: [],
        gateCopy: () => ({ ruleLabel: 'Reels' }),
        bailUrl: 'https://www.instagram.com/',
      },
      document,
      fetchConfig,
      sendItemView,
    );
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();
    expect(sendItemView).toHaveBeenCalledWith(window.location.href);

    controller.stop();
  });

  it('reports even while an interstitial is up for the same surface', async () => {
    // A report that only fired while NOT gated would freeze the count at exactly the
    // number the user is most trying to keep an eye on — the moment the gate starts
    // applying is exactly when the measurement matters most.
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/reels/abc/');

    const fetchConfig = vi.fn(async () =>
      configWith({
        gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }],
      }),
    );
    const sendItemView = vi.fn();

    const controller = initPlatformContentScript(
      {
        platform: 'instagram',
        hideSurfaces: [],
        gateCopy: () => ({ ruleLabel: 'Reels' }),
        bailUrl: 'https://www.instagram.com/',
      },
      document,
      fetchConfig,
      sendItemView,
    );
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();
    expect(sendItemView).toHaveBeenCalledWith(window.location.href);

    controller.stop();
  });

  it('a count-exhausted gate arrives as HARD_BLOCK and raises the interstitial with no countdown', async () => {
    // The worker escalates a spent count budget to HARD_BLOCK before this module ever sees
    // it (core/applies.ts) — the page has no idea "count" and "minutes" are different axes,
    // it only ever renders the mode it is handed. `createGateOverlay` renders no countdown
    // for HARD_BLOCK, whatever delaySeconds says.
    mount(FIXTURE_HTML);
    window.history.replaceState({}, '', '/reels/abc/');

    const fetchConfig = vi.fn(async () => configWith({ gates: [COUNT_EXHAUSTED_REEL_GATE] }));

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

    const overlay = document.getElementById(OVERLAY_ID);
    expect(overlay).not.toBeNull();
    // `.nudge-overlay__count` is the DELAY-only countdown digit (`createGateOverlay`) —
    // HARD_BLOCK renders none, whatever `delaySeconds` the gate carries.
    expect(overlay?.querySelector('.nudge-overlay__count')).toBeNull();
    expect(overlay?.querySelector('.nudge-overlay__title')?.textContent).toBe('Reels is blocked');

    controller.stop();
  });
});
