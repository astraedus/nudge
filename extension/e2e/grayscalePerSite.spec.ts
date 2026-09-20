import {
  baseSettings,
  expect,
  rule,
  test,
  waitForGrayscaleDomains,
} from './fixtures';

/**
 * Grayscale as a PER-SITE toggle.
 *
 * Everything here asserts the COMPUTED style rather than a class name or a stored flag. A
 * class only proves our JavaScript ran; `getComputedStyle(...).filter` proves the
 * dynamically-registered stylesheet actually loaded and reached the pixels — which is the
 * entire mechanism, since the reason this feature is a `chrome.scripting` registration and
 * not injected CSS is that injected CSS arrives after the page has already painted in
 * colour.
 *
 * The pair in every test is the point, not padding: the registration derives ONE `matches`
 * list from the SET of rules asking for grayscale, so the interesting failures are all
 * set-shaped (every site gray, no site gray, the wrong site gray). A single gray domain
 * asserted alone cannot distinguish any of them.
 */

test.describe('per-site grayscale', () => {
  test('greys the site that asked for it and leaves the other in colour', async ({
    context,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        rules: [
          rule('gray.test', { mode: 'ALLOW', grayscale: true }),
          rule('plain.test', { mode: 'ALLOW', grayscale: false }),
        ],
      }),
    );
    // Both rules are Allow with no limit, so they compile ZERO dynamic rules and
    // `setSettings` returns the instant it checks. Registered CSS is injected before first
    // paint or never, so the wait has to happen before the navigation, not after.
    await waitForGrayscaleDomains(serviceWorker, ['gray.test']);

    const gray = await context.newPage();
    await gray.goto(siteUrl('gray.test'));
    await expect(gray.locator('#host')).toHaveText('gray.test');
    expect(
      await gray.evaluate(() => getComputedStyle(document.documentElement).filter),
    ).toContain('grayscale');
    await gray.close();

    const plain = await context.newPage();
    await plain.goto(siteUrl('plain.test'));
    await expect(plain.locator('#host')).toHaveText('plain.test');
    expect(
      await plain.evaluate(() => getComputedStyle(document.documentElement).filter),
    ).toBe('none');
  });

  test('turning grayscale off restores the site to colour', async ({
    context,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    // The registration persists across sessions by design, so the failure this guards is a
    // site staying gray forever for a user who switched it off — worse than never having
    // greyed it, because nothing in the UI explains it.
    await setSettings(
      baseSettings({ rules: [rule('gray.test', { mode: 'ALLOW', grayscale: true })] }),
    );
    await waitForGrayscaleDomains(serviceWorker, ['gray.test']);

    const before = await context.newPage();
    await before.goto(siteUrl('gray.test'));
    expect(
      await before.evaluate(() => getComputedStyle(document.documentElement).filter),
    ).toContain('grayscale');
    await before.close();

    await setSettings(
      baseSettings({ rules: [rule('gray.test', { mode: 'ALLOW', grayscale: false })] }),
    );
    await waitForGrayscaleDomains(serviceWorker, []);

    const after = await context.newPage();
    await after.goto(siteUrl('gray.test'));
    await expect(after.locator('#host')).toHaveText('gray.test');
    expect(
      await after.evaluate(() => getComputedStyle(document.documentElement).filter),
    ).toBe('none');
  });

  test('greys a site that is only limited, not blocked', async ({
    context,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    // Grayscale on an Allow rule is the combination the feature exists for — "let me on
    // the site, just make it boring". It is also the one where nothing else about the rule
    // reaches the network layer, so a grayscale registration derived from "rules that
    // block" instead of "rules that ask for it" would silently do nothing here.
    await setSettings(
      baseSettings({
        rules: [rule('gray.test', { mode: 'ALLOW', dailyLimitMinutes: 30, grayscale: true })],
      }),
    );
    await waitForGrayscaleDomains(serviceWorker, ['gray.test']);

    const page = await context.newPage();
    await page.goto(siteUrl('gray.test'));

    await expect(page.locator('#host')).toHaveText('gray.test');
    expect(
      await page.evaluate(() => getComputedStyle(document.documentElement).filter),
    ).toContain('grayscale');
  });
});
