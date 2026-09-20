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
