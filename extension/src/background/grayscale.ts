/**
 * Gray-screen mode's registration side.
 *
 * The problem: static manifest CSS cannot be turned off, and CSS injected from JS cannot be
 * applied before the page paints (it has to await a storage read first), so a JS-gated
 * grayscale flashes the page in full colour on every load — exactly the dopamine hit the
 * feature exists to remove.
 *
 * The solution: register `grayscale.css` as a DYNAMIC content script while the feature is
 * on and unregister it when off. Chrome injects registered CSS "before any DOM is
 * constructed or displayed" (chrome.scripting docs), so there is no colour frame at all.
 *
 * Registration is derived from settings on every worker wake rather than toggled
 * incrementally: `persistAcrossSessions` defaults to true, so a stale registration would
 * otherwise outlive the setting that asked for it and grey YouTube out for a user who had
 * turned the feature off.
 */

export const GRAYSCALE_SCRIPT_ID = 'nudge-grayscale';
const GRAYSCALE_CSS = 'grayscale.css';
const YOUTUBE_MATCHES = ['*://*.youtube.com/*'];

async function isRegistered(): Promise<boolean> {
  try {
    const existing = await chrome.scripting.getRegisteredContentScripts({
      ids: [GRAYSCALE_SCRIPT_ID],
    });
    return existing.length > 0;
  } catch {
    // A filter for an unknown id throws on some Chrome versions rather than returning [].
    return false;
  }
}

/**
 * Make the registration match `enabled`. Idempotent, so it is safe to call on every worker
 * wake and on every settings change.
 */
export async function applyGrayscale(enabled: boolean): Promise<void> {
  const registered = await isRegistered();

  try {
    if (enabled && !registered) {
      await chrome.scripting.registerContentScripts([
        {
          id: GRAYSCALE_SCRIPT_ID,
          matches: YOUTUBE_MATCHES,
          css: [GRAYSCALE_CSS],
          // The whole point: before the first paint.
          runAt: 'document_start',
          allFrames: false,
          persistAcrossSessions: true,
        },
      ]);
      return;
    }
    if (!enabled && registered) {
      await chrome.scripting.unregisterContentScripts({ ids: [GRAYSCALE_SCRIPT_ID] });
    }
  } catch (error) {
    // Gray-screen is a cosmetic intervention; a registration failure must never take down
    // the blocking path that shares this worker.
    console.error('[nudge] gray-screen registration failed', error);
  }
}
