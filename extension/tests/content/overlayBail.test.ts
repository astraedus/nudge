// @vitest-environment jsdom
/**
 * `bailAwayFromGate` — where "I changed my mind" actually sends someone.
 *
 * The bug this exists to prevent, phrased as the user sees it: under "block YouTube except
 * these channels", pressing "I changed my mind" on the channel interstitial landed them on
 * the BLOCK PAGE, because the button navigated to the site root and the site rule is still
 * redirecting exactly that. It was filed as a Known gap for v0.2.0.
 *
 * Two branches, and the test names state the outcome rather than the branch taken:
 *  - there IS somewhere to go back to  -> go back, and touch `location` not at all
 *  - there is NOT (a tab opened straight onto the gated video) -> hand the problem to the
 *    caller, which closes the tab, and STILL never navigate to a redirected URL
 */

import { describe, expect, it, vi } from 'vitest';
import { assignSafeLocation, bailAwayFromGate } from '../../src/content/overlay';

/**
 * A document stub carrying only what the function reads. Hand-built rather than driven
 * through the real jsdom `window`, because `history.length` is the whole discriminator and
 * jsdom's is shared across every test in a file — a fixture that leaks state between cases
 * would make the two branches decide themselves.
 */
function docWithHistory(length: number): {
  doc: Document;
  back: ReturnType<typeof vi.fn>;
  assign: ReturnType<typeof vi.fn>;
} {
  const back = vi.fn();
  const assign = vi.fn();
  const doc = {
    defaultView: {
      history: { length, back },
    },
    location: { assign },
  } as unknown as Document;
  return { doc, back, assign };
}

describe('bailAwayFromGate — a tab with somewhere to go back to', () => {
  it('returns the user to the page they came from, and never to the site root', () => {
    const { doc, back, assign } = docWithHistory(3);
    const onNoHistory = vi.fn();

    const result = bailAwayFromGate(doc, { onNoHistory });

    expect(result).toBe('back');
    expect(back).toHaveBeenCalledTimes(1);
    // The whole point: no navigation to a URL the site rule may still be redirecting.
    expect(assign).not.toHaveBeenCalled();
    expect(onNoHistory).not.toHaveBeenCalled();
  });

  it('treats exactly one history entry as nowhere to go back to', () => {
    // `history.length` counts the CURRENT entry, so 1 means "this is the only one" — a tab
    // opened straight onto the gated video from another tab or another app.
    const { doc, back, assign } = docWithHistory(1);
    const onNoHistory = vi.fn();

    const result = bailAwayFromGate(doc, { onNoHistory });

    expect(result).toBe('no-history');
    expect(back).not.toHaveBeenCalled();
    expect(assign).not.toHaveBeenCalled();
    expect(onNoHistory).toHaveBeenCalledTimes(1);
  });
});

describe('bailAwayFromGate — a document with no view at all', () => {
  it('falls to the caller rather than throwing, so the bail button is never a dead button', () => {
    const onNoHistory = vi.fn();
    const doc = { defaultView: null } as unknown as Document;

    expect(bailAwayFromGate(doc, { onNoHistory })).toBe('no-history');
    expect(onNoHistory).toHaveBeenCalledTimes(1);
  });
});

describe('assignSafeLocation — the last-resort fallback is still guarded', () => {
  it('navigates for http(s) and refuses anything else', () => {
    const { doc, assign } = docWithHistory(1);

    assignSafeLocation(doc, 'https://www.youtube.com/');
    expect(assign).toHaveBeenCalledWith('https://www.youtube.com/');

    assign.mockClear();
    assignSafeLocation(doc, 'javascript:alert(1)');
    assignSafeLocation(doc, 'data:text/html,<b>x</b>');
    assignSafeLocation(doc, 'not a url at all');
    expect(assign).not.toHaveBeenCalled();
  });
});
