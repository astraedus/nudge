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

  test('the Breathing circle grows smoothly every frame while its count ticks 4, 3, 2, 1', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    // The 0.3.1 pacer stepped the circle once per 100ms React tick through a 100ms LINEAR
    // transition, so in a real browser it moved in a visible staircase. Sample the circle
    // on every animation frame for most of an inhale: it must move on nearly every frame,
    // only ever grow, and never take a jump a smooth breath could not.
    await setSettings(
      baseSettings({ rules: [rule('blocked.test', { mode: 'BREATHING', delaySeconds: 16 })] }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));
    await expect(page).toHaveURL(/blocked\.html\?target=/);
    const count = page.getByTestId('breath-count');
    await expect(count).toHaveText(/^[1-4]$/);

    const samples = await page.evaluate(
      () =>
        new Promise<number[]>((resolve) => {
          const circle = document.querySelector<HTMLElement>('[data-testid="breath-circle"]');
          const out: number[] = [];
          const start = performance.now();
          const read = () => {
            const match = /scale\(([\d.]+)\)/.exec(circle?.style.transform ?? '');
            out.push(match ? Number(match[1]) : NaN);
            if (performance.now() - start < 1500) requestAnimationFrame(read);
            else resolve(out);
          };
          requestAnimationFrame(read);
        }),
    );

    expect(samples.length).toBeGreaterThan(20);
    expect(samples.every((s) => Number.isFinite(s))).toBe(true);
    const distinct = new Set(samples.map((s) => s.toFixed(4))).size;
    expect(distinct).toBeGreaterThan(samples.length * 0.6);
    for (let i = 1; i < samples.length; i += 1) {
      const step = (samples[i] ?? 0) - (samples[i - 1] ?? 0);
      // A 4s sine inhale from 0.6 to 1.0 never moves more than ~0.016 per 60fps frame;
      // 0.05 leaves room for a dropped frame or two on a loaded CI runner.
      expect(Math.abs(step)).toBeLessThan(0.05);
    }

    await expect(page.getByText('Breathe out')).toBeVisible({ timeout: 6000 });
    await expect(count).toHaveText('4');
    await expect(count).toHaveText('3', { timeout: 2000 });
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
