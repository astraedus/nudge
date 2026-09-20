/**
 * HAND-AUTHORED fixture DOM for the Facebook hide surfaces, built from the ext-12
 * selector table (see `content/facebook/selectors.ts`) — NOT live captures. Same caveat
 * as every other fixture file in `tests/content/fixtures/`.
 *
 * Every fixture that hides something also carries at least one element/sibling that must
 * survive, so a chain that over-matches gets caught.
 */

/**
 * Primary case: every hide surface reachable via its FIRST chain rung, plus decoys
 * (other nav rows, an ordinary feed post) that must never be touched.
 */
export const FACEBOOK_PRIMARY_HTML = `
  <ul data-testid="left-rail">
    <li data-testid="feed-nav-row"><a href="/">Home</a></li>
    <li data-testid="reels-nav-row"><a href="/reel/?s=tab" data-testid="reels-nav-link">Reels</a></li>
    <li data-testid="marketplace-nav-row"><a href="/marketplace/">Marketplace</a></li>
  </ul>
  <div scrollable="true" data-testid="stories-tray">
    <div aria-label="Stories" data-testid="stories-marker">
      <a href="/stories/create/" data-testid="stories-create">Create story</a>
    </div>
  </div>
  <div data-pagelet="PeopleYouMayKnow" data-testid="people-you-may-know">
    <span>People You May Know</span>
  </div>
  <div data-testid="feed">
    <article data-testid="feed-post-1">A normal post</article>
  </div>
`;

/**
 * `reelsNav` reachable only via the anchor-only fallback: no `<li>` wraps the Reels link
 * this time (e.g. a compact-nav layout), only the bare anchor.
 */
export const FACEBOOK_REELS_NAV_FALLBACK_HTML = `
  <div data-testid="compact-nav">
    <a href="/reel/?s=tab" data-testid="reels-nav-link-bare">Reels</a>
    <a href="/marketplace/" data-testid="marketplace-nav-link">Marketplace</a>
  </div>
`;

/**
 * `stories` reachable only via the hashed-class last resort: no `[scrollable="true"]`
 * ancestor wraps the `[aria-label="Stories"]` marker.
 */
export const FACEBOOK_STORIES_HASHED_FALLBACK_HTML = `
  <div class="xb57i2i" data-testid="stories-tray-hashed">
    <div aria-label="Stories" data-testid="stories-marker">stories</div>
  </div>
  <div data-testid="feed">
    <article data-testid="feed-post-1">A normal post</article>
  </div>
`;

/**
 * `peopleYouMayKnow` reachable only via the structural last-resort rung: no pagelet or
 * aria-label marker, just the bare structural shape.
 */
export const FACEBOOK_PEOPLE_YOU_MAY_KNOW_STRUCTURAL_HTML = `
  <div class="x1xnnf8n" data-testid="feed-column">
    <div data-testid="feed-item-1">post one</div>
    <div data-testid="feed-item-2">post two</div>
    <div data-testid="people-you-may-know-structural">People You May Know (no markers)</div>
  </div>
`;
