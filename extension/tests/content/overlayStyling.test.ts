/**
 * Guard against the overlay-root/element-id mismatch that made every non-YouTube
 * interstitial render as an ordinary in-flow block instead of a full-screen backdrop.
 *
 * `overlay.ts`'s `overlayIdFor(platform)` derives a per-platform element id
 * (`nudge-gate-${platform}`), but `platformOverlay.css` styles the fixed/inset-0 overlay
 * root with a hand-written selector list, because CSS has no way to express the same
 * template `overlayIdFor` uses. If that list ever drifts from the platform registry —
 * one id renamed, one platform added and forgotten — the affected platform's gate mounts
 * correctly (the DOM node exists, the child `.nudge-overlay__*` class rules still apply)
 * but the root positioning rule silently never matches. The gate then paints as a small
 * block sitting in the page flow instead of covering it, which looks exactly like a
 * broken/skippable gate to the user. Neither `tsc` nor a DOM test catches this, because
 * nothing else ever compares the ids `overlayIdFor` produces against the strings written
 * into the stylesheet — this file is that comparison.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { PLATFORMS } from '../../src/core/platforms';
import { overlayIdFor } from '../../src/content/overlay';
import { NUDGE_OVERLAY_ID } from '../../src/content/selectors';

const PLATFORM_OVERLAY_CSS = readFileSync(
  fileURLToPath(new URL('../../src/content/platformOverlay.css', import.meta.url)),
  'utf-8',
);

const YOUTUBE_CSS = readFileSync(
  fileURLToPath(new URL('../../src/content/youtube.css', import.meta.url)),
  'utf-8',
);

/**
 * Whitespace-tolerant check for a `#<id>.nudge-overlay` root rule in `css`. Escapes `id`
 * so it is safe to use inside a regex even though every real id here is a plain
 * hyphenated word.
 */
function hasOverlayRootRule(css: string, id: string): boolean {
  const escaped = id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`#${escaped}\\s*\\.nudge-overlay\\b`).test(css);
}

describe("every platform's interstitial gets a full-screen backdrop rule that actually matches its element id", () => {
  const nonYouTubePlatforms = PLATFORMS.filter((platform) => platform.id !== 'youtube');

  it('found at least one non-YouTube platform to check (guard is not vacuous)', () => {
    expect(nonYouTubePlatforms.length).toBeGreaterThan(0);
  });

  it.each(nonYouTubePlatforms.map((platform) => [platform.id, platform] as const))(
    '%s: overlayIdFor id has a matching root rule in platformOverlay.css',
    (_label, platform) => {
      const id = overlayIdFor(platform.id);
      expect(hasOverlayRootRule(PLATFORM_OVERLAY_CSS, id)).toBe(true);
    },
  );

  it("YouTube's NUDGE_OVERLAY_ID has a matching root rule in youtube.css", () => {
    expect(hasOverlayRootRule(YOUTUBE_CSS, NUDGE_OVERLAY_ID)).toBe(true);
  });

  it('does NOT match a bogus id (proves the matcher can actually fail)', () => {
    expect(hasOverlayRootRule(PLATFORM_OVERLAY_CSS, 'nudge-gate-doesnotexist')).toBe(false);
    expect(hasOverlayRootRule(YOUTUBE_CSS, 'nudge-gate-doesnotexist')).toBe(false);
  });
});

/**
 * The pacer's rules live in BOTH static stylesheets (each platform script injects exactly
 * one), so the same breath circle is written twice. A tweak to one copy and not the other
 * would leave YouTube's Breathing gate looking different from the other six, with no
 * error anywhere. Pin the two copies to each other.
 */
const PACER_SELECTOR = /^\.nudge-overlay__(breath|phase|progress|bar|remaining)\b/;

function pacerRules(css: string): string[] {
  const withoutComments = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const rules: string[] = [];
  for (const match of withoutComments.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const selectors = (match[1] ?? '').split(',').map((s) => s.trim());
    if (!selectors.every((s) => PACER_SELECTOR.test(s))) continue;
    const body = (match[2] ?? '').replace(/\s+/g, ' ').trim();
    rules.push(`${selectors.join(', ')} { ${body} }`);
  }
  return rules;
}

describe('the Breathing pacer is styled identically in both overlay stylesheets', () => {
  it('found the pacer rules (guard is not vacuous)', () => {
    const rules = pacerRules(PLATFORM_OVERLAY_CSS);
    expect(rules.some((r) => r.startsWith('.nudge-overlay__breath-stage'))).toBe(true);
    expect(rules.some((r) => r.startsWith('.nudge-overlay__bar'))).toBe(true);
  });

  it('platformOverlay.css and youtube.css carry the same pacer rules', () => {
    expect(pacerRules(YOUTUBE_CSS)).toEqual(pacerRules(PLATFORM_OVERLAY_CSS));
  });

  it('a drifted copy is caught (planted defect)', () => {
    const drifted = PLATFORM_OVERLAY_CSS.replace('font-size: 34px', 'font-size: 30px');
    expect(drifted).not.toBe(PLATFORM_OVERLAY_CSS);
    expect(pacerRules(drifted)).not.toEqual(pacerRules(PLATFORM_OVERLAY_CSS));
  });
});
