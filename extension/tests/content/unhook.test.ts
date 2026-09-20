// @vitest-environment jsdom
/**
 * Tests for the Unhook-parity hide toggles (`applyHideToggles`) and the best-effort
 * autoplay-off click (`applyAutoplayOff`).
 *
 * Every test is phrased as a USER-VISIBLE outcome (what's on screen / what got clicked),
 * never as "which selector rung matched" or "which internal branch ran" — this codebase has
 * a documented incident (extension/CLAUDE.md "Lessons") where a critical bug survived 400
 * tests because two of them asserted the internal branch instead of the visible behaviour.
 * "Hidden" here means "carries `UNHOOK_HIDDEN_CLASS`", the same class the real stylesheet
 * keys off `display: none` — checking the class IS checking what the user would see.
 *
 * Repo default vitest environment is `node` (src/core is pure); this file opts into jsdom.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NUDGE_OVERLAY_ID, resetFallbackWarnings } from '../../src/content/selectors';
import { UNHOOK_HIDDEN_CLASS, applyAutoplayOff, applyHideToggles } from '../../src/content/unhook';
import {
  HOME_PAGE_HTML,
  WATCH_PAGE_ALL_SURFACES_HTML,
  WATCH_PAGE_AUTOPLAY_OFF_HTML,
  WATCH_PAGE_RENAMED_COMMENTS_HTML,
} from './fixtures/unhookPages';

/** Mount a fixture in a detached root so tests never fight over document.body. */
function mount(html: string): HTMLElement {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.replaceChildren(root);
  return root;
}

/** Every toggle off — the caller opts individual surfaces in from this baseline. */
const ALL_OFF = {
  enabled: true,
  hides: {},
};

/** Build a hide-toggle config, keyed by the platform-registry hide ids. */
function withHides(hides: Partial<Record<'homeFeed' | 'sidebarRecs' | 'endScreen' | 'comments', boolean>>) {
  return { enabled: true, hides };
}

function isHidden(root: HTMLElement, testId: string): boolean {
  const el = root.querySelector(`[data-testid="${testId}"]`);
  if (!el) throw new Error(`fixture missing [data-testid="${testId}"]`);
  return el.classList.contains(UNHOOK_HIDDEN_CLASS);
}

beforeEach(() => {
  resetFallbackWarnings();
});

describe('applyHideToggles — each toggle independently hides only its own surface', () => {
  it('hides the home feed and leaves the rest of the page visible', () => {
    const root = mount(HOME_PAGE_HTML);

    const result = applyHideToggles(root, withHides({ homeFeed: true }), {
      pageType: 'home',
      warn: () => {},
    });

    expect(result.hidden).toBeGreaterThan(0);
    expect(isHidden(root, 'home-feed-contents')).toBe(true);
    expect(isHidden(root, 'masthead')).toBe(false);
    expect(isHidden(root, 'home-related-decoy')).toBe(false);
  });

  it('hides the sidebar recommendations on a watch page, leaving comments and the end screen visible', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);

    applyHideToggles(root, withHides({ sidebarRecs: true }), { pageType: 'watch', warn: () => {} });

    expect(isHidden(root, 'watch-related')).toBe(true);
    expect(isHidden(root, 'watch-comments')).toBe(false);
    expect(isHidden(root, 'watch-end-screen')).toBe(false);
  });

  it('hides the in-player end screen on a watch page, leaving comments and recommendations visible', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);

    applyHideToggles(root, withHides({ endScreen: true }), { pageType: 'watch', warn: () => {} });

    expect(isHidden(root, 'watch-end-screen')).toBe(true);
    expect(isHidden(root, 'watch-related')).toBe(false);
    expect(isHidden(root, 'watch-comments')).toBe(false);
  });

  // Hiding the whole `#comments` section (not just its inner list) is deliberate: leaving
  // the wrapper visible left the "N Comments / Sort by" header on screen as dead chrome
  // (live QA, 2026-07-26).
  it('hides the comments section on a watch page, leaving the end screen and recommendations visible', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);

    applyHideToggles(root, withHides({ comments: true }), { pageType: 'watch', warn: () => {} });

    expect(isHidden(root, 'watch-comments')).toBe(true);
    expect(isHidden(root, 'watch-end-screen')).toBe(false);
    expect(isHidden(root, 'watch-related')).toBe(false);
  });

  it('still hides comments when YouTube has renamed the primary comments wrapper', () => {
    const root = mount(WATCH_PAGE_RENAMED_COMMENTS_HTML);

    applyHideToggles(root, withHides({ comments: true }), { pageType: 'watch', warn: () => {} });

    expect(isHidden(root, 'renamed-comments-legacy')).toBe(true);
    expect(isHidden(root, 'renamed-end-screen')).toBe(false);
    expect(isHidden(root, 'renamed-related')).toBe(false);
  });
});

describe('applyHideToggles — an absent key reads as off', () => {
  it('leaves every surface visible when the hides map is empty', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);

    const result = applyHideToggles(root, ALL_OFF, { pageType: 'watch', warn: () => {} });

    expect(result.hidden).toBe(0);
    expect(isHidden(root, 'watch-related')).toBe(false);
    expect(isHidden(root, 'watch-comments')).toBe(false);
    expect(isHidden(root, 'watch-end-screen')).toBe(false);
  });

  it('leaves a surface visible when every OTHER key is present but this one is simply absent', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);

    // comments is never mentioned at all — not `false`, just missing — while the other
    // two surfaces are explicitly on. An older stored settings blob, or a hide the
    // registry gained after a profile was written, must never retroactively start hiding.
    const result = applyHideToggles(
      root,
      withHides({ sidebarRecs: true, endScreen: true }),
      { pageType: 'watch', warn: () => {} },
    );

    expect(isHidden(root, 'watch-related')).toBe(true);
    expect(isHidden(root, 'watch-end-screen')).toBe(true);
    expect(isHidden(root, 'watch-comments')).toBe(false);
    expect(result.hidden).toBeGreaterThan(0);
  });
});

describe('applyHideToggles — turning one surface off never un-hides the others', () => {
  it('keeps sidebar recs and the end screen hidden after comments is turned back off', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);

    // Start with all three on...
    applyHideToggles(
      root,
      withHides({ sidebarRecs: true, endScreen: true, comments: true }),
      { pageType: 'watch', warn: () => {} },
    );
    expect(isHidden(root, 'watch-related')).toBe(true);
    expect(isHidden(root, 'watch-end-screen')).toBe(true);
    expect(isHidden(root, 'watch-comments')).toBe(true);

    // ...then flip ONLY comments off. The other two must still be hidden: each feature's
    // state is reconciled together in one pass, so turning one off must never read as
    // "reveal everything" the way a naive incremental toggle could.
    const result = applyHideToggles(
      root,
      withHides({ sidebarRecs: true, endScreen: true, comments: false }),
      { pageType: 'watch', warn: () => {} },
    );

    expect(isHidden(root, 'watch-comments')).toBe(false);
    expect(isHidden(root, 'watch-related')).toBe(true);
    expect(isHidden(root, 'watch-end-screen')).toBe(true);
    expect(result.revealed).toBeGreaterThan(0);
  });
});

describe('applyHideToggles — flipping a toggle off reveals what it hid', () => {
  it('brings the comments section back, still in the DOM, once hideComments is turned off', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);
    const nodeCountBefore = root.querySelectorAll('*').length;

    applyHideToggles(root, withHides({ comments: true }), { pageType: 'watch', warn: () => {} });
    expect(isHidden(root, 'watch-comments')).toBe(true);

    const off = applyHideToggles(root, ALL_OFF, { pageType: 'watch', warn: () => {} });

    expect(off.revealed).toBeGreaterThan(0);
    expect(isHidden(root, 'watch-comments')).toBe(false);
    // Reversible by construction: no node was ever removed, only the class toggled.
    expect(root.querySelectorAll('*').length).toBe(nodeCountBefore);
    expect(root.querySelector('[data-testid="watch-comments"]')).not.toBeNull();
  });
});

describe('applyHideToggles — enabled: false reveals everything', () => {
  it('brings back every surface it had hidden when Nudge is globally disabled', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);

    applyHideToggles(
      root,
      withHides({ sidebarRecs: true, endScreen: true, comments: true }),
      { pageType: 'watch', warn: () => {} },
    );
    expect(isHidden(root, 'watch-related')).toBe(true);
    expect(isHidden(root, 'watch-end-screen')).toBe(true);
    expect(isHidden(root, 'watch-comments')).toBe(true);

    const result = applyHideToggles(
      root,
      { enabled: false, hides: { sidebarRecs: true, endScreen: true, comments: true } },
      { pageType: 'watch', warn: () => {} },
    );

    expect(result.hidden).toBe(0);
    expect(result.revealed).toBeGreaterThan(0);
    expect(isHidden(root, 'watch-related')).toBe(false);
    expect(isHidden(root, 'watch-end-screen')).toBe(false);
    expect(isHidden(root, 'watch-comments')).toBe(false);
    expect(root.querySelectorAll(`.${UNHOOK_HIDDEN_CLASS}`)).toHaveLength(0);
  });
});

describe('applyHideToggles — page-type scoping', () => {
  it('never hides a watch-only surface on the home page, even one shaped exactly like it', () => {
    const root = mount(HOME_PAGE_HTML);

    // hideSidebarRecs is ON, but sidebar-recs only applies to 'watch' pages — the home
    // page's #related-shaped decoy must survive because the surface is never even
    // considered here, not because this particular selector happened to miss it.
    applyHideToggles(root, withHides({ sidebarRecs: true }), { pageType: 'home', warn: () => {} });

    expect(isHidden(root, 'home-related-decoy')).toBe(false);
  });
});

describe('applyHideToggles — our own overlay is never hidden', () => {
  it('hides the real end screen but never anything living inside the Nudge overlay', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);
    expect(root.querySelector(`#${NUDGE_OVERLAY_ID}`)).not.toBeNull();

    applyHideToggles(root, withHides({ endScreen: true }), { pageType: 'watch', warn: () => {} });

    expect(isHidden(root, 'watch-end-screen')).toBe(true);
    expect(isHidden(root, 'overlay-endscreen-decoy')).toBe(false);
    expect(isHidden(root, 'nudge-overlay')).toBe(false);
  });
});

describe('applyHideToggles — never throws', () => {
  it('does nothing and does not throw on a page with none of the expected DOM', () => {
    const root = mount('');

    expect(() =>
      applyHideToggles(
        root,
        withHides({ homeFeed: true, sidebarRecs: true, endScreen: true, comments: true }),
        { pageType: 'home', warn: () => {} },
      ),
    ).not.toThrow();

    const result = applyHideToggles(
      root,
      withHides({ homeFeed: true, sidebarRecs: true, endScreen: true, comments: true }),
      { pageType: 'watch', warn: () => {} },
    );
    expect(result).toEqual({ hidden: 0, revealed: 0, degradedSurfaces: [] });

    expect(() =>
      applyHideToggles(root, { ...ALL_OFF, enabled: false }, { pageType: 'other' }),
    ).not.toThrow();
  });
});

describe('applyAutoplayOff', () => {
  it('clicks the autoplay switch off when it is currently on', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);
    const clickSpy = vi.spyOn(HTMLElement.prototype, 'click');

    try {
      const clicked = applyAutoplayOff(
        root,
        { enabled: true, youtube: { channelMode: 'OFF', channels: [], channelBlockMode: 'HARD_BLOCK', channelDelaySeconds: 0, disableAutoplay: true } },
        { warn: () => {} },
      );

      expect(clicked).toBe(true);
      expect(clickSpy).toHaveBeenCalledTimes(1);
      const toggle = root.querySelector('[data-testid="autoplay-toggle"]');
      expect(clickSpy.mock.instances).toContain(toggle);
    } finally {
      clickSpy.mockRestore();
    }
  });

  it('does not click anything when autoplay is already off', () => {
    const root = mount(WATCH_PAGE_AUTOPLAY_OFF_HTML);
    const clickSpy = vi.spyOn(HTMLElement.prototype, 'click');

    try {
      const clicked = applyAutoplayOff(
        root,
        { enabled: true, youtube: { channelMode: 'OFF', channels: [], channelBlockMode: 'HARD_BLOCK', channelDelaySeconds: 0, disableAutoplay: true } },
        { warn: () => {} },
      );

      expect(clicked).toBe(false);
      expect(clickSpy).not.toHaveBeenCalled();
      const toggle = root.querySelector('[data-testid="autoplay-toggle-off"]');
      expect(toggle?.getAttribute('aria-checked')).toBe('false');
    } finally {
      clickSpy.mockRestore();
    }
  });

  it('does nothing when the disableAutoplay toggle itself is off, even if the switch is on', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);
    const clickSpy = vi.spyOn(HTMLElement.prototype, 'click');

    try {
      const clicked = applyAutoplayOff(
        root,
        { enabled: true, youtube: { channelMode: 'OFF', channels: [], channelBlockMode: 'HARD_BLOCK', channelDelaySeconds: 0, disableAutoplay: false } },
        { warn: () => {} },
      );

      expect(clicked).toBe(false);
      expect(clickSpy).not.toHaveBeenCalled();
    } finally {
      clickSpy.mockRestore();
    }
  });

  it('does nothing when `youtube` is null (every non-YouTube platform, or an unruled YouTube page)', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);
    const clickSpy = vi.spyOn(HTMLElement.prototype, 'click');

    try {
      const clicked = applyAutoplayOff(root, { enabled: true, youtube: null }, { warn: () => {} });

      expect(clicked).toBe(false);
      expect(clickSpy).not.toHaveBeenCalled();
    } finally {
      clickSpy.mockRestore();
    }
  });

  it('calling it twice never re-enables autoplay', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);
    const toggle = root.querySelector('[data-testid="autoplay-toggle"]');
    if (!toggle) throw new Error('fixture missing the autoplay toggle');
    // Model YouTube's own response to the click: the switch flips itself off. Nothing in
    // applyAutoplayOff does this — it only ever acts on a switch that reads as ON.
    toggle.addEventListener('click', () => toggle.setAttribute('aria-checked', 'false'));

    const config = {
      enabled: true,
      youtube: {
        channelMode: 'OFF' as const,
        channels: [],
        channelBlockMode: 'HARD_BLOCK' as const,
        channelDelaySeconds: 0,
        disableAutoplay: true,
      },
    };
    const first = applyAutoplayOff(root, config, { warn: () => {} });
    expect(first).toBe(true);
    expect(toggle.getAttribute('aria-checked')).toBe('false');

    const second = applyAutoplayOff(root, config, { warn: () => {} });
    expect(second).toBe(false);
    // Still off — a second pass never turns it back on.
    expect(toggle.getAttribute('aria-checked')).toBe('false');
  });

  it('does not throw on a page with no player at all', () => {
    const root = mount('');
    const config = {
      enabled: true,
      youtube: {
        channelMode: 'OFF' as const,
        channels: [],
        channelBlockMode: 'HARD_BLOCK' as const,
        channelDelaySeconds: 0,
        disableAutoplay: true,
      },
    };

    expect(() => applyAutoplayOff(root, config, { warn: () => {} })).not.toThrow();
    expect(applyAutoplayOff(root, config)).toBe(false);
  });
});

describe('applyHideToggles, the end screen has two parts that show together', () => {
  /**
   * Live QA, 2026-07-26: `.ytp-endscreen-content` (the suggestion grid) and
   * `.ytp-ce-element` (the creator's own end-cards) are DIFFERENT elements shown at the same
   * time, not alternative selectors for one element. First-match-wins hid the grid and left
   * the cards sitting on top of the video.
   */
  it('hides both the suggestion grid and the creator end-cards', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);

    applyHideToggles(root, withHides({ endScreen: true }), { pageType: 'watch', warn: () => {} });

    expect(isHidden(root, 'watch-end-screen')).toBe(true);
    expect(isHidden(root, 'watch-end-cards')).toBe(true);
  });

  it('brings both back when the toggle is switched off', () => {
    const root = mount(WATCH_PAGE_ALL_SURFACES_HTML);
    applyHideToggles(root, withHides({ endScreen: true }), { pageType: 'watch', warn: () => {} });

    applyHideToggles(root, withHides({ endScreen: false }), { pageType: 'watch', warn: () => {} });

    expect(isHidden(root, 'watch-end-screen')).toBe(false);
    expect(isHidden(root, 'watch-end-cards')).toBe(false);
  });
});
