import { baseSettings, expect, rule, test } from './fixtures';

/** Minutes from local midnight, offset by `deltaMinutes`, wrapped into 0..1439. */
function minuteOfDay(deltaMinutes = 0): number {
  const now = new Date();
  return (((now.getHours() * 60 + now.getMinutes() + deltaMinutes) % 1440) + 1440) % 1440;
}

test.describe('Scheduled Override', () => {
  test('inside the window the scheduled mode replaces the default behavior', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        rules: [
          rule('blocked.test', {
            // Default behavior is a Delay the user can wait out...
            mode: 'DELAY',
            delaySeconds: 2,
            schedule: {
              enabled: true,
              days: null,
              // ...but right now we are inside a Hard Block window.
              startMinute: minuteOfDay(-30),
              endMinute: minuteOfDay(30),
              mode: 'HARD_BLOCK',
              delaySeconds: 15,
            },
          }),
        ],
      }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    await expect(page.getByRole('button', { name: /go back/i })).toBeVisible();
    await expect(page.getByRole('button', { name: 'I changed my mind' })).toHaveCount(0);
  });

  test('outside the window the default behavior applies', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        rules: [
          rule('blocked.test', {
            mode: 'DELAY',
            delaySeconds: 120,
            schedule: {
              enabled: true,
              days: null,
              // A window that starts in two hours and ends three hours from now.
              startMinute: minuteOfDay(120),
              endMinute: minuteOfDay(180),
              mode: 'HARD_BLOCK',
              delaySeconds: 15,
            },
          }),
        ],
      }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    await expect(page.getByRole('button', { name: 'I changed my mind' })).toBeVisible();
  });

  test('a disabled schedule is ignored even when now falls inside it', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        rules: [
          rule('blocked.test', {
            mode: 'DELAY',
            delaySeconds: 120,
            schedule: {
              enabled: false,
              days: null,
              startMinute: minuteOfDay(-30),
              endMinute: minuteOfDay(30),
              mode: 'HARD_BLOCK',
              delaySeconds: 15,
            },
          }),
        ],
      }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));

    await expect(page.getByRole('button', { name: 'I changed my mind' })).toBeVisible();
  });
});
