import { baseSettings, expect, rule, test } from './fixtures';

/**
 * The extension's own pages, loaded in a real browser.
 *
 * These are cheap but catch a whole class of failure the unit tests cannot: an entrypoint
 * that never got wired into the manifest, or a page that throws on mount with the real
 * chrome APIs rather than mocked ones.
 */
test.describe('extension surfaces', () => {
  test('the dashboard is registered as the options page', async ({ serviceWorker }) => {
    // Right-click the toolbar icon -> Options has to land somewhere real.
    const manifest = await serviceWorker.evaluate(() => chrome.runtime.getManifest());

    expect(manifest.options_page).toBe('dashboard.html');
  });

  test('the popup renders today\'s screen time and a way into the dashboard', async ({
    context,
    extensionId,
    setSettings,
  }) => {
    await setSettings(baseSettings({ rules: [rule('blocked.test')] }));

    const page = await context.newPage();
    await page.goto(`chrome-extension://${extensionId}/popup.html`);

    await expect(page.getByText('Today').first()).toBeVisible();
    await expect(page.getByRole('button', { name: /open dashboard/i })).toBeVisible();
  });

  test('the dashboard renders stats and the Commitment Lock honesty note', async ({
    context,
    extensionId,
    setSettings,
  }) => {
    await setSettings(baseSettings({ rules: [rule('blocked.test')] }));

    const page = await context.newPage();
    await page.goto(`chrome-extension://${extensionId}/dashboard.html`);

    // The dashboard paints a loading state first and fills in once GET_DASHBOARD_STATE
    // resolves, which can take a moment if the service worker was asleep — wait for the
    // loaded UI rather than assuming an instant render.
    //
    // Note the role: these are <button> elements inside a role="tablist", which Chrome
    // exposes as role="tab" (not "button"). That is the semantically correct mapping, so
    // assert against it rather than the tag name.
    const settingsTab = page.getByRole('tab', { name: 'Settings', exact: true });
    await expect(settingsTab).toBeVisible({ timeout: 20_000 });

    await expect(page.getByText('Blocked').first()).toBeVisible();
    await expect(page.getByText('Walked Away').first()).toBeVisible();

    // The honesty note is a product commitment (ext-02): a Chrome extension cannot stop its
    // own removal, and we say so rather than implying an unbreakable lock.
    await settingsTab.click();
    await expect(page.getByText('Commitment Lock').first()).toBeVisible();
    await expect(page.getByText(/chrome:\/\/extensions/i).first()).toBeVisible();
  });

  test('the onboarding page renders the tagline and the incognito note', async ({
    context,
    extensionId,
  }) => {
    const page = await context.newPage();
    await page.goto(`chrome-extension://${extensionId}/onboarding.html`);

    await expect(page.getByText('Break the scroll. Take back your time.')).toBeVisible();
  });

  test('no extension page logs a console error or preload warning on load', async ({
    context,
    extensionId,
  }) => {
    const errors: string[] = [];
    const preloadWarnings: string[] = [];
    context.on('console', (message) => {
      const text = message.text();
      if (message.type() === 'error') errors.push(text);
      // Regression: Vite's modulepreload hints are useless for pages loaded off disk, and
      // Chrome logged ~6 "cross-world extension resource mismatch" / "preloaded but not
      // used" warnings per page. `build.modulePreload: false` in wxt.config.ts turns them
      // off — a clean console is part of a zero-telemetry product's trust story.
      if (message.type() === 'warning' && /preload/i.test(text)) preloadWarnings.push(text);
    });

    for (const surface of ['popup.html', 'dashboard.html', 'onboarding.html', 'blocked.html']) {
      const page = await context.newPage();
      await page.goto(`chrome-extension://${extensionId}/${surface}`);
      await page.waitForTimeout(500);
      await page.close();
    }

    expect(errors).toEqual([]);
    expect(preloadWarnings).toEqual([]);
  });
});
