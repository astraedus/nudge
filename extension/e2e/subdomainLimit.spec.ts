import { baseSettings, expect, rule, test } from './fixtures';
import type { NudgeSettings } from '../src/core/settingsSchema';
import type { Page } from '@playwright/test';

/**
 * A spent daily limit, reached on a SUBDOMAIN of the ruled site.
 *
 * QA R2 (2026-09-20) found an infinite redirect loop here: DNR matches every subdomain, so
 * `en.wikipedia.test` was redirected to the block page, but the block page resolved usage
 * under the HOST while the tracker filled the RULE's bucket. It therefore saw an unspent
 * budget, answered ALLOW, and sent the user back to the site — where DNR redirected them
 * again. 217 main-frame navigations in 8 seconds, and a crashed renderer.
 *
 * So these assert the NAVIGATION COUNT, not just the page that eventually renders. "The
 * block page is showing" was true throughout the loop; what was false is that it got there
 * once and stopped.
 */

const LIMIT_MINUTES = 1;
const RULE_HOST = 'wikipedia.test';
const SUBDOMAIN_PAGE = 'https://en.wikipedia.test/wiki/Colour';

function spentLimit(mode: 'ALLOW' | 'DELAY'): {
  settings: Partial<NudgeSettings>;
  usage: { ms: Record<string, number> };
} {
  return {
    settings: baseSettings({
      rules: [
        rule(RULE_HOST, {
          mode,
          delaySeconds: 15,
          dailyLimitMinutes: LIMIT_MINUTES,
        }),
      ],
    }),
    // The tracker attributes a page on ANY subdomain to the rule's own bucket, so that is
    // the key a spent budget lives under.
    usage: { ms: { [RULE_HOST]: LIMIT_MINUTES * 60_000 } },
  };
}

/** Count main-frame navigations while `run` happens. */
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

test.describe('a spent daily limit on a subdomain of the ruled site', () => {
  test('lands on the block page ONCE instead of bouncing forever', async ({
    context,
    setSettings,
    seedUsage,
  }) => {
    const { settings, usage } = spentLimit('ALLOW');
    await seedUsage(RULE_HOST, LIMIT_MINUTES * 60);
    await setSettings(settings, usage);

    const page = await context.newPage();
    const navigations = await countMainFrameNavigations(page, async () => {
      await page.goto(SUBDOMAIN_PAGE);
    });

    // One navigation to the site, one redirect to the block page. A loop produced 217.
    expect(navigations).toBeLessThanOrEqual(3);
    await expect(page).toHaveURL(/blocked\.html\?target=/);
    await expect(page.getByText('Daily limit reached')).toBeVisible();
  });

  test('does not offer a fresh Delay countdown once the budget is gone', async ({
    context,
    setSettings,
    seedUsage,
  }) => {
    // The blast radius QA measured: a Delay rule answered DELAY with a full minute
    // remaining on the subdomain, so the limit was simply a no-op there.
    const { settings, usage } = spentLimit('DELAY');
    await seedUsage(RULE_HOST, LIMIT_MINUTES * 60);
    await setSettings(settings, usage);

    const page = await context.newPage();
    await page.goto(SUBDOMAIN_PAGE);

    await expect(page.getByText('Daily limit reached')).toBeVisible();
    // A Delay renders a countdown and an "I changed my mind"; a spent budget must render
    // neither, because there is nothing left to wait out today.
    await expect(page.getByRole('button', { name: 'I changed my mind' })).toHaveCount(0);
  });

  test('treats the bare domain and the subdomain the same way', async ({
    context,
    setSettings,
    seedUsage,
  }) => {
    const { settings, usage } = spentLimit('ALLOW');
    await seedUsage(RULE_HOST, LIMIT_MINUTES * 60);
    await setSettings(settings, usage);

    const bare = await context.newPage();
    await bare.goto(`https://${RULE_HOST}/wiki/Colour`);
    await expect(bare.getByText('Daily limit reached')).toBeVisible();

    const sub = await context.newPage();
    await sub.goto(SUBDOMAIN_PAGE);
    await expect(sub.getByText('Daily limit reached')).toBeVisible();
  });

  test('still opens the subdomain normally while the budget is intact', async ({
    context,
    setSettings,
  }) => {
    // The other direction, so a fix that simply blocks everything cannot pass.
    await setSettings(
      baseSettings({
        rules: [rule(RULE_HOST, { mode: 'ALLOW', dailyLimitMinutes: 30 })],
      }),
      { ms: { [RULE_HOST]: 60_000 } },
    );

    const page = await context.newPage();
    await page.goto(SUBDOMAIN_PAGE);

    await expect(page.locator('#host')).toHaveText('en.wikipedia.test');
  });
});
