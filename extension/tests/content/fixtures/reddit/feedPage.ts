/**
 * Reddit fixture DOM.
 *
 * HAND-AUTHORED, NOT A LIVE CAPTURE — same caveat as every other fixture in this repo
 * (tests/content/fixtures/youtube.ts et al.). Reddit ships zero hide surfaces
 * (`content/reddit/selectors.ts`), so this fixture only needs to be "a page the gate
 * overlay can be appended to" — its content is deliberately minimal.
 */

export const REDDIT_PAGE_HTML = `
  <shreddit-app>
    <main>
      <div data-testid="post-container">a post</div>
    </main>
  </shreddit-app>
`;
