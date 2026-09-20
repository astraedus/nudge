import { beforeEach, describe, expect, it } from 'vitest';

import {
  applyGrayscale,
  grayscaleMatches,
  GRAYSCALE_SCRIPT_ID,
} from '../../src/background/grayscale';
import { settings, siteRule } from '../helpers/rules';
import { installScripting, resetBrowser, type ScriptingState } from './fakeApis';

/**
 * Grayscale is registered as a dynamic content script because that is the only mechanism
 * that is BOTH runtime-gateable and applied before first paint — see the table in
 * extension/CLAUDE.md. These cases are about what a user would SEE (which sites come up
 * grey, and that a site they un-greyed stops being grey), plus the one invariant the
 * mechanism itself has: `persistAcrossSessions` is true, so a registration outlives the
 * setting that asked for it unless every wake re-derives it.
 */

let scripting: ScriptingState;

function registered(): chrome.scripting.RegisteredContentScript | undefined {
  return scripting.scripts.find((script) => script.id === GRAYSCALE_SCRIPT_ID);
}

beforeEach(() => {
  resetBrowser();
  scripting = installScripting();
});

describe('grayscaleMatches', () => {
  it('lists exactly the sites whose rule asks for it', () => {
    const config = settings({
      rules: [
        siteRule({ id: 'a', domain: 'reddit.com', grayscale: true }),
        siteRule({ id: 'b', domain: 'news.example.com', grayscale: false }),
      ],
    });
    expect(grayscaleMatches(config)).toEqual(['*://*.reddit.com/*']);
  });

  it('greys a platform’s other domains too, so an x.com rule covers twitter.com', () => {
    const config = settings({
      rules: [siteRule({ id: 'a', domain: 'x.com', grayscale: true })],
    });
    expect(grayscaleMatches(config)).toEqual(['*://*.twitter.com/*', '*://*.x.com/*']);
  });

  it('ignores a switched-off rule and everything while Nudge itself is off', () => {
    const disabledRule = settings({
      rules: [siteRule({ domain: 'reddit.com', grayscale: true, enabled: false })],
    });
    const nudgeOff = settings({
      globalEnabled: false,
      rules: [siteRule({ domain: 'reddit.com', grayscale: true })],
    });
    expect(grayscaleMatches(disabledRule)).toEqual([]);
    expect(grayscaleMatches(nudgeOff)).toEqual([]);
  });
});

describe('applyGrayscale', () => {
  const grey = (domain: string) =>
    settings({ rules: [siteRule({ domain, grayscale: true })] });

  it('registers the stylesheet before first paint for the greyed sites', async () => {
    await applyGrayscale(grey('reddit.com'));

    const script = registered();
    expect(script?.matches).toEqual(['*://*.reddit.com/*']);
    expect(script?.css).toEqual(['grayscale.css']);
    // The entire reason this is a dynamic registration rather than injected CSS.
    expect(script?.runAt).toBe('document_start');
  });

  it('registers nothing at all when no site asks for it', async () => {
    await applyGrayscale(settings({ rules: [siteRule({ grayscale: false })] }));
    expect(registered()).toBeUndefined();
  });

  it('un-greys a site the moment its toggle goes off', async () => {
    await applyGrayscale(grey('reddit.com'));
    await applyGrayscale(settings({ rules: [siteRule({ domain: 'reddit.com' })] }));
    expect(registered()).toBeUndefined();
  });

  it('keeps one registration when called repeatedly, and leaves it untouched', async () => {
    await applyGrayscale(grey('reddit.com'));
    scripting.calls.length = 0;
    await applyGrayscale(grey('reddit.com'));
    await applyGrayscale(grey('reddit.com'));

    expect(scripting.scripts).toHaveLength(1);
    // Re-registering on every wake would leave a window in which a page loading right then
    // paints in full colour — the one failure this whole mechanism exists to avoid.
    expect(scripting.calls).toEqual(['get', 'get']);
  });

  it('follows the set of greyed sites as it changes, without duplicating the script', async () => {
    await applyGrayscale(grey('reddit.com'));
    await applyGrayscale(
      settings({
        rules: [
          siteRule({ id: 'a', domain: 'reddit.com', grayscale: true }),
          siteRule({ id: 'b', domain: 'news.ycombinator.com', grayscale: true }),
        ],
      }),
    );

    expect(scripting.scripts).toHaveLength(1);
    expect(registered()?.matches).toEqual([
      '*://*.news.ycombinator.com/*',
      '*://*.reddit.com/*',
    ]);
  });

  it('still fixes a stale match list when the registry query is unsupported', async () => {
    await applyGrayscale(grey('reddit.com'));
    // Some Chrome builds throw on a filtered query for an id that exists; falling back to
    // "not registered" and re-registering would be a duplicate-id error, leaving the user
    // greyed on a site they removed.
    scripting.throwOnQuery = true;

    await applyGrayscale(grey('news.ycombinator.com'));

    expect(scripting.scripts).toHaveLength(1);
    expect(registered()?.matches).toEqual(['*://*.news.ycombinator.com/*']);
  });

  it('never throws out of the blocking path when registration fails', async () => {
    Object.defineProperty(chrome, 'scripting', {
      configurable: true,
      writable: true,
      value: {
        getRegisteredContentScripts: async () => {
          throw new Error('boom');
        },
        registerContentScripts: async () => {
          throw new Error('boom');
        },
        updateContentScripts: async () => {
          throw new Error('boom');
        },
        unregisterContentScripts: async () => {
          throw new Error('boom');
        },
      },
    });

    await expect(applyGrayscale(grey('reddit.com'))).resolves.toBeUndefined();
  });
});

describe('switching grayscale off', () => {
  /**
   * Live QA 2026-09-20, reproduced twice: turning grayscale off logged
   * "[nudge] gray-screen registration failed Error: Script with ID 'nudge-grayscale' does
   * not exist or is not fully registered". Saving settings applies grayscale directly AND
   * fires storage.onChanged, which applies it again; both read the registration, both see
   * it, and the loser unregisters something already gone. Nothing was broken, but the only
   * thing a red error tells a user is that turning a cosmetic feature off does not work.
   */
  const grey = settings({
    rules: [siteRule({ domain: 'youtube.com', grayscale: true })],
  });
  const plain = settings({
    rules: [siteRule({ domain: 'youtube.com', grayscale: false })],
  });

  it('leaves nothing registered and says nothing to the console', async () => {
    const errors: unknown[] = [];
    const original = console.error;
    console.error = (...args: unknown[]) => errors.push(args);
    try {
      await applyGrayscale(grey);
      expect(registered()).toBeDefined();

      // Both callers fire, as they do in production.
      await Promise.all([applyGrayscale(plain), applyGrayscale(plain)]);
    } finally {
      console.error = original;
    }

    expect(registered()).toBeUndefined();
    expect(errors).toEqual([]);
  });

  it('is still gone after a third redundant pass', async () => {
    await applyGrayscale(grey);
    await applyGrayscale(plain);
    await applyGrayscale(plain);

    expect(registered()).toBeUndefined();
  });
});
