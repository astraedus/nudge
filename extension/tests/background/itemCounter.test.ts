import { beforeEach, describe, expect, it, vi } from 'vitest';

import { handleItemViewed, resolveItem } from '../../src/background/itemCounter';
import { loadDay, saveSettings } from '../../src/background/storage';
import { localDayKey } from '../../src/core/scheduleEvaluator';
import { surfaceKey } from '../../src/core/surfaceKeys';
import type { NudgeSettings } from '../../src/core/settingsSchema';
import { featuresWith, settings, siteRule } from '../helpers/rules';
import { installAttention, installDnr, resetBrowser, type AttentionState } from './fakeApis';

/**
 * The COUNT axis ("20 Shorts a day, then the gate"): resolving a reported URL to a surface
 * and an item id, de-duplicating against the day's seen set, and firing the crossing.
 *
 * Asserted the same way the rest of `tests/background/` is — the outcome a real user would
 * see (a tab redirected, a stat incremented) rather than which internal branch ran, and with
 * explicit call counts wherever "did this fire exactly once" is the actual claim.
 */

const NOW = new Date(2026, 8, 20, 12, 0, 0);

let attention: AttentionState;

async function seed(config: NudgeSettings): Promise<void> {
  await saveSettings(config);
}

beforeEach(() => {
  resetBrowser();
  installDnr();
  attention = installAttention();
});

describe('resolveItem', () => {
  it("keys the bucket off the matching RULE's domain, not the raw host", () => {
    // The exact bug class already paid for on the minute axis (live QA 2026-09-20): a
    // tracker that filled `en.wikipedia.org` while every budget check read `wikipedia.org`
    // meant the limit could never fire. `gaming.youtube.com` is not a KNOWN_SUBDOMAIN
    // (`www`/`m`/`mobile`/`l`/`lm`), so `extractDomain` alone would leave it untouched —
    // this only resolves because `resolveItem` goes through the rule, not the bare host.
    const config = settings({ rules: [siteRule({ domain: 'youtube.com' })] });
    const result = resolveItem(config, 'https://gaming.youtube.com/shorts/abc');
    expect(result).toEqual({ domain: 'youtube.com', gateId: 'shorts', itemId: 'abc' });
  });

  it('returns null for a URL that is not one item of any gate\'s stream', () => {
    const config = settings({ rules: [siteRule({ domain: 'youtube.com' })] });
    expect(resolveItem(config, 'https://www.youtube.com/watch?v=abc')).toBeNull();
  });

  it('returns null for a domain no known platform answers on', () => {
    const config = settings({ rules: [] });
    expect(resolveItem(config, 'https://example.com/whatever')).toBeNull();
  });

  it('returns null rather than throwing on a malformed URL', () => {
    const config = settings({ rules: [] });
    expect(resolveItem(config, 'not a url at all')).toBeNull();
  });
});

describe('handleItemViewed', () => {
  it('does not count a page that is not an item, and writes nothing to storage', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'youtube.com' })] }));

    const result = await handleItemViewed('https://www.youtube.com/watch?v=abc', NOW);

    expect(result).toEqual({ ok: true, counted: false });
    expect(await loadDay(localDayKey(NOW))).toEqual({});
  });

  it('does not count an unknown platform', async () => {
    await seed(settings({ rules: [] }));

    const result = await handleItemViewed('https://example.com/whatever', NOW);

    expect(result).toEqual({ ok: true, counted: false });
  });

  it('answers rather than hanging on a garbage URL', async () => {
    // A handler that never responds hangs the caller's sendMessage promise forever — a
    // documented repo lesson (messagesRouter.ts's own docstring). `handleItemViewed` must
    // resolve to a defined answer for input that resolves to no item at all.
    const result = await handleItemViewed('not a url, not even close', NOW);
    expect(result).toEqual({ ok: true, counted: false });
  });

  it('counts an item once no matter how many times the same id is reported the same day', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'youtube.com' })] }));

    const first = await handleItemViewed('https://www.youtube.com/shorts/abc', NOW);
    const second = await handleItemViewed('https://www.youtube.com/shorts/abc', NOW);

    expect(first.counted).toBe(true);
    expect(second.counted).toBe(false);
    const day = await loadDay(localDayKey(NOW));
    expect(day[surfaceKey('youtube.com', 'shorts')]?.items).toBe(1);
  });

  it('counts two DIFFERENT items as two', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'youtube.com' })] }));

    await handleItemViewed('https://www.youtube.com/shorts/abc', NOW);
    await handleItemViewed('https://www.youtube.com/shorts/def', NOW);

    const day = await loadDay(localDayKey(NOW));
    expect(day[surfaceKey('youtube.com', 'shorts')]?.items).toBe(2);
  });

  it('counts the same item again once the day rolls over', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'youtube.com' })] }));
    await handleItemViewed('https://www.youtube.com/shorts/abc', NOW);

    const tomorrow = new Date(NOW.getTime() + 24 * 60 * 60 * 1000);
    const result = await handleItemViewed('https://www.youtube.com/shorts/abc', tomorrow);

    expect(result.counted).toBe(true);
    const day = await loadDay(localDayKey(tomorrow));
    expect(day[surfaceKey('youtube.com', 'shorts')]?.items).toBe(1);
  });

  it('counts for a known platform even with no rule and no budget configured', async () => {
    // The dashboard's "of which Shorts" line must not start at zero the moment a budget is
    // switched on — the surface bucket fills unconditionally, exactly as the tracker fills
    // the minute axis unconditionally. Only the ENFORCEMENT below needs a configured limit.
    await seed(settings({ rules: [] }));

    const result = await handleItemViewed('https://www.youtube.com/shorts/abc', NOW);

    expect(result.counted).toBe(true);
    const day = await loadDay(localDayKey(NOW));
    expect(day[surfaceKey('youtube.com', 'shorts')]?.items).toBe(1);
  });

  it('counts nothing while Nudge itself is switched off', async () => {
    await seed({
      ...settings({ rules: [siteRule({ domain: 'youtube.com' })] }),
      globalEnabled: false,
    });

    const result = await handleItemViewed('https://www.youtube.com/shorts/abc', NOW);

    expect(result).toEqual({ ok: true, counted: false });
    expect(await loadDay(localDayKey(NOW))).toEqual({});
  });

  it('lands the increment under the domain#gate SURFACE key, never the plain domain', async () => {
    await seed(settings({ rules: [siteRule({ domain: 'youtube.com' })] }));

    await handleItemViewed('https://www.youtube.com/shorts/abc', NOW);

    const day = await loadDay(localDayKey(NOW));
    expect(day[surfaceKey('youtube.com', 'shorts')]?.items).toBe(1);
    expect(day['youtube.com']).toBeUndefined();
  });
});

describe('crossing a count budget', () => {
  const twoShortsADay = settings({
    rules: [
      siteRule({
        domain: 'youtube.com',
        mode: 'ALLOW',
        features: featuresWith('youtube', {
          gates: { shorts: { mode: 'OFF', dailyLimitCount: 2 } },
        }),
      }),
    ],
  });

  beforeEach(() => {
    attention.tabs = [
      { id: 1, url: 'https://www.youtube.com/shorts/abc' },
      { id: 2, url: 'https://www.youtube.com/watch?v=xyz' },
    ];
  });

  it('recompiles and redirects exactly once on the crossing, and never again after', async () => {
    await seed(twoShortsADay);
    // Spied at the chrome.* boundary rather than the module boundary, so this pins the
    // actual effect (a rule-set recompile, a tab actually navigated) rather than an
    // internal call graph that could be refactored without the user-visible behaviour
    // changing at all.
    const recompiles = vi.spyOn(chrome.declarativeNetRequest, 'updateDynamicRules');
    const tabUpdates = vi.spyOn(chrome.tabs, 'update');

    const first = await handleItemViewed('https://www.youtube.com/shorts/one', NOW);
    expect(first.counted).toBe(true);
    expect(recompiles).not.toHaveBeenCalled();
    expect(tabUpdates).not.toHaveBeenCalled();

    const second = await handleItemViewed('https://www.youtube.com/shorts/two', NOW);
    expect(second.counted).toBe(true);
    expect(recompiles).toHaveBeenCalledTimes(1);
    // Only the tab actually on the Shorts SURFACE gets pushed to the block page — the
    // ordinary video tab (id 2) sits on the same domain but is not on the gate's surface
    // and must be left alone, exactly as a spent Shorts budget must not close YouTube.
    expect(tabUpdates).toHaveBeenCalledTimes(1);
    expect(tabUpdates).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ url: expect.stringContaining('blocked.html?target=') }),
    );

    // A third distinct item still counts (it is a new id today) but the gate is already
    // in force, so there is no new transition to fire on — recompiling or redirecting
    // again here would be re-blocking a user already looking at the block page.
    const third = await handleItemViewed('https://www.youtube.com/shorts/three', NOW);
    expect(third.counted).toBe(true);
    expect(recompiles).toHaveBeenCalledTimes(1);
    expect(tabUpdates).toHaveBeenCalledTimes(1);
  });

  it('does nothing while the count is still under budget', async () => {
    await seed(twoShortsADay);

    await handleItemViewed('https://www.youtube.com/shorts/one', NOW);

    expect(attention.navigatedTo).toEqual({});
  });
});
