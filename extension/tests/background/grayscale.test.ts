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
