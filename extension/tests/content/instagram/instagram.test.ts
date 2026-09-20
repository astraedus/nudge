// @vitest-environment jsdom
/**
 * Tests for the Instagram content script (`content/instagram/`).
 *
 * Phrased as USER-VISIBLE outcomes throughout (repo rule, extension/CLAUDE.md Lessons:
 * "A test that asserts the bug is worse than no test" — a critical bug once survived 400
 * tests because two asserted the internal branch instead of the visible behaviour).
 * "Hidden" here means "carries the surface's own `nudge-hide-<id>` class", the same class
 * `content/instagram.css` keys `display: none` off — checking the class IS checking what
 * the user would see. "Gated" means an element with id `nudge-platform-gate`
 * (`overlayIdFor`) is attached to the document.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  IDLE_SITE_CONFIG,
  overlayIdFor,
  applyPlatformHides,
  hideClassFor,
} from '../../../src/content/platformGate';
import { resetFallbackWarnings } from '../../../src/content/selectors';
import { initInstagramContentScript } from '../../../src/content/instagram';
import { INSTAGRAM_HIDE_SURFACES } from '../../../src/content/instagram/selectors';
import type { ResolvedGate, SiteConfig } from '../../../src/core/protocol';
import {
  INSTAGRAM_PRIMARY_HTML,
  INSTAGRAM_REELS_NAV_FALLBACK_HTML,
  INSTAGRAM_STORIES_HASHED_FALLBACK_HTML,
} from '../fixtures/instagram/pages';

const OVERLAY_ID = overlayIdFor('instagram');

function mount(html: string): HTMLElement {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.replaceChildren(root);
  return root;
}

function isHidden(root: HTMLElement, testId: string, hideId: string): boolean {
  const el = root.querySelector(`[data-testid="${testId}"]`);
  if (!el) throw new Error(`fixture missing [data-testid="${testId}"]`);
  return el.classList.contains(hideClassFor(hideId));
}

/** Every toggle off; a test opts individual surfaces in from this baseline. */
const ALL_OFF: Partial<Record<string, boolean>> = {
  reelsNav: false,
  exploreNav: false,
  stories: false,
  suggested: false,
};

beforeEach(() => {
  resetFallbackWarnings();
});

describe('Instagram hide surfaces — each toggle hides only its own surface', () => {
  it('hides the Reels nav entry via its exact-href rung, leaving other nav entries and the feed visible', () => {
    const root = mount(INSTAGRAM_PRIMARY_HTML);

    applyPlatformHides(root, INSTAGRAM_HIDE_SURFACES, { ...ALL_OFF, reelsNav: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'reels-nav', 'reelsNav')).toBe(true);
    expect(isHidden(root, 'home-nav', 'reelsNav')).toBe(false);
    expect(isHidden(root, 'explore-nav', 'exploreNav')).toBe(false);
    expect(isHidden(root, 'direct-nav', 'reelsNav')).toBe(false);
    expect(isHidden(root, 'feed', 'reelsNav')).toBe(false);
  });

  it('hides the Reels nav entry via the icon-anchored fallback, climbing to the whole row', () => {
    const root = mount(INSTAGRAM_REELS_NAV_FALLBACK_HTML);

    const result = applyPlatformHides(root, INSTAGRAM_HIDE_SURFACES, { reelsNav: true }, true, OVERLAY_ID);

    expect(result.degradedSurfaces).toContain('reelsNav');
    expect(isHidden(root, 'reels-nav-fallback', 'reelsNav')).toBe(true);
    expect(isHidden(root, 'explore-nav', 'reelsNav')).toBe(false);
  });

  it('hides the Explore nav entry, leaving Reels and the feed visible', () => {
    const root = mount(INSTAGRAM_PRIMARY_HTML);

    applyPlatformHides(root, INSTAGRAM_HIDE_SURFACES, { ...ALL_OFF, exploreNav: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'explore-nav', 'exploreNav')).toBe(true);
    expect(isHidden(root, 'reels-nav', 'exploreNav')).toBe(false);
    expect(isHidden(root, 'feed', 'exploreNav')).toBe(false);
  });

  it('hides the whole stories tray (climbed via the scrollable ancestor), leaving the feed visible', () => {
    const root = mount(INSTAGRAM_PRIMARY_HTML);

    applyPlatformHides(root, INSTAGRAM_HIDE_SURFACES, { ...ALL_OFF, stories: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'stories-tray', 'stories')).toBe(true);
    expect(isHidden(root, 'feed', 'stories')).toBe(false);
    expect(isHidden(root, 'suggested-block', 'stories')).toBe(false);
  });

  it('hides the stories tray via the hashed-class last resort when no scrollable ancestor exists', () => {
    const root = mount(INSTAGRAM_STORIES_HASHED_FALLBACK_HTML);

    const result = applyPlatformHides(root, INSTAGRAM_HIDE_SURFACES, { stories: true }, true, OVERLAY_ID);

    expect(result.degradedSurfaces).toContain('stories');
    expect(isHidden(root, 'stories-tray-hashed', 'stories')).toBe(true);
    expect(isHidden(root, 'feed', 'stories')).toBe(false);
  });

  it('hides "Suggested for you", leaving stories and the feed visible', () => {
    const root = mount(INSTAGRAM_PRIMARY_HTML);

    applyPlatformHides(root, INSTAGRAM_HIDE_SURFACES, { ...ALL_OFF, suggested: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'suggested-block', 'suggested')).toBe(true);
    expect(isHidden(root, 'stories-tray', 'suggested')).toBe(false);
    expect(isHidden(root, 'feed', 'suggested')).toBe(false);
  });
});

describe('Instagram hide surfaces — reveal when turned back off, without touching a sibling surface', () => {
  it('reveals stories the instant its toggle turns off while Reels-nav stays hidden', () => {
    const root = mount(INSTAGRAM_PRIMARY_HTML);

    applyPlatformHides(root, INSTAGRAM_HIDE_SURFACES, { reelsNav: true, stories: true }, true, OVERLAY_ID);
    expect(isHidden(root, 'reels-nav', 'reelsNav')).toBe(true);
    expect(isHidden(root, 'stories-tray', 'stories')).toBe(true);

    applyPlatformHides(root, INSTAGRAM_HIDE_SURFACES, { reelsNav: true, stories: false }, true, OVERLAY_ID);

    expect(isHidden(root, 'stories-tray', 'stories')).toBe(false);
    expect(isHidden(root, 'reels-nav', 'reelsNav')).toBe(true);
  });

  it('reveals every surface when the extension is globally disabled', () => {
    const root = mount(INSTAGRAM_PRIMARY_HTML);
    applyPlatformHides(
      root,
      INSTAGRAM_HIDE_SURFACES,
      { reelsNav: true, exploreNav: true, stories: true, suggested: true },
      true, OVERLAY_ID
    );

    applyPlatformHides(
      root,
      INSTAGRAM_HIDE_SURFACES,
      { reelsNav: true, exploreNav: true, stories: true, suggested: true },
      false, OVERLAY_ID
    );

    expect(isHidden(root, 'reels-nav', 'reelsNav')).toBe(false);
    expect(isHidden(root, 'explore-nav', 'exploreNav')).toBe(false);
    expect(isHidden(root, 'stories-tray', 'stories')).toBe(false);
    expect(isHidden(root, 'suggested-block', 'suggested')).toBe(false);
  });

  it('never hides its own overlay even if a selector would otherwise match it', () => {
    const root = mount(INSTAGRAM_PRIMARY_HTML);
    const overlay = document.createElement('div');
    overlay.id = OVERLAY_ID;
    overlay.setAttribute('data-testid', 'suggested-block'); // pathological: matches the chain
    root.appendChild(overlay);

    applyPlatformHides(root, INSTAGRAM_HIDE_SURFACES, { suggested: true }, true, OVERLAY_ID);

    expect(overlay.classList.contains(hideClassFor('suggested'))).toBe(false);
  });
});

describe('Instagram gate overlay (initInstagramContentScript)', () => {
  function configWith(overrides: Partial<SiteConfig>): SiteConfig {
    return { ...IDLE_SITE_CONFIG, enabled: true, platform: 'instagram', ...overrides };
  }

  it('shows the interstitial for a HARD_BLOCK Reels gate', async () => {
    mount(INSTAGRAM_PRIMARY_HTML);
    window.history.replaceState({}, '', '/reels/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initInstagramContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });

  it('does not show the interstitial when the resolved gate mode is ALLOW', async () => {
    mount(INSTAGRAM_PRIMARY_HTML);
    window.history.replaceState({}, '', '/explore/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'explore', mode: 'ALLOW', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initInstagramContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });

  it('does not show the interstitial on a URL that matches no gate at all', async () => {
    mount(INSTAGRAM_PRIMARY_HTML);
    window.history.replaceState({}, '', '/direct/inbox/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initInstagramContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });
});

describe('Instagram SPA navigation', () => {
  it('gates the NEW url after a pushState hop into Reels, not the old one', async () => {
    mount(INSTAGRAM_PRIMARY_HTML);
    window.history.replaceState({}, '', '/');

    let currentGates: ResolvedGate[] = [];
    const fetchConfig = vi.fn(
      async (): Promise<SiteConfig> => ({
        ...IDLE_SITE_CONFIG,
        enabled: true,
        platform: 'instagram',
        gates: currentGates,
      }),
    );

    const controller = initInstagramContentScript(document, fetchConfig);
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    // Simulate the worker's answer changing alongside the SPA hop (mirrors how the real
    // extension re-reads config on storage/nav events) and drive navigation via
    // pushState, exactly as an in-app Instagram link click would.
    currentGates = [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }];
    window.history.pushState({}, '', '/reels/');
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });

  it('refresh() re-evaluates purely from the current URL after a bare pushState (no reload)', async () => {
    mount(INSTAGRAM_PRIMARY_HTML);
    window.history.replaceState({}, '', '/reels/');

    const fetchConfig = vi.fn(
      async (): Promise<SiteConfig> => ({
        ...IDLE_SITE_CONFIG,
        enabled: true,
        platform: 'instagram',
        gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }],
      }),
    );

    const controller = initInstagramContentScript(document, fetchConfig);
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    // Leave Reels for a non-gated page: refresh() alone (no fresh worker round trip) must
    // tear the overlay down, proving the controller re-derives from location on every
    // navigation rather than latching the first verdict.
    window.history.pushState({}, '', '/direct/inbox/');
    controller.refresh();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });
});
