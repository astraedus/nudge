/**
 * Facebook content script entrypoint — thin on purpose, same shape as
 * `entrypoints/instagram.content.ts`. All logic lives in `content/facebook/` so it stays
 * unit-testable against fixture DOM without a browser; this file only decides WHERE and
 * WHEN.
 */

import '../content/platformOverlay.css';
import '../content/facebook.css';
import { initFacebookContentScript } from '../content/facebook';

export default defineContentScript({
  matches: ['*://*.facebook.com/*'],
  runAt: 'document_idle',
  cssInjectionMode: 'manifest',
  main(ctx) {
    const controller = initFacebookContentScript();
    // An extension reload leaves the old script alive on the page; without this it keeps
    // its observer, poll and interval running against a dead message port.
    ctx.onInvalidated(() => controller.stop());
  },
});
