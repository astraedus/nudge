import {
  baseSettings,
  expect,
  platformRule,
  readTodayCounter,
  test,
  waitForRuleCount,
} from './fixtures';
import type { Page } from '@playwright/test';

/**
 * COUNT budgets (v0.3): "20 Shorts a day, then the gate."
 *
 * A minute budget answers "how long"; a count budget answers "how many" — a different
 * question, tracked by a different piece of machinery (`background/itemCounter.ts`'s
 * day-scoped seen set) and reaching the same three consumers (DNR, the engine, the block
 * page) through the same `gateAppliesNow` predicate a minute budget does. These specs are
 * therefore the count-axis siblings of `budget.spec.ts` and `subdomainLimit.spec.ts`, not a
 * new mechanism to re-prove from scratch.
 *
 * `*.youtube.com` is mapped onto the local HTTPS fixture server (see fixtures.ts), and the
 * fixture serves the same YouTube-shaped page for any path, so `/shorts/<id>` is a real
 * navigation the real content script (and its item counter, started in
 * `entrypoints/youtube.content.ts`) runs against.
 */

/** YouTube allowed as a site, with only the Shorts count gated. */
function shortsCountGated(dailyLimitCount: number) {
  return baseSettings({
    rules: [
      platformRule(
        'youtube.com',
        { gates: { shorts: { mode: 'OFF', dailyLimitCount } } },
        { mode: 'ALLOW' },
      ),
    ],
  });
}

function shortsUrl(id: string): string {
  return `https://www.youtube.com/shorts/${id}`;
}

/** Count main-frame navigations while `run` happens. Mirrors subdomainLimit.spec.ts. */
async function countMainFrameNavigations(page: Page, run: () => Promise<void>): Promise<number> {
  let navigations = 0;
  const onNavigated = (frame: { parentFrame: () => unknown }) => {
    if (frame.parentFrame() === null) navigations += 1;
  };
  page.on('framenavigated', onNavigated);
  try {
    await run();
    // Give any loop a generous chance to show itself; a correct run adds nothing here.
    await page.waitForTimeout(3_000);
  } finally {
    page.off('framenavigated', onNavigated);
  }
  return navigations;
}

test.describe('Shorts count budget', () => {
  test('the count advances per distinct Short and a spent count blocks the next one', async ({
    context,
    serviceWorker,
    setSettings,
  }) => {
    // The headline shape: unlimited-until-spent, then a Hard Block with no way through
    // today — exactly the point of choosing a count over a minutes budget in the first
    // place. A fix that blocks from the first Short, or never blocks at all, both fail here.
    await setSettings(shortsCountGated(2));

    const page = await context.newPage();
    await page.goto(shortsUrl('vid1'));
    await expect(page.locator('#host')).toHaveText('www.youtube.com');
    await expect
      .poll(() => readTodayCounter(serviceWorker, 'youtube.com#shorts', 'items'))
      .toBe(1);

    // The SECOND distinct Short takes the stored count to 2, which is already spent — the
    // boundary is >=, exactly like the minute budget. So THIS navigation is the one that
    // crosses, and DNR recompiles a redirect for the gate where none existed before.
    await page.goto(shortsUrl('vid2'));
    await waitForRuleCount(serviceWorker, 1);
    await expect
      .poll(() => readTodayCounter(serviceWorker, 'youtube.com#shorts', 'items'))
      .toBe(2);

    // Every Shorts navigation from here on is redirected by DNR before it ever loads.
    await page.goto(shortsUrl('vid3'));
    await expect(page).toHaveURL(/blocked\.html\?target=/);
    // The count budget's whole reason to exist over a minutes budget: the page tells the
    // user back the unit they configured, not a generic "daily limit".
    await expect(page.getByText("You've watched 2 Shorts today")).toBeVisible();
  });

  test('re-visiting a Short already seen today does not advance the count', async ({
    context,
    serviceWorker,
    setSettings,
  }) => {
    // The whole reason the worker keeps a day-scoped seen set instead of a raw tally: a
    // counter that charges for a back button, or a friend re-sending a link, is impossible
    // to reason about and reads as a bug, not a feature.
    await setSettings(shortsCountGated(5));

    const page = await context.newPage();
    await page.goto(shortsUrl('vid1'));
    await expect
      .poll(() => readTodayCounter(serviceWorker, 'youtube.com#shorts', 'items'))
      .toBe(1);

    await page.goto(shortsUrl('vid2'));
    await expect
      .poll(() => readTodayCounter(serviceWorker, 'youtube.com#shorts', 'items'))
      .toBe(2);

    // Back to a Short already seen today.
    await page.goto(shortsUrl('vid1'));
    await expect(page.locator('#host')).toHaveText('www.youtube.com');
    // No deterministic "it will never happen" signal exists for a report that correctly
    // does nothing, so give a wrongly-counting report a generous window to land before
    // asserting it never did.
    await page.waitForTimeout(1_500);
    expect(await readTodayCounter(serviceWorker, 'youtube.com#shorts', 'items')).toBe(2);
  });

  test('a spent Shorts count does not block ordinary YouTube', async ({
    context,
    setSettings,
    seedUsage,
  }) => {
    // Blocking more than the user configured is how a feature stops being trusted. The
    // count lives on the SURFACE (`youtube.com#shorts`), and must not leak onto the site.
    await seedUsage('youtube.com#shorts', 0, 2);
    await setSettings(shortsCountGated(2), { counts: { 'youtube.com#shorts': 2 } });

    const page = await context.newPage();
    await page.goto('https://www.youtube.com/feed/subscriptions');

    await expect(page.locator('#host')).toBeVisible();
    await expect(page.locator('#host')).toHaveText('www.youtube.com');
  });

  test('a Shorts URL still opens normally while the count is under the limit', async ({
    context,
    serviceWorker,
    setSettings,
  }) => {
    // The other direction from the headline test, so a fix that simply blocks every Short
    // outright (ignoring the configured limit entirely) cannot pass either.
    await setSettings(shortsCountGated(5));

    const page = await context.newPage();
    await page.goto(shortsUrl('vid1'));
    await expect(page.locator('#host')).toHaveText('www.youtube.com');
    await page.goto(shortsUrl('vid2'));
    await expect(page.locator('#host')).toHaveText('www.youtube.com');
    await expect
      .poll(() => readTodayCounter(serviceWorker, 'youtube.com#shorts', 'items'))
      .toBe(2);

    // Two seen, five allowed: still well under budget.
    await page.goto(shortsUrl('vid3'));
    await expect(page.locator('#host')).toHaveText('www.youtube.com');
    await expect(page).not.toHaveURL(/blocked\.html/);
  });

  test('a spent Shorts count does not produce a redirect loop', async ({
    context,
    setSettings,
    seedUsage,
  }) => {
    // The repo has already paid for this once on the minute axis (subdomainLimit.spec.ts):
    // a block page that answers ALLOW while DNR still redirects produced 217 navigations
    // in 8 seconds and a crashed renderer. "The block page is showing" was true throughout
    // that loop; the navigation COUNT is what distinguishes the two, so that is asserted
    // here rather than only the final URL.
    await seedUsage('youtube.com#shorts', 0, 2);
    await setSettings(shortsCountGated(2), { counts: { 'youtube.com#shorts': 2 } });

    const page = await context.newPage();
    const navigations = await countMainFrameNavigations(page, async () => {
      await page.goto(shortsUrl('vid99'));
    });

    // One navigation to the item, one redirect to the block page.
    expect(navigations).toBeLessThanOrEqual(3);
    await expect(page).toHaveURL(/blocked\.html\?target=/);
    await expect(page.getByText("You've watched 2 Shorts today")).toBeVisible();
  });
});
