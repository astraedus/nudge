/**
 * LinkedIn fixture DOM.
 *
 * HAND-AUTHORED, NOT A LIVE CAPTURE — same caveat as every other fixture in this repo
 * (tests/content/fixtures/youtube.ts et al.). Shapes the two elements this platform's
 * tests actually touch: the `suggestedFollows` hide surface
 * (`.scaffold-layout__aside .feed-follows-module`, sourced in
 * `content/linkedin/selectors.ts`) plus an unrelated feed post that must never be caught
 * by that selector.
 */

export const LINKEDIN_FEED_HTML = `
  <div class="scaffold-layout">
    <main class="scaffold-layout__main">
      <div data-testid="mainFeed">
        <div data-testid="feed-post">a post</div>
      </div>
    </main>
    <aside class="scaffold-layout__aside">
      <div class="feed-follows-module">
        <h3>Add to your feed</h3>
        <div class="follows-recommendation">Suggested Person</div>
      </div>
    </aside>
  </div>
`;
