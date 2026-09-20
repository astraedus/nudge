/**
 * HAND-AUTHORED fixture DOM for the Instagram hide surfaces, built from the ext-12
 * selector table (see `content/instagram/selectors.ts`) — NOT live captures. Same caveat
 * as every other fixture file in `tests/content/fixtures/`.
 *
 * Every fixture that hides something also carries at least one element/sibling that must
 * survive, so a chain that over-matches gets caught.
 */

/**
 * Primary case: every hide surface reachable via its FIRST chain rung, plus decoys
 * (other nav entries, an ordinary feed post) that must never be touched.
 */
export const INSTAGRAM_PRIMARY_HTML = `
  <nav data-testid="primary-nav">
    <a href="/" data-testid="home-nav">Home</a>
    <a href="/reels/" data-testid="reels-nav">
      <svg aria-label="Reels" data-testid="reels-nav-icon"></svg>
    </a>
    <a href="/explore/" data-testid="explore-nav">Explore</a>
    <a href="/direct/inbox/" data-testid="direct-nav">Messages</a>
  </nav>
  <div scrollable="true" data-testid="stories-tray">
    <a aria-label="Story by alice" href="/stories/alice/" data-testid="story-alice">alice</a>
    <a aria-label="Story by bob" href="/stories/bob/" data-testid="story-bob">bob</a>
  </div>
  <div data-testid="suggested-block">
    <div>
      <a href="/explore/people/" data-testid="suggested-see-all">See All</a>
    </div>
  </div>
  <div data-testid="feed">
    <article data-testid="feed-post-1">A normal post</article>
  </div>
`;

/**
 * `reelsNav` reachable only via its icon-anchored fallback rung: the href no longer
 * exactly matches (nor ends with) `/reels/`, so only `nav a:has(svg[aria-label*="Reel"])`
 * — climbing from the aria-labelled icon to its containing `<a>` — can find it.
 */
export const INSTAGRAM_REELS_NAV_FALLBACK_HTML = `
  <nav data-testid="primary-nav">
    <a href="/reels/?badge=1" data-testid="reels-nav-fallback">
      <svg aria-label="Reels tab" data-testid="reels-nav-icon"></svg>
    </a>
    <a href="/explore/" data-testid="explore-nav">Explore</a>
  </nav>
`;

/**
 * `stories` reachable only via the hashed-class last resort: no `[scrollable="true"]` or
 * `[role="presentation"]` ancestor wraps the story anchors, only the legacy `ul._acay`
 * class does.
 */
export const INSTAGRAM_STORIES_HASHED_FALLBACK_HTML = `
  <ul class="_acay" data-testid="stories-tray-hashed">
    <li><a aria-label="Story by carol" href="/stories/carol/" data-testid="story-carol">carol</a></li>
  </ul>
  <div data-testid="feed">
    <article data-testid="feed-post-1">A normal post</article>
  </div>
`;
