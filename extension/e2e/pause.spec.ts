import {
  baseSettings,
  expect,
  readTodayCounter,
  rule,
  test,
  waitForNoTempAllows,
} from './fixtures';

test.describe('Delay and Breathing pauses', () => {
  test('a Delay countdown completes, grants temporary access, and expires back to blocked', async ({
    context,
    setSettings,
    siteUrl,
    serviceWorker,
  }) => {
    // The expiry half of this test waits on the REAL chrome.alarms expiry rather than
    // simulating it, because the alarm re-arm path is exactly what has historically broken
    // in MV3. tempAllowMinutes is clamped to a 1-minute minimum, hence the long timeout.
    test.setTimeout(180_000);

    await setSettings(
      baseSettings({
        tempAllowMinutes: 1,
        rules: [rule('blocked.test', { mode: 'DELAY', delaySeconds: 2 })],
      }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));
    await expect(page).toHaveURL(/blocked\.html\?target=/);

    // The countdown runs, then the page sends itself on to the original target.
    await expect(page.locator('#host')).toHaveText('blocked.test', { timeout: 20_000 });

    // Temporary access is per-origin, so a fresh navigation goes straight through.
    await page.goto(siteUrl('blocked.test', '/second-visit'));
    await expect(page.locator('#host')).toHaveText('blocked.test');
    await expect(page.locator('#path')).toHaveText('/second-visit');

    // Wait for the grant to lapse, then confirm the next navigation re-blocks.
    await waitForNoTempAllows(serviceWorker);
    await page.goto(siteUrl('blocked.test', '/third-visit'));
    await expect(page).toHaveURL(/blocked\.html\?target=/);
  });

  test('a Breathing pause cycles and then lets the user through', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        tempAllowMinutes: 5,
        rules: [rule('blocked.test', { mode: 'BREATHING', delaySeconds: 2 })],
      }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    await expect(page).toHaveURL(/blocked\.html\?target=/);
    await expect(page.getByText(/breathe (in|out)/i)).toBeVisible();

    await expect(page.locator('#host')).toHaveText('blocked.test', { timeout: 20_000 });
  });

  test('"I changed my mind" bails out and records a Walked Away', async ({
    context,
    setSettings,
    siteUrl,
    serviceWorker,
  }) => {
    await setSettings(
      baseSettings({ rules: [rule('blocked.test', { mode: 'DELAY', delaySeconds: 120 })] }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));
    await page.getByRole('button', { name: 'I changed my mind' }).click();

    // The user is taken off the block page, and never reaches the blocked site.
    await expect(page).not.toHaveURL(/blocked\.html/);
    expect(page.url()).not.toContain('blocked.test');

    const walkedAway = await readTodayCounter(serviceWorker, 'blocked.test', 'walkedAway');
    expect(walkedAway).toBeGreaterThan(0);
  });

  test('showing the block page records a Blocked event', async ({
    context,
    setSettings,
    siteUrl,
    serviceWorker,
  }) => {
    await setSettings(baseSettings({ rules: [rule('blocked.test')] }));

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));
    await expect(page.getByRole('button', { name: /go back/i })).toBeVisible();

    const blocked = await readTodayCounter(serviceWorker, 'blocked.test', 'blocked');
    expect(blocked).toBeGreaterThan(0);
  });
});
