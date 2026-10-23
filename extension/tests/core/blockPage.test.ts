import { describe, expect, it } from 'vitest';
import { BLOCK_PAGE_PATH, BLOCK_TARGET_MARKER, blockPageUrl } from '../../src/core/blockPage';

/**
 * Three callers reach the block page and one page parses it. The target has to arrive
 * VERBATIM: DNR's `regexSubstitution` cannot percent-encode, so the original URL carries its
 * own `?`, `&` and `#`, and `BlockPage.tsx` reads everything after the first marker.
 */

const BLOCK_PAGE = `chrome-extension://abcdef/${BLOCK_PAGE_PATH}`;

/** What `BlockPage.tsx#readTargetParam` does with `location.href`. */
function parseLikeBlockPage(href: string): string | null {
  const markerAt = href.indexOf(BLOCK_TARGET_MARKER);
  if (markerAt === -1) return null;
  const raw = href.slice(markerAt + BLOCK_TARGET_MARKER.length);
  return raw === '' ? null : raw;
}

describe('the block page address', () => {
  it('keeps a target with its own query and fragment verbatim, as the last thing in the URL', () => {
    const target = 'https://www.youtube.com/watch?v=abc&t=30#x';

    const url = blockPageUrl(BLOCK_PAGE, target);

    expect(url.endsWith(target)).toBe(true);
    expect(url).toBe(`${BLOCK_PAGE}${BLOCK_TARGET_MARKER}${target}`);
    expect(url).not.toContain('%');
  });

  it('is read back to the exact original URL by the block page parser', () => {
    for (const target of [
      'https://www.youtube.com/watch?v=abc&t=30#x',
      'https://www.youtube.com/@veritasium',
      'https://example.com/a?target=nested&b=%20c',
    ]) {
      expect(parseLikeBlockPage(blockPageUrl(BLOCK_PAGE, target))).toBe(target);
    }
  });

  it('puts the marker before any character of the target', () => {
    // If the marker appeared after a `?`-bearing target, the parser would cut the target.
    const url = blockPageUrl(BLOCK_PAGE, 'https://x.test/?a=1');
    expect(url.indexOf(BLOCK_TARGET_MARKER)).toBe(BLOCK_PAGE.length);
  });
});
