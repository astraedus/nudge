/**
 * YouTube content script entrypoint — thin on purpose.
 *
 * All logic lives in src/content/youtube.ts + src/content/selectors.ts so it can be unit
 * tested against fixture DOM without a browser. This file only decides WHERE and WHEN.
 *
 *  - `matches`: every YouTube host (www., m., music. — the mobile layout ships the same
 *    `ytd-*` custom elements).
 *  - `runAt: 'document_idle'`: the hiding pass runs once YouTube has rendered its feed
 *    (ext-03 §1 — every shipped OSS Shorts hider does this). The no-flash guarantee comes
 *    from the CSS below, not from running JS earlier.
 *  - `cssInjectionMode: 'manifest'`: WXT puts the imported CSS into the content script's
 *    manifest `css` array, which Chrome injects before any DOM is constructed. That is
 *    the FOUC-safe path (ext-03 §4/§6); JS-injected styles are not.
 */

import '../content/youtube.css';
import { initYoutubeContentScript } from '../content/youtube';

export default defineContentScript({
  matches: ['*://*.youtube.com/*'],
  runAt: 'document_idle',
  cssInjectionMode: 'manifest',
  main(ctx) {
    const controller = initYoutubeContentScript();
    // An extension reload leaves the old script alive on the page; without this it keeps
    // its observer and interval running against a dead message port.
    ctx.onInvalidated(() => controller.stop());
  },
});
