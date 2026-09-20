/**
 * Hand-authored DOM fixtures for the X (Twitter) content script tests — built from the
 * `data-testid` selector table in ops/routes/nudge/research/ext-12-reels-and-feeds-web-
 * techniques.md §C / `insin/control-panel-for-twitter`'s `Selectors` enum (see also
 * `src/content/x/selectors.ts`), NOT live captures.
 */

/** One of everything X exposes a hide surface for, plus untouched decoy content. */
export const X_TIMELINE_HTML = `
  <div data-testid="placementTracking" data-testid-fixture="promoted-post">Promoted post</div>
  <div data-testid="sidebarColumn">
    <section data-testid-fixture="trends-section">
      <h2 aria-label="Timeline: Trending now">Trends for you</h2>
      <div data-testid="trend" data-testid-fixture="trend-row-1">#Trend1</div>
      <div data-testid="trend" data-testid-fixture="trend-row-2">#Trend2</div>
    </section>
    <aside data-testid-fixture="who-to-follow">
      <div data-testid="UserCell" data-testid-fixture="user-cell-1">Suggested user</div>
    </aside>
  </div>
  <nav role="navigation" data-testid-fixture="app-nav">
    <a data-testid="AppTabBar_Explore_Link" data-testid-fixture="explore-nav" href="/explore">Explore</a>
  </nav>
  <div data-testid-fixture="grok-drawer" data-testid="GrokDrawer">Grok</div>
  <article data-testid-fixture="tweet">an ordinary post</article>
`;

/**
 * The "What's happening" section itself is gone (a hypothetical DOM rename) — only bare
 * trend rows remain. Exercises the degraded `[data-testid="trend"]` fallback rung.
 */
export const X_TRENDS_ROWS_ONLY_HTML = `
  <div data-testid="sidebarColumn">
    <div data-testid="trend" data-testid-fixture="trend-row-1">#Trend1</div>
    <div data-testid="trend" data-testid-fixture="trend-row-2">#Trend2</div>
  </div>
  <article data-testid-fixture="tweet">an ordinary post</article>
`;

/** Explore nav rendered without the data-testid attribute — exercises the secondary rung. */
export const X_EXPLORE_NAV_HREF_ONLY_HTML = `
  <nav role="navigation">
    <a href="/explore" data-testid-fixture="explore-nav-href-only">Explore</a>
  </nav>
  <article data-testid-fixture="tweet">an ordinary post</article>
`;
