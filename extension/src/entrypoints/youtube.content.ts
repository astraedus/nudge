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
import { startItemViewCounter } from '../content/itemCounter';
import { initYoutubeContentScript } from '../content/youtube';

export default defineContentScript({
  matches: ['*://*.youtube.com/*'],
  runAt: 'document_idle',
  cssInjectionMode: 'manifest',
  main(ctx) {
    /*
     * COUNT budgets ("20 Shorts a day", v0.3) are started HERE rather than inside
     * `content/youtube.ts`, and that is deliberate rather than convenient.
     *
     * Every other platform gets counting from the shared controller in
     * `content/platformGate.ts`. YouTube does not route through it: its script carries its
     * own navigation layer, entangled with the channel-freshness settle window that exists
     * to stop a documented P0 (a false interstitial on a channel the user explicitly
     * allowed, for 3-5s after a watch -> watch hop) whose regression is LIVE-ONLY, with no
     * fixture that reproduces it. extension/CLAUDE.md records that as a Known gap and says
     * plainly that touching it is its own isolated change, gated on
     * `e2e/youtubeAdvanced.spec.ts`. Counting is a pure observation with no verdict of its
     * own, so it needs none of that machinery: it observes navigation itself and reports.
     * `yt-navigate-finish` is passed so a Shorts swipe is noticed on YouTube's own event
     * as well as by the href poll.
     */
    // Started BEFORE the main controller, deliberately: counting is independent of
    // everything the controller does, and if the controller ever throws on an unfamiliar
    // page shape, the budget the user set must still be measured rather than silently
    // stopping for as long as that page is open.
    const itemCounter = startItemViewCounter({
      platform: 'youtube',
      navEvent: 'yt-navigate-finish',
    });
    const controller = initYoutubeContentScript();
    // An extension reload leaves the old script alive on the page; without this it keeps
    // its observer and interval running against a dead message port.
    ctx.onInvalidated(() => {
      controller.stop();
      itemCounter.stop();
    });
  },
});
