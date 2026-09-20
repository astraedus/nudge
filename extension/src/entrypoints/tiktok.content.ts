/**
 * TikTok content script entrypoint — thin on purpose, same shape as
 * `youtube.content.ts`. All logic lives in `src/content/tiktok/` so it is unit-testable
 * against fixture DOM without a browser.
 *
 *  - `matches`: tiktok.com only (the registry's one domain for this platform).
 *  - `runAt: 'document_idle'`: hides/gate apply once TikTok has rendered its feed.
 *  - `cssInjectionMode: 'manifest'`: both stylesheets land in this content script's
 *    manifest `css` array, which Chrome injects before any DOM is constructed — the
 *    FOUC-safe path for both the hide classes and the shared interstitial.
 */

import '../content/tiktok.css';
import '../content/platformOverlay.css';
import { initTiktokContentScript } from '../content/tiktok';

export default defineContentScript({
  matches: ['*://*.tiktok.com/*'],
  runAt: 'document_idle',
  cssInjectionMode: 'manifest',
  main(ctx) {
    const controller = initTiktokContentScript();
    // An extension reload leaves the old script alive on the page; without this it keeps
    // its observer/poll/listener running against a dead message port.
    ctx.onInvalidated(() => controller.stop());
  },
});
