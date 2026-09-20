/**
 * Hand-authored DOM fixtures for the TikTok content script tests — built from the
 * `data-e2e` selector table in ops/routes/nudge/research/ext-12-reels-and-feeds-web-
 * techniques.md §B (see also `src/content/tiktok/selectors.ts`), NOT live captures.
 *
 * Every element also carries a `data-testid` so tests can assert on a stable handle that
 * is independent of the `data-e2e` attribute the selector chains themselves key off —
 * mirrors the convention in `tests/content/fixtures/unhookPages.ts`.
 */

/** One of everything TikTok exposes a hide surface for, plus untouched decoy content. */
export const TIKTOK_FEED_HTML = `
  <div data-e2e="comment-list" data-testid="comment-list">comments go here</div>
  <form data-e2e="search-box" data-testid="search-box">
    <input data-e2e="search-user-input" data-testid="search-input" />
  </form>
  <a data-e2e="nav-live" data-testid="nav-live" href="/live">Live</a>
  <a data-e2e="nav-explore" data-testid="nav-explore" href="/explore">Explore</a>
  <div data-e2e="suggest-accounts" data-testid="suggested-accounts">Suggested accounts</div>
  <div data-testid="feed">the For You feed itself</div>
`;

/**
 * `search-box` form missing (a hypothetical DOM rename) — only the bare input survives.
 * Exercises the secondary `input[data-e2e="search-user-input"]` rung.
 */
export const TIKTOK_SEARCH_INPUT_ONLY_HTML = `
  <input data-e2e="search-user-input" data-testid="search-input" />
  <div data-testid="feed">feed</div>
`;

/**
 * The OTHER suggested-accounts spelling ext-12 found live on some builds/surfaces.
 * Exercises the second rung of the `suggestedAccounts` chain.
 */
export const TIKTOK_SUGGESTED_ALT_SPELLING_HTML = `
  <div data-e2e="suggested-accounts" data-testid="suggested-accounts-alt">Suggested accounts</div>
  <div data-testid="feed">feed</div>
`;

/** Explore nav rendered without the data-e2e attribute — exercises the href secondary rung. */
export const TIKTOK_EXPLORE_NAV_HREF_ONLY_HTML = `
  <a href="/explore" data-testid="nav-explore-href-only">Explore</a>
  <div data-testid="feed">feed</div>
`;
