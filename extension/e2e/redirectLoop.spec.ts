import { baseSettings, expect, rule, test } from './fixtures';

/**
 * Regression: Hard Block + Daily Time Limit used to be an INFINITE REDIRECT LOOP.
 *
 * The engine had no branch for "Hard Block, has a limit, still under it", so it fell through
 * to ALLOW. DNR redirects the domain regardless of mode, so the sequence was:
 * navigate -> DNR redirect -> block page asks the engine -> ALLOW -> page sends the user to
 * the target -> DNR redirects again -> forever, hammering the service worker.
 *
 * Live-Chrome QA found this on 3 domains; 400 unit tests and 24 e2e specs did not, because
 * two unit tests had encoded the buggy ALLOW as the expected result.
 */
test.describe('Hard Block + Daily Time Limit (redirect-loop regression)', () => {
  test('renders the block page once and does not bounce', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        rules: [rule('blocked.test', { mode: 'HARD_BLOCK', dailyLimitMinutes: 30 })],
      }),
    );

    const page = await context.newPage();

    // Count every committed main-frame navigation. A loop shows up as an unbounded climb.
    let navigations = 0;
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) navigations += 1;
    });

    await page.goto(siteUrl('blocked.test'));
    await expect(page).toHaveURL(/blocked\.html\?target=/);
    await expect(page.getByRole('button', { name: /go back/i })).toBeVisible();

    const afterLoad = navigations;

    // Give any loop a generous window to run away.
    await page.waitForTimeout(3_000);

    // Settled: the block page stayed put rather than bouncing back to the site.
    expect(navigations).toBe(afterLoad);
    await expect(page).toHaveURL(/blocked\.html\?target=/);
    expect(page.url()).not.toMatch(/^https?:\/\/blocked\.test/);
  });

  test('under budget it is a plain Hard Block, not a "limit reached" block', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        rules: [rule('blocked.test', { mode: 'HARD_BLOCK', dailyLimitMinutes: 30 })],
      }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    await expect(page.getByRole('button', { name: /go back/i })).toBeVisible();
    // The budget is untouched, so nothing should claim it was exhausted.
    await expect(page.getByText('Daily limit reached')).toHaveCount(0);
  });

  test('the same rule over budget still reports limit reached', async ({
    context,
    setSettings,
    seedUsage,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        rules: [rule('blocked.test', { mode: 'HARD_BLOCK', dailyLimitMinutes: 30 })],
      }),
    );
    await seedUsage('blocked.test', 30 * 60);

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    await expect(page.getByText('Daily limit reached')).toBeVisible();
  });
});
