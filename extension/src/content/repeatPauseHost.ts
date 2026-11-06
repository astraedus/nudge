/**
 * The one element the repeating pause adds to a page: a shadow host holding the pill and the
 * overlay.
 *
 * The platform scripts can rely on manifest CSS and pages we have read. This script runs on any
 * site a user wrote a rule for, so three things have to hold on pages nobody here has seen:
 *
 *  - **The site's CSS cannot bleed in.** Hence the shadow root, and the host's own box set
 *    inline with `!important`, which beats anything a site aims at it (`* { display: none }`).
 *  - **A strict page CSP cannot strip our styles.** A constructable sheet in `adoptedStyleSheets`
 *    is not subject to `style-src`, and inline values written through CSSOM are not either. The
 *    `<style>` fallback is for an environment with no constructable sheets. It is not unsafe
 *    today, because Chromium lets an isolated-world script's `<style>` past the page's CSP (seen
 *    with the sheet disabled under the CSP e2e), but that exemption is the browser's, not ours.
 *  - **Fullscreen cannot hide the pill.** A `manual` popover sits in the top layer, above the
 *    fullscreen element, as long as it is re-shown after that element went fullscreen.
 *    `showPopover` failing costs only that: the inline `display: block` keeps the host painted.
 *
 * Open shadow root: nothing in here is a secret, and Playwright locators pierce only open ones.
 */

export const REPEAT_PAUSE_HOST_TAG = 'nudge-repeat-pause-host';

const HOST_STYLES: readonly (readonly [string, string])[] = [
  // Must stay first: a shorthand written after the others would reset them.
  ['all', 'initial'],
  ['display', 'block'],
  ['position', 'fixed'],
  ['inset', '0'],
  ['width', '100%'],
  ['height', '100%'],
  ['margin', '0'],
  ['padding', '0'],
  ['border', '0'],
  ['background', 'transparent'],
  ['overflow', 'visible'],
  // Inherited by the pill. The overlay turns it back on for itself (`repeatPause.css`), so the
  // host can cover the whole viewport without ever taking a click from the site.
  ['pointer-events', 'none'],
  ['z-index', '2147483647'],
];

export interface RepeatPauseHost {
  /** Where the pill and the overlay are appended. */
  root: ShadowRoot;
  /** Put the host back if the site removed it. */
  ensureAttached: () => void;
  /** Move to the top of the top layer, above anything that went fullscreen since. */
  raise: () => void;
  remove: () => void;
}

function adoptStyles(doc: Document, root: ShadowRoot, css: string): void {
  try {
    const view = doc.defaultView as (Window & typeof globalThis) | null;
    if (!view) throw new Error('no view');
    const sheet = new view.CSSStyleSheet();
    sheet.replaceSync(css);
    root.adoptedStyleSheets = [sheet];
  } catch {
    const style = doc.createElement('style');
    style.textContent = css;
    root.append(style);
  }
}

/** A re-injected script finds the previous copy's host here. There is only ever one. */
export function removeStaleHosts(doc: Document): void {
  for (const stale of Array.from(doc.querySelectorAll(REPEAT_PAUSE_HOST_TAG))) stale.remove();
}

export function createRepeatPauseHost(doc: Document, css: string): RepeatPauseHost {
  const element = doc.createElement(REPEAT_PAUSE_HOST_TAG);
  for (const [name, value] of HOST_STYLES) element.style.setProperty(name, value, 'important');
  element.setAttribute('popover', 'manual');

  const root = element.attachShadow({ mode: 'open' });
  adoptStyles(doc, root, css);

  const raise = (): void => {
    // Hide first: showing a popover that is already open throws and does not re-order it.
    try {
      element.hidePopover();
    } catch {
      // Not open, or no popover API.
    }
    try {
      element.showPopover();
    } catch {
      // No popover API, or the host is detached. Still painted, just not in the top layer.
    }
  };

  const ensureAttached = (): void => {
    if (element.isConnected) return;
    doc.documentElement?.append(element);
    raise();
  };

  ensureAttached();

  return { root, ensureAttached, raise, remove: () => element.remove() };
}
