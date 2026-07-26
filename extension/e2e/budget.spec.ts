import {
  baseSettings,
  expect,
  rule,
  seedTrackerInterval,
  sendFromExtensionPage,
  test,
} from './fixtures';
import type { NudgeSettings } from '../src/core/settingsSchema';

test.describe('Daily Time Limit', () => {
  test('an exhausted budget forces a Hard Block on the next navigation', async ({
    context,
    setSettings,
    seedUsage,
    siteUrl,
  }) => {
    const settings = baseSettings({
      rules: [rule('blocked.test', { mode: 'DELAY', delaySeconds: 2, dailyLimitMinutes: 1 })],
    });
    await setSettings(settings);
    // Exactly at the limit is over it (the engine uses >=).
    await seedUsage('blocked.test', 60);

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    await expect(page).toHaveURL(/blocked\.html\?target=/);
    // Escalated to Hard Block despite the rule being a Delay: no countdown, no way through.
    await expect(page.getByRole('button', { name: /go back/i })).toBeVisible();
    await expect(page.getByText('Daily limit reached')).toBeVisible();
    // The rule name carries the Android-parity suffix.
    await expect(page.getByText('Rule: blocked.test (limit reached)')).toBeVisible();
  });

  test('an under-budget site still gets its configured Delay, not a Hard Block', async ({
    context,
    setSettings,
    seedUsage,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        rules: [rule('blocked.test', { mode: 'DELAY', delaySeconds: 60, dailyLimitMinutes: 5 })],
      }),
    );
    await seedUsage('blocked.test', 10);

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    await expect(page.getByRole('button', { name: 'I changed my mind' })).toBeVisible();
    await expect(page.getByText(/limit reached/i)).toHaveCount(0);
  });

  test('crossing the limit mid-browsing redirects the already-open tab', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    seedUsage,
    siteUrl,
  }) => {
    // The whole point of this feature: DNR only sees REQUESTS, so a page already open when
    // the budget runs out would stay readable until the next navigation. The worker has to
    // actively push open tabs to the block page.
    const settings = baseSettings({
      tempAllowMinutes: 30,
      rules: [rule('blocked.test', { mode: 'DELAY', delaySeconds: 1, dailyLimitMinutes: 1 })],
    });
    await setSettings(settings);

    // Get onto the site legitimately by completing the pause, so a real tab is sitting open.
    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));
    await expect(page.locator('#host')).toHaveText('blocked.test', { timeout: 20_000 });

    // Put usage just under the 1-minute limit, with a 10s interval in flight.
    await seedUsage('blocked.test', 55);
    await seedTrackerInterval(serviceWorker, 'blocked.test', 10_000);

    // Any settings save runs an accounting step, which is the real production trigger for
    // the budget check (SAVE_SETTINGS -> onActivityEvent -> accountAndSwitch).
    await sendFromExtensionPage(context, extensionId, {
      type: 'SAVE_SETTINGS',
      settings: settings as NudgeSettings,
    });

    // The open tab is pushed to the block page without the user navigating.
    await expect(page).toHaveURL(/blocked\.html\?target=/, { timeout: 15_000 });
    await expect(page.getByText('Daily limit reached')).toBeVisible();
  });
});
