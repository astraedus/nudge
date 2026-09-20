// @vitest-environment jsdom
/**
 * TikTok content-script tests: hide-surface selectors, the gate overlay, and SPA-nav
 * re-gating — driven end-to-end through `initTiktokContentScript` wherever cheap (the real
 * integration), unit-testing `HIDE_SURFACES` directly where that is cheaper.
 *
 * Phrased as USER-VISIBLE outcomes throughout (repo rule, extension/CLAUDE.md): "hidden"
 * means "carries the surface's own `nudge-hide-<id>` class", the exact class the real
 * stylesheet (`src/content/tiktok.css`) keys `display: none` off. "Gated" means an element
 * with id `nudge-platform-gate` is attached to the document.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  overlayIdFor,
  applyPlatformHides,
  hideClassFor,
} from '../../../src/content/platformGate';
import { resetFallbackWarnings } from '../../../src/content/selectors';
import { HIDE_SURFACES } from '../../../src/content/tiktok/selectors';
import { initTiktokContentScript } from '../../../src/content/tiktok';
import type { ResolvedGate, SiteConfig } from '../../../src/core/protocol';
import { IDLE_SITE_CONFIG } from '../../../src/content/platformGate';
import {

  TIKTOK_EXPLORE_NAV_HREF_ONLY_HTML,
  TIKTOK_FEED_HTML,
  TIKTOK_SEARCH_INPUT_ONLY_HTML,
  TIKTOK_SUGGESTED_ALT_SPELLING_HTML,
} from '../fixtures/tiktok/pages';

const OVERLAY_ID = overlayIdFor('tiktok');

function mount(html: string): HTMLElement {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.replaceChildren(root);
  return root;
}

/** Every TikTok hide toggle off. */
const ALL_OFF: Partial<Record<string, boolean>> = {
  comments: false,
  search: false,
  liveNav: false,
  exploreNav: false,
  suggestedAccounts: false,
};

function isHidden(root: HTMLElement, testId: string, hideId: string): boolean {
  const el = root.querySelector(`[data-testid="${testId}"]`);
  if (!el) throw new Error(`fixture missing [data-testid="${testId}"]`);
  return el.classList.contains(hideClassFor(hideId));
}

beforeEach(() => {
  resetFallbackWarnings();
});

describe('TikTok HIDE_SURFACES — each toggle hides only its own surface', () => {
  it('hides comments, leaving search, nav entries and suggested accounts visible', () => {
    const root = mount(TIKTOK_FEED_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, comments: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'comment-list', 'comments')).toBe(true);
    expect(isHidden(root, 'search-box', 'search')).toBe(false);
    expect(isHidden(root, 'nav-live', 'liveNav')).toBe(false);
    expect(isHidden(root, 'nav-explore', 'exploreNav')).toBe(false);
    expect(isHidden(root, 'suggested-accounts', 'suggestedAccounts')).toBe(false);
  });

  it('hides the search box, leaving comments and everything else visible', () => {
    const root = mount(TIKTOK_FEED_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, search: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'search-box', 'search')).toBe(true);
    expect(isHidden(root, 'comment-list', 'comments')).toBe(false);
    expect(isHidden(root, 'nav-live', 'liveNav')).toBe(false);
  });

  it('falls back to hiding the bare search input when the search form is absent', () => {
    const root = mount(TIKTOK_SEARCH_INPUT_ONLY_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, search: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'search-input', 'search')).toBe(true);
  });

  it('hides the Live nav entry, leaving Explore visible', () => {
    const root = mount(TIKTOK_FEED_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, liveNav: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'nav-live', 'liveNav')).toBe(true);
    expect(isHidden(root, 'nav-explore', 'exploreNav')).toBe(false);
  });

  it('hides the Explore nav entry via the data-e2e attribute, leaving Live visible', () => {
    const root = mount(TIKTOK_FEED_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, exploreNav: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'nav-explore', 'exploreNav')).toBe(true);
    expect(isHidden(root, 'nav-live', 'liveNav')).toBe(false);
  });

  it('falls back to the href-anchored Explore link when data-e2e is absent', () => {
    const root = mount(TIKTOK_EXPLORE_NAV_HREF_ONLY_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, exploreNav: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'nav-explore-href-only', 'exploreNav')).toBe(true);
  });

  it('hides suggested accounts under the primary spelling', () => {
    const root = mount(TIKTOK_FEED_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, suggestedAccounts: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'suggested-accounts', 'suggestedAccounts')).toBe(true);
  });

  it('hides suggested accounts under the alternate spelling TikTok also ships', () => {
    const root = mount(TIKTOK_SUGGESTED_ALT_SPELLING_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, suggestedAccounts: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'suggested-accounts-alt', 'suggestedAccounts')).toBe(true);
  });

  it('reveals a surface the instant its toggle turns back off, without touching a still-on sibling', () => {
    const root = mount(TIKTOK_FEED_HTML);

    applyPlatformHides(
      root,
      HIDE_SURFACES,
      { ...ALL_OFF, comments: true, search: true },
      true, OVERLAY_ID
    );
    expect(isHidden(root, 'comment-list', 'comments')).toBe(true);
    expect(isHidden(root, 'search-box', 'search')).toBe(true);

    applyPlatformHides(
      root,
      HIDE_SURFACES,
      { ...ALL_OFF, comments: false, search: true },
      true, OVERLAY_ID
    );

    expect(isHidden(root, 'comment-list', 'comments')).toBe(false);
    expect(isHidden(root, 'search-box', 'search')).toBe(true);
  });

  it('reveals everything when the extension is globally disabled', () => {
    const root = mount(TIKTOK_FEED_HTML);

    applyPlatformHides(
      root,
      HIDE_SURFACES,
      { comments: true, search: true, liveNav: true, exploreNav: true, suggestedAccounts: true },
      true, OVERLAY_ID
    );
    applyPlatformHides(
      root,
      HIDE_SURFACES,
      { comments: true, search: true, liveNav: true, exploreNav: true, suggestedAccounts: true },
      false, OVERLAY_ID
    );

    expect(isHidden(root, 'comment-list', 'comments')).toBe(false);
    expect(isHidden(root, 'search-box', 'search')).toBe(false);
    expect(isHidden(root, 'nav-live', 'liveNav')).toBe(false);
    expect(isHidden(root, 'nav-explore', 'exploreNav')).toBe(false);
    expect(isHidden(root, 'suggested-accounts', 'suggestedAccounts')).toBe(false);
  });
});

describe('initTiktokContentScript — gate overlay', () => {
  function configWith(overrides: Partial<SiteConfig>): SiteConfig {
    return { ...IDLE_SITE_CONFIG, enabled: true, platform: 'tiktok', ...overrides };
  }

  it('gates the For You feed (root path) with an interstitial in a BlockMode', async () => {
    mount(TIKTOK_FEED_HTML);
    window.history.replaceState({}, '', '/');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'foryou', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initTiktokContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });

  it('does not gate when the resolved mode for the matched gate is ALLOW', async () => {
    mount(TIKTOK_FEED_HTML);
    window.history.replaceState({}, '', '/foryou');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'foryou', mode: 'ALLOW', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initTiktokContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });

  it('does not gate a URL that matches no TikTok gate at all', async () => {
    mount(TIKTOK_FEED_HTML);
    window.history.replaceState({}, '', '/@someone/video/123');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'foryou', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initTiktokContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });

  it('SPA nav: gates the new URL after a pushState into Explore, having started on an ungated page', async () => {
    mount(TIKTOK_FEED_HTML);
    window.history.replaceState({}, '', '/@someone/video/123');

    const currentGates: ResolvedGate[] = [
      { id: 'foryou', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false },
      { id: 'explore', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false },
    ];
    const fetchConfig = vi.fn(async () => configWith({ gates: currentGates }));

    const controller = initTiktokContentScript(document, fetchConfig);
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    window.history.pushState({}, '', '/explore');
    // The nav layer's href-diff poll is what would notice this on a real page; the
    // controller also exposes `refresh` for a synchronous re-check in tests (same pattern
    // as tests/content/platformGate.test.ts's re-gating test).
    controller.refresh();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });
});
