import { baseSettings, expect, rule, test } from './fixtures';

test.describe('Escape Hatch (daily 2-minute pass)', () => {
  test('lets the user through once, then shows a spent, disabled button', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        emergencyPass: { enabled: true },
        rules: [rule('blocked.test')],
      }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    const pass = page.getByRole('button', { name: 'Use for 2 minutes · once a day' });
    await expect(pass).toBeVisible();
    await pass.click();

    // The grant takes effect immediately: the blocked site loads.
    await expect(page.locator('#host')).toHaveText('blocked.test', { timeout: 15_000 });

    // The lockout is GLOBAL and lasts 24h, so a second site is barred too — this is the
    // property that makes it an escape hatch rather than a free pass per site.
    await setSettings(
      baseSettings({
        emergencyPass: { enabled: true },
        rules: [rule('blocked.test'), rule('other.test')],
      }),
    );

    const second = await context.newPage();
    await second.goto(siteUrl('other.test'));
    await expect(second).toHaveURL(/blocked\.html\?target=/);

    // Spent state stays VISIBLE but disabled, rather than vanishing.
    const spent = second.getByRole('button', { name: /daily pass used/i });
    await expect(spent).toBeVisible();
    await expect(spent).toBeDisabled();
  });

  test('is absent entirely when the Escape Hatch is turned off', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        emergencyPass: { enabled: false },
        rules: [rule('blocked.test')],
      }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));
    await expect(page.getByRole('button', { name: /go back/i })).toBeVisible();

    await expect(page.getByRole('button', { name: /2 minutes|daily pass/i })).toHaveCount(0);
  });
});
