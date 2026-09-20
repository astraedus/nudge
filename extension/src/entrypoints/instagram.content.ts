/**
 * Instagram content script entrypoint — thin on purpose, same shape as
 * `entrypoints/youtube.content.ts`. All logic lives in `content/instagram/` so it stays
 * unit-testable against fixture DOM without a browser; this file only decides WHERE and
 * WHEN.
 *
 *  - `matches`: instagram.com only (the registry's canonical domain).
 *  - `runAt: 'document_idle'`: mirrors the YouTube script — the hiding pass runs once
 *    Instagram has rendered its feed; the no-flash guarantee comes from the CSS below, not
 *    from running JS earlier.
 *  - `cssInjectionMode: 'manifest'`: both the shared interstitial stylesheet and this
 *    platform's own hide-toggle stylesheet land in the content script's manifest `css`
 *    array, which Chrome injects before any DOM is constructed.
 */

import '../content/platformOverlay.css';
import '../content/instagram.css';
import { initInstagramContentScript } from '../content/instagram';

export default defineContentScript({
  matches: ['*://*.instagram.com/*'],
  runAt: 'document_idle',
  cssInjectionMode: 'manifest',
  main(ctx) {
    const controller = initInstagramContentScript();
    // An extension reload leaves the old script alive on the page; without this it keeps
    // its observer, poll and interval running against a dead message port.
    ctx.onInvalidated(() => controller.stop());
  },
});
