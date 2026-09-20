/**
 * Reddit content script entrypoint — thin on purpose, same shape as
 * `entrypoints/instagram.content.ts`. All logic lives in `content/reddit/` so it stays
 * unit-testable against fixture DOM without a browser; this file only decides WHERE and
 * WHEN.
 *
 *  - `matches`: reddit.com only (the registry's canonical domain).
 *  - `runAt: 'document_idle'`: mirrors every other platform script — the (in Reddit's
 *    case: gate-only) pass runs once the page has rendered.
 *  - `cssInjectionMode: 'manifest'`: the shared interstitial stylesheet lands in the
 *    content script's manifest `css` array, which Chrome injects before any DOM is
 *    constructed. No `reddit.css` import — Reddit has zero hide surfaces
 *    (`content/reddit/selectors.ts`), so there is nothing for a per-platform stylesheet to
 *    style.
 */

import '../content/platformOverlay.css';
import { initRedditContentScript } from '../content/reddit';

export default defineContentScript({
  matches: ['*://*.reddit.com/*'],
  runAt: 'document_idle',
  cssInjectionMode: 'manifest',
  main(ctx) {
    const controller = initRedditContentScript();
    // An extension reload leaves the old script alive on the page; without this it keeps
    // its observer, poll and interval running against a dead message port.
    ctx.onInvalidated(() => controller.stop());
  },
});
