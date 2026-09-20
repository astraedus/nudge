// @vitest-environment jsdom
/**
 * Tests for the Facebook content script (`content/facebook/`). Same structure and
 * discipline as `tests/content/instagram/instagram.test.ts` — phrased as USER-VISIBLE
 * outcomes throughout (repo rule). "Hidden" means "carries the surface's own
 * `nudge-hide-<id>` class", the same class `content/facebook.css` keys `display: none`
 * off. "Gated" means an element with id `nudge-platform-gate` (`overlayIdFor`) is
 * attached to the document.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  IDLE_SITE_CONFIG,
  overlayIdFor,
  applyPlatformHides,
  hideClassFor,
} from '../../../src/content/platformGate';
import { resetFallbackWarnings } from '../../../src/content/selectors';
import { initFacebookContentScript } from '../../../src/content/facebook';
import { FACEBOOK_HIDE_SURFACES } from '../../../src/content/facebook/selectors';
import type { ResolvedGate, SiteConfig } from '../../../src/core/protocol';
import {
  FACEBOOK_PEOPLE_YOU_MAY_KNOW_STRUCTURAL_HTML,
  FACEBOOK_PRIMARY_HTML,
  FACEBOOK_REELS_NAV_FALLBACK_HTML,
  FACEBOOK_STORIES_HASHED_FALLBACK_HTML,
} from '../fixtures/facebook/pages';

const OVERLAY_ID = overlayIdFor('facebook');

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

const ALL_OFF: Partial<Record<string, boolean>> = {
  reelsNav: false,
  stories: false,
  peopleYouMayKnow: false,
};

beforeEach(() => {
  resetFallbackWarnings();
});

describe('Facebook hide surfaces — each toggle hides only its own surface', () => {
  it('hides the whole Reels nav row (climbed to the <li>), leaving other nav rows and the feed visible', () => {
    const root = mount(FACEBOOK_PRIMARY_HTML);

    applyPlatformHides(root, FACEBOOK_HIDE_SURFACES, { ...ALL_OFF, reelsNav: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'reels-nav-row', 'reelsNav')).toBe(true);
    expect(isHidden(root, 'feed-nav-row', 'reelsNav')).toBe(false);
    expect(isHidden(root, 'marketplace-nav-row', 'reelsNav')).toBe(false);
    expect(isHidden(root, 'feed', 'reelsNav')).toBe(false);
  });

  it('hides only the bare Reels link via the anchor-only fallback when no <li> wraps it', () => {
    const root = mount(FACEBOOK_REELS_NAV_FALLBACK_HTML);

    const result = applyPlatformHides(root, FACEBOOK_HIDE_SURFACES, { reelsNav: true }, true, OVERLAY_ID);

    expect(result.degradedSurfaces).toContain('reelsNav');
    expect(isHidden(root, 'reels-nav-link-bare', 'reelsNav')).toBe(true);
    expect(isHidden(root, 'marketplace-nav-link', 'reelsNav')).toBe(false);
  });

  it('hides the stories tray (climbed via the scrollable ancestor), leaving the feed visible', () => {
    const root = mount(FACEBOOK_PRIMARY_HTML);

    applyPlatformHides(root, FACEBOOK_HIDE_SURFACES, { ...ALL_OFF, stories: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'stories-tray', 'stories')).toBe(true);
    expect(isHidden(root, 'feed', 'stories')).toBe(false);
    expect(isHidden(root, 'people-you-may-know', 'stories')).toBe(false);
  });

  it('hides the stories tray via the hashed-class last resort when no scrollable ancestor exists', () => {
    const root = mount(FACEBOOK_STORIES_HASHED_FALLBACK_HTML);

    const result = applyPlatformHides(root, FACEBOOK_HIDE_SURFACES, { stories: true }, true, OVERLAY_ID);

    expect(result.degradedSurfaces).toContain('stories');
    expect(isHidden(root, 'stories-tray-hashed', 'stories')).toBe(true);
    expect(isHidden(root, 'feed', 'stories')).toBe(false);
  });

  it('hides "People You May Know" via the pagelet-anchored rung, leaving stories and the feed visible', () => {
    const root = mount(FACEBOOK_PRIMARY_HTML);

    applyPlatformHides(root, FACEBOOK_HIDE_SURFACES, { ...ALL_OFF, peopleYouMayKnow: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'people-you-may-know', 'peopleYouMayKnow')).toBe(true);
    expect(isHidden(root, 'stories-tray', 'peopleYouMayKnow')).toBe(false);
    expect(isHidden(root, 'feed', 'peopleYouMayKnow')).toBe(false);
  });

  it('hides "People You May Know" via the structural last-resort rung when no pagelet/aria marker exists', () => {
    const root = mount(FACEBOOK_PEOPLE_YOU_MAY_KNOW_STRUCTURAL_HTML);

    const result = applyPlatformHides(root, FACEBOOK_HIDE_SURFACES, { peopleYouMayKnow: true }, true, OVERLAY_ID);

    expect(result.degradedSurfaces).toContain('peopleYouMayKnow');
    expect(isHidden(root, 'people-you-may-know-structural', 'peopleYouMayKnow')).toBe(true);
    expect(isHidden(root, 'feed-item-1', 'peopleYouMayKnow')).toBe(false);
    expect(isHidden(root, 'feed-item-2', 'peopleYouMayKnow')).toBe(false);
  });
});

describe('Facebook hide surfaces — reveal when turned back off, without touching a sibling surface', () => {
  it('reveals "People You May Know" the instant its toggle turns off while the Reels row stays hidden', () => {
    const root = mount(FACEBOOK_PRIMARY_HTML);

    applyPlatformHides(root, FACEBOOK_HIDE_SURFACES, { reelsNav: true, peopleYouMayKnow: true }, true, OVERLAY_ID);
    expect(isHidden(root, 'reels-nav-row', 'reelsNav')).toBe(true);
    expect(isHidden(root, 'people-you-may-know', 'peopleYouMayKnow')).toBe(true);

    applyPlatformHides(root, FACEBOOK_HIDE_SURFACES, { reelsNav: true, peopleYouMayKnow: false }, true, OVERLAY_ID);

    expect(isHidden(root, 'people-you-may-know', 'peopleYouMayKnow')).toBe(false);
    expect(isHidden(root, 'reels-nav-row', 'reelsNav')).toBe(true);
  });

  it('reveals every surface when the extension is globally disabled', () => {
    const root = mount(FACEBOOK_PRIMARY_HTML);
    applyPlatformHides(
      root,
      FACEBOOK_HIDE_SURFACES,
      { reelsNav: true, stories: true, peopleYouMayKnow: true },
      true, OVERLAY_ID
    );

    applyPlatformHides(
      root,
      FACEBOOK_HIDE_SURFACES,
      { reelsNav: true, stories: true, peopleYouMayKnow: true },
      false, OVERLAY_ID
    );

    expect(isHidden(root, 'reels-nav-row', 'reelsNav')).toBe(false);
    expect(isHidden(root, 'stories-tray', 'stories')).toBe(false);
    expect(isHidden(root, 'people-you-may-know', 'peopleYouMayKnow')).toBe(false);
  });

  it('never hides its own overlay even if a selector would otherwise match it', () => {
    const root = mount(FACEBOOK_PRIMARY_HTML);
    const overlay = document.createElement('div');
    overlay.id = OVERLAY_ID;
    overlay.setAttribute('data-pagelet', 'PeopleYouMayKnow'); // pathological: matches the chain
    root.appendChild(overlay);

    applyPlatformHides(root, FACEBOOK_HIDE_SURFACES, { peopleYouMayKnow: true }, true, OVERLAY_ID);

    expect(overlay.classList.contains(hideClassFor('peopleYouMayKnow'))).toBe(false);
  });
});

describe('Facebook gate overlay (initFacebookContentScript)', () => {
  function configWith(overrides: Partial<SiteConfig>): SiteConfig {
    return { ...IDLE_SITE_CONFIG, enabled: true, platform: 'facebook', ...overrides };
  }

  it('shows the interstitial for a HARD_BLOCK News Feed gate', async () => {
    mount(FACEBOOK_PRIMARY_HTML);
    window.history.replaceState({}, '', '/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'feed', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
    );

    const controller = initFacebookContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });

  it('does not show the interstitial when the resolved gate mode is ALLOW', async () => {
    mount(FACEBOOK_PRIMARY_HTML);
    window.history.replaceState({}, '', '/marketplace/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'marketplace', mode: 'ALLOW', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
    );

    const controller = initFacebookContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });

  it('does not show the interstitial on a URL that matches no gate at all', async () => {
    mount(FACEBOOK_PRIMARY_HTML);
    window.history.replaceState({}, '', '/settings/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'feed', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }] }),
    );

    const controller = initFacebookContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });
});

describe('Facebook SPA navigation', () => {
  it('gates the NEW url after a pushState hop into Watch, not the old one', async () => {
    mount(FACEBOOK_PRIMARY_HTML);
    window.history.replaceState({}, '', '/settings/');

    let currentGates: ResolvedGate[] = [];
    const fetchConfig = vi.fn(
      async (): Promise<SiteConfig> => ({
        ...IDLE_SITE_CONFIG,
        enabled: true,
        platform: 'facebook',
        gates: currentGates,
      }),
    );

    const controller = initFacebookContentScript(document, fetchConfig);
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    currentGates = [{ id: 'watch', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }];
    window.history.pushState({}, '', '/watch/');
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });

  it('refresh() re-evaluates purely from the current URL after a bare pushState (no reload)', async () => {
    mount(FACEBOOK_PRIMARY_HTML);
    window.history.replaceState({}, '', '/reel/');

    const fetchConfig = vi.fn(
      async (): Promise<SiteConfig> => ({
        ...IDLE_SITE_CONFIG,
        enabled: true,
        platform: 'facebook',
        gates: [{ id: 'reels', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false, countReached: false, itemsToday: 0, countLimit: null }],
      }),
    );

    const controller = initFacebookContentScript(document, fetchConfig);
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    window.history.pushState({}, '', '/settings/');
    controller.refresh();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });
});
