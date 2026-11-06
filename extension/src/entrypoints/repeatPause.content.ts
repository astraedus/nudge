/**
 * The repeating pause's entrypoint: thin on purpose, all logic is in `content/repeatPause.ts`.
 *
 *  - `registration: 'runtime'`, with no `matches`: `background/repeatPauseScript.ts` registers it
 *    for exactly the sites that asked, so no other page ever runs it. No `matches` here also means
 *    WXT adds nothing to `host_permissions`.
 *  - `cssInjectionMode: 'manual'`: the styles go into a shadow root, not the page, so they arrive
 *    as a string (`?inline`) rather than through the manifest's `css` array.
 *  - Injected twice into one tab (a registered script plus `executeScript`), WXT's context stops
 *    the older copy, which is what `ctx.onInvalidated` below is listening for.
 */

import overlayCss from '../content/platformOverlay.css?inline';
import { initRepeatPause } from '../content/repeatPause';
import repeatPauseCss from '../content/repeatPause.css?inline';
import type { Request } from '../core/protocol';
import { send } from '../ui/rpc';

export default defineContentScript({
  registration: 'runtime',
  runAt: 'document_idle',
  cssInjectionMode: 'manual',
  main(ctx) {
    const controller = initRepeatPause({
      doc: document,
      // Reading `isInvalid` is what notices the extension was removed or updated underneath this
      // page and fires `onInvalidated`, so a dead script does not keep drawing a stale timer.
      send: <T extends Request>(request: T) =>
        ctx.isInvalid ? Promise.reject(new Error('context invalidated')) : send(request),
      styles: `${overlayCss}\n${repeatPauseCss}`,
    });

    const onStorageChanged = (changes: Record<string, unknown>): void => {
      if ('nudge:settings' in changes) controller.refresh();
    };
    chrome.storage.onChanged.addListener(onStorageChanged);

    ctx.onInvalidated(() => {
      chrome.storage.onChanged.removeListener(onStorageChanged);
      controller.stop();
    });
  },
});
