/**
 * X (Twitter) content script entrypoint — thin on purpose, same shape as
 * `youtube.content.ts`. All logic lives in `src/content/x/` so it is unit-testable
 * against fixture DOM without a browser.
 *
 *  - `matches`: BOTH `x.com` and `twitter.com` — the registry's `x` platform lists both
 *    domains because twitter.com still resolves and users still have rules on it.
 *  - `runAt: 'document_idle'`: hides/gate apply once the SPA has rendered its timeline.
 *  - `cssInjectionMode: 'manifest'`: both stylesheets land in this content script's
 *    manifest `css` array, injected by Chrome before any DOM is constructed.
 */

import '../content/x.css';
import '../content/platformOverlay.css';
import { initXContentScript } from '../content/x';

export default defineContentScript({
  matches: ['*://*.x.com/*', '*://*.twitter.com/*'],
  runAt: 'document_idle',
  cssInjectionMode: 'manifest',
  main(ctx) {
    const controller = initXContentScript();
    // An extension reload leaves the old script alive on the page; without this it keeps
    // its observer/poll/listener running against a dead message port.
    ctx.onInvalidated(() => controller.stop());
  },
});
