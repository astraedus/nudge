/**
 * The block page's address, in one place. PURE.
 *
 * Three callers reach the block page: the DNR redirect (`background/dnr.ts`, which can only
 * append the original URL through `regexSubstitution`), the worker pushing open tabs there
 * when a limit is crossed, and the YouTube content script refusing a page DNR had to let
 * through (a legacy `/c/` channel URL that turned out not to be on the list). All three must
 * produce the shape `entrypoints/blocked/BlockPage.tsx` parses.
 */

/** The block page, relative to the extension root: `chrome.runtime.getURL(BLOCK_PAGE_PATH)`. */
export const BLOCK_PAGE_PATH = 'blocked.html';

/**
 * Everything after this marker is the original URL, VERBATIM and unencoded.
 *
 * `target` is deliberately last and unencoded: `regexSubstitution` cannot percent-encode, so
 * the original URL routinely carries its own `?`, `&` and `#`, and the block page reads
 * everything after the first marker rather than parsing query parameters.
 */
export const BLOCK_TARGET_MARKER = '?target=';

/** `blockPage` is the absolute block-page URL (`chrome.runtime.getURL(BLOCK_PAGE_PATH)`). */
export function blockPageUrl(blockPage: string, target: string): string {
  return `${blockPage}${BLOCK_TARGET_MARKER}${target}`;
}
