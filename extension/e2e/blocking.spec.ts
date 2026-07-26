import { baseSettings, expect, rule, test } from './fixtures';

test.describe('site blocking', () => {
  test('a site with no rule loads normally', async ({ context, setSettings, siteUrl }) => {
    await setSettings(baseSettings({ rules: [rule('blocked.test')] }));

    const page = await context.newPage();
    await page.goto(siteUrl('allowed.test'));

    await expect(page.locator('#host')).toHaveText('allowed.test');
  });

  test('a Hard Block rule redirects the navigation to the block page', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(baseSettings({ rules: [rule('blocked.test')] }));

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    // The DNR redirect must carry the original URL through to the block page.
    await expect(page).toHaveURL(/blocked\.html\?target=http:\/\/blocked\.test\//);
    await expect(page.getByRole('button', { name: /go back/i })).toBeVisible();
  });

  test('subdomains of a blocked domain are blocked by the same rule', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(baseSettings({ rules: [rule('blocked.test')] }));

    const page = await context.newPage();
    await page.goto(siteUrl('www.blocked.test'));

    await expect(page).toHaveURL(/blocked\.html\?target=/);
  });

  test('the master toggle off lets a blocked site through', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(baseSettings({ globalEnabled: false, rules: [rule('blocked.test')] }));

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    await expect(page.locator('#host')).toHaveText('blocked.test');
  });
});
