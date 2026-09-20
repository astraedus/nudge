/**
 * LinkedIn content script entrypoint — thin on purpose, same shape as
 * `entrypoints/instagram.content.ts`. All logic lives in `content/linkedin/` so it stays
 * unit-testable against fixture DOM without a browser; this file only decides WHERE and
 * WHEN.
 *
 *  - `matches`: linkedin.com only (the registry's canonical domain).
 *  - `runAt: 'document_idle'`: mirrors every other platform script — the hiding pass runs
 *    once LinkedIn has rendered its feed; the no-flash guarantee comes from the CSS below,
 *    not from running JS earlier.
 *  - `cssInjectionMode: 'manifest'`: both the shared interstitial stylesheet and this
 *    platform's own hide-toggle stylesheet land in the content script's manifest `css`
 *    array, which Chrome injects before any DOM is constructed.
 */

import '../content/platformOverlay.css';
import '../content/linkedin.css';
import { initLinkedinContentScript } from '../content/linkedin';

export default defineContentScript({
  matches: ['*://*.linkedin.com/*'],
  runAt: 'document_idle',
  cssInjectionMode: 'manifest',
  main(ctx) {
    const controller = initLinkedinContentScript();
    // An extension reload leaves the old script alive on the page; without this it keeps
    // its observer, poll and interval running against a dead message port.
    ctx.onInvalidated(() => controller.stop());
  },
});
