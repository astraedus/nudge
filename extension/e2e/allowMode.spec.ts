import {
  baseSettings,
  expect,
  expectedRuleCount,
  rule,
  seedTrackerInterval,
  sendFromExtensionPage,
  test,
  waitForRuleCount,
} from './fixtures';
import type { NudgeSettings } from '../src/core/settingsSchema';

/**
 * The limit-only rule — Allow mode plus a daily budget, StayFree's model and the headline
 * new shape in v0.2.
 *
 * It is the one behaviour where "a rule exists" and "a rule is enforced" are different
 * facts, and four layers have to agree on which is true right now (`core/applies.ts`). The
 * failure it guards against is silent in BOTH directions and invisible to unit tests: a
 * wrong `usageMs` anywhere in the chain either blocks a site the user only asked to limit,
 * or lets the limit never bite at all. Both look like "nothing happened" from the outside,
 * which is why this is asserted against what the user sees on screen — the site's own page
 * versus the block page — and never against a rule count.
 */

const LIMIT_MINUTES = 5;
const LIMIT_SECONDS = LIMIT_MINUTES * 60;

function limitOnly(domain: string): Partial<NudgeSettings> {
  return baseSettings({
    rules: [rule(domain, { mode: 'ALLOW', dailyLimitMinutes: LIMIT_MINUTES })],
  });
}

test.describe('Allow mode with a daily limit', () => {
  test('the site opens normally until the budget is spent, then the block page takes over', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    seedUsage,
    siteUrl,
  }) => {
    // One test covering both sides on purpose: "the site opened" on its own would also be
    // true of settings that never arrived, and "the site is blocked" on its own would also
    // be true of a rule that blocks unconditionally. Only the transition, in one profile,
    // pins the actual behaviour.
    const settings = limitOnly('blocked.test');
    await seedUsage('blocked.test', 30);
    await setSettings(settings, { ms: { 'blocked.test': 30_000 } });

    const before = await context.newPage();
    await before.goto(siteUrl('blocked.test'));
    await expect(before.locator('#host')).toHaveText('blocked.test');
    await before.close();

    // Spend the budget. Exactly at the limit is over it (the engine uses >=).
    //
    // Re-saving the same settings is what makes the worker look again: `SAVE_SETTINGS`
    // recompiles unconditionally, whereas writing an unchanged value to storage fires no
    // `onChanged` at all. It is also the production path — the rule set is a function of
    // (settings, usage, now), so the usage half has to be able to move it on its own.
    await seedUsage('blocked.test', LIMIT_SECONDS);
    await sendFromExtensionPage(context, extensionId, {
      type: 'SAVE_SETTINGS',
      settings: settings as NudgeSettings,
    });
    await waitForRuleCount(
      serviceWorker,
      expectedRuleCount(settings, { ms: { 'blocked.test': LIMIT_SECONDS * 1_000 } }),
    );

    const after = await context.newPage();
    await after.goto(siteUrl('blocked.test'));

    await expect(after).toHaveURL(/blocked\.html\?target=/);
    await expect(after.getByText('Daily limit reached')).toBeVisible();
    // A spent budget cannot be waited out before midnight, so it is a Hard Block: no
    // countdown, no way through.
    await expect(after.getByRole('button', { name: /go back/i })).toBeVisible();
    await expect(after.getByRole('button', { name: 'I changed my mind' })).toHaveCount(0);
  });

  test('one spent budget does not close a second limited site that still has time', async ({
    context,
    setSettings,
    seedUsage,
    siteUrl,
  }) => {
    // Two limit-only rules in one profile, one spent and one not. A budget read that is
    // not keyed per domain — the whole day's usage, the first rule's usage, the wrong
    // argument position — passes the previous test and fails this one.
    await seedUsage('blocked.test', LIMIT_SECONDS);
    await seedUsage('allowed.test', 20);
    await setSettings(
      baseSettings({
        rules: [
          rule('blocked.test', { mode: 'ALLOW', dailyLimitMinutes: LIMIT_MINUTES }),
          rule('allowed.test', { mode: 'ALLOW', dailyLimitMinutes: LIMIT_MINUTES }),
        ],
      }),
      { ms: { 'blocked.test': LIMIT_SECONDS * 1_000, 'allowed.test': 20_000 } },
    );

    const open = await context.newPage();
    await open.goto(siteUrl('allowed.test'));
    await expect(open.locator('#host')).toHaveText('allowed.test');
    await open.close();

    const spent = await context.newPage();
    await spent.goto(siteUrl('blocked.test'));
    await expect(spent).toHaveURL(/blocked\.html\?target=/);
  });

  test('spending the budget while reading pushes the open tab to the block page', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    seedUsage,
    siteUrl,
  }) => {
    // The Allow-mode version of the mid-browsing flip. It matters more here than for a
    // blocking rule: an Allow site is one the user is legitimately sitting on, with no
    // pause to complete first, so the tab in front of them is the ONLY thing that can
    // tell them the budget ran out.
    const settings = baseSettings({
      rules: [rule('blocked.test', { mode: 'ALLOW', dailyLimitMinutes: 1 })],
    });
    await setSettings(settings);

    const page = await context.newPage();
    await page.goto(siteUrl('blocked.test'));
    await expect(page.locator('#host')).toHaveText('blocked.test');

    // Just under the 1-minute limit, with a 10s interval in flight: the next accounting
    // step crosses it.
    await seedUsage('blocked.test', 55);
    await seedTrackerInterval(serviceWorker, 'blocked.test', 10_000);

    await sendFromExtensionPage(context, extensionId, {
      type: 'SAVE_SETTINGS',
      settings: settings as NudgeSettings,
    });

    await expect(page).toHaveURL(/blocked\.html\?target=/, { timeout: 15_000 });
    await expect(page.getByText('Daily limit reached')).toBeVisible();
  });
});
