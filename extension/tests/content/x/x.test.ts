// @vitest-environment jsdom
/**
 * X (Twitter) content-script tests: hide-surface selectors, the gate overlay, SPA-nav
 * re-gating, and (per the dispatch brief) a dedicated assertion that the entrypoint
 * registers content-script matches for BOTH `x.com` and `twitter.com`.
 *
 * Phrased as USER-VISIBLE outcomes throughout (repo rule, extension/CLAUDE.md): "hidden"
 * means "carries the surface's own `nudge-hide-<id>` class", the exact class the real
 * stylesheet (`src/content/x.css`) keys `display: none` off. "Gated" means an element with
 * id `nudge-platform-gate` is attached to the document.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  IDLE_SITE_CONFIG,
  overlayIdFor,
  applyPlatformHides,
  hideClassFor,
} from '../../../src/content/platformGate';
import { resetFallbackWarnings } from '../../../src/content/selectors';
import { HIDE_SURFACES } from '../../../src/content/x/selectors';
import { initXContentScript } from '../../../src/content/x';
import type { ResolvedGate, SiteConfig } from '../../../src/core/protocol';
import {

  X_EXPLORE_NAV_HREF_ONLY_HTML,
  X_TIMELINE_HTML,
  X_TRENDS_ROWS_ONLY_HTML,
} from '../fixtures/x/pages';

const OVERLAY_ID = overlayIdFor('x');

function mount(html: string): HTMLElement {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.replaceChildren(root);
  return root;
}

/** Every X hide toggle off. */
const ALL_OFF: Partial<Record<string, boolean>> = {
  trends: false,
  whoToFollow: false,
  promoted: false,
  grok: false,
  exploreNav: false,
};

function isHidden(root: HTMLElement, testId: string, hideId: string): boolean {
  const el = root.querySelector(`[data-testid-fixture="${testId}"]`);
  if (!el) throw new Error(`fixture missing [data-testid-fixture="${testId}"]`);
  return el.classList.contains(hideClassFor(hideId));
}

beforeEach(() => {
  resetFallbackWarnings();
});

describe('X HIDE_SURFACES — each toggle hides only its own surface', () => {
  it('hides promoted posts, leaving trends, who-to-follow, Grok and Explore visible', () => {
    const root = mount(X_TIMELINE_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, promoted: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'promoted-post', 'promoted')).toBe(true);
    expect(isHidden(root, 'trends-section', 'trends')).toBe(false);
    expect(isHidden(root, 'who-to-follow', 'whoToFollow')).toBe(false);
    expect(isHidden(root, 'grok-drawer', 'grok')).toBe(false);
    expect(isHidden(root, 'explore-nav', 'exploreNav')).toBe(false);
  });

  it('hides the whole "What\'s happening" section, leaving promoted posts visible', () => {
    const root = mount(X_TIMELINE_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, trends: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'trends-section', 'trends')).toBe(true);
    expect(isHidden(root, 'promoted-post', 'promoted')).toBe(false);
  });

  it('falls back to hiding individual trend rows when the trends section itself is gone', () => {
    const root = mount(X_TRENDS_ROWS_ONLY_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, trends: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'trend-row-1', 'trends')).toBe(true);
    expect(isHidden(root, 'trend-row-2', 'trends')).toBe(true);
  });

  it('hides "Who to follow", leaving trends visible', () => {
    const root = mount(X_TIMELINE_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, whoToFollow: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'who-to-follow', 'whoToFollow')).toBe(true);
    expect(isHidden(root, 'trends-section', 'trends')).toBe(false);
  });

  it('hides the Grok entry, leaving the Explore nav visible', () => {
    const root = mount(X_TIMELINE_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, grok: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'grok-drawer', 'grok')).toBe(true);
    expect(isHidden(root, 'explore-nav', 'exploreNav')).toBe(false);
  });

  it('hides the Explore nav entry via data-testid, leaving Grok visible', () => {
    const root = mount(X_TIMELINE_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, exploreNav: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'explore-nav', 'exploreNav')).toBe(true);
    expect(isHidden(root, 'grok-drawer', 'grok')).toBe(false);
  });

  it('falls back to the nav-scoped href link when the Explore data-testid is absent', () => {
    const root = mount(X_EXPLORE_NAV_HREF_ONLY_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, exploreNav: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'explore-nav-href-only', 'exploreNav')).toBe(true);
  });

  it('reveals a surface the instant its toggle turns back off, without touching a still-on sibling', () => {
    const root = mount(X_TIMELINE_HTML);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, promoted: true, grok: true }, true, OVERLAY_ID);
    expect(isHidden(root, 'promoted-post', 'promoted')).toBe(true);
    expect(isHidden(root, 'grok-drawer', 'grok')).toBe(true);

    applyPlatformHides(root, HIDE_SURFACES, { ...ALL_OFF, promoted: false, grok: true }, true, OVERLAY_ID);

    expect(isHidden(root, 'promoted-post', 'promoted')).toBe(false);
    expect(isHidden(root, 'grok-drawer', 'grok')).toBe(true);
  });

  it('reveals everything when the extension is globally disabled', () => {
    const root = mount(X_TIMELINE_HTML);
    const allOn = { trends: true, whoToFollow: true, promoted: true, grok: true, exploreNav: true };

    applyPlatformHides(root, HIDE_SURFACES, allOn, true, OVERLAY_ID);
    applyPlatformHides(root, HIDE_SURFACES, allOn, false, OVERLAY_ID);

    expect(isHidden(root, 'promoted-post', 'promoted')).toBe(false);
    expect(isHidden(root, 'trends-section', 'trends')).toBe(false);
    expect(isHidden(root, 'who-to-follow', 'whoToFollow')).toBe(false);
    expect(isHidden(root, 'grok-drawer', 'grok')).toBe(false);
    expect(isHidden(root, 'explore-nav', 'exploreNav')).toBe(false);
  });
});

describe('initXContentScript — gate overlay', () => {
  function configWith(overrides: Partial<SiteConfig>): SiteConfig {
    return { ...IDLE_SITE_CONFIG, enabled: true, platform: 'x', ...overrides };
  }

  it('gates the home timeline with an interstitial in a BlockMode', async () => {
    mount(X_TIMELINE_HTML);
    window.history.replaceState({}, '', '/home');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'home', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initXContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });

  it('does not gate when the resolved mode for the matched gate is ALLOW', async () => {
    mount(X_TIMELINE_HTML);
    window.history.replaceState({}, '', '/notifications');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'notifications', mode: 'ALLOW', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initXContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });

  it('does not gate a URL that matches no X gate at all', async () => {
    mount(X_TIMELINE_HTML);
    window.history.replaceState({}, '', '/someuser/status/12345');

    const fetchConfig = vi.fn(async () =>
      configWith({ gates: [{ id: 'home', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false }] }),
    );

    const controller = initXContentScript(document, fetchConfig);
    await controller.reload();

    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    controller.stop();
  });

  it('SPA nav: gates the new URL after a pushState into Explore, having started on an ungated page', async () => {
    mount(X_TIMELINE_HTML);
    window.history.replaceState({}, '', '/someuser/status/12345');

    const currentGates: ResolvedGate[] = [
      { id: 'home', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false },
      { id: 'explore', mode: 'HARD_BLOCK', delaySeconds: 15, limitReached: false },
    ];
    const fetchConfig = vi.fn(async () => configWith({ gates: currentGates }));

    const controller = initXContentScript(document, fetchConfig);
    await controller.reload();
    expect(document.getElementById(OVERLAY_ID)).toBeNull();

    window.history.pushState({}, '', '/explore');
    controller.refresh();

    expect(document.getElementById(OVERLAY_ID)).not.toBeNull();

    controller.stop();
  });
});

describe('X content-script entrypoint — registers matches for both domains', () => {
  it('includes both x.com and twitter.com in its `matches` array', async () => {
    const mod = await import('../../../src/entrypoints/x.content');
    const definition = mod.default as { matches: readonly string[] };

    expect(definition.matches).toContain('*://*.x.com/*');
    expect(definition.matches).toContain('*://*.twitter.com/*');
  });
});
