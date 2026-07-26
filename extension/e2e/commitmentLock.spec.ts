import { baseSettings, expect, rule, sendFromExtensionPage, test } from './fixtures';
import type { NudgeSettings } from '../src/core/settingsSchema';
import type { SaveResult } from '../src/core/protocol';
import { normalize } from '../src/core/strictMode';

test.describe('Commitment Lock (Strict Mode)', () => {
  test('gates a weakening change behind a typed challenge, then accepts the answer', async ({
    context,
    extensionId,
    setSettings,
  }) => {
    const locked = baseSettings({
      strictMode: { enabled: true, challengeLength: 12 },
      rules: [rule('blocked.test')],
    });
    await setSettings(locked);

    // Removing a rule weakens protection.
    const weakened = { ...locked, rules: [] } as NudgeSettings;
    const refused = await sendFromExtensionPage<SaveResult>(context, extensionId, {
      type: 'SAVE_SETTINGS',
      settings: weakened,
    });

    expect(refused.ok).toBe(false);
    expect(refused.challenge).toBeTruthy();
    expect(normalize(refused.challenge!)).toHaveLength(12);

    // A wrong answer is refused, and the SAME challenge is offered again so the user can
    // keep typing the code in front of them.
    const wrong = await sendFromExtensionPage<SaveResult>(context, extensionId, {
      type: 'SAVE_SETTINGS',
      settings: weakened,
      challengeResponse: 'definitely-not-it',
    });
    expect(wrong.ok).toBe(false);
    expect(wrong.challenge).toBe(refused.challenge);

    // The correct answer goes through.
    const accepted = await sendFromExtensionPage<SaveResult>(context, extensionId, {
      type: 'SAVE_SETTINGS',
      settings: weakened,
      challengeResponse: refused.challenge!,
    });
    expect(accepted.ok).toBe(true);
  });

  test('never gates a strengthening change', async ({ context, extensionId, setSettings }) => {
    const locked = baseSettings({
      strictMode: { enabled: true, challengeLength: 12 },
      rules: [rule('blocked.test')],
    });
    await setSettings(locked);

    // Adding a rule strengthens protection — no challenge.
    const strengthened = {
      ...locked,
      rules: [rule('blocked.test'), rule('other.test')],
    } as NudgeSettings;

    const result = await sendFromExtensionPage<SaveResult>(context, extensionId, {
      type: 'SAVE_SETTINGS',
      settings: strengthened,
    });

    expect(result.ok).toBe(true);
    expect(result.challenge).toBeUndefined();
  });

  test('hides the Escape Hatch entirely while the lock is on', async ({
    context,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        strictMode: { enabled: true, challengeLength: 12 },
        emergencyPass: { enabled: true },
        rules: [rule('blocked.test')],
      }),
    );

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));
    await expect(page.getByRole('button', { name: /go back/i })).toBeVisible();

    // A commitment lock must not have a one-tap bypass.
    await expect(page.getByRole('button', { name: /2 minutes|daily pass/i })).toHaveCount(0);
  });
});
