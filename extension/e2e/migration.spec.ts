import { test as base, expect, type BrowserContext, type Worker } from '@playwright/test';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import type { AddressInfo } from 'node:net';
import {
  expectedRuleCount,
  extensionWorker,
  launchExtensionContext,
  seedRawSettings,
  startTestServer,
  waitForGrayscaleDomains,
  waitForRuleCount,
} from './fixtures';
import { GRAYSCALE_SCRIPT_ID } from '../src/background/grayscale';

/**
 * v2 → v3 migration on an INSTALLED profile.
 *
 * This is the only spec in the suite whose subject is people who already have Nudge. When
 * v0.2.0 reaches the Web Store, every existing install auto-updates under a profile whose
 * `storage.sync` still holds the v0.1.0 shape: a top-level `youtube` object, no `features`
 * anywhere, no `grayscale` on any rule. If the fold is wrong the visible result is not an
 * error — it is someone who had YouTube under control yesterday and does not today, with
 * nothing in the UI to explain it.
 *
 * ## How the restart is staged
 *
 * The profile directory is REAL and outlives the browser, so this runs two browsers over
 * one profile: the first plants the v2 blob and then deletes every piece of derived state
 * it produced (dynamic DNR rules, the grayscale registration), leaving a profile that
 * holds nothing but v0.1.0 settings; the second boots cold against it and has to rebuild
 * everything from those settings alone. That clearing step is what keeps the gate honest —
 * only one build of the extension exists, so without it the second browser could inherit a
 * correct rule set it never had to derive.
 *
 * `chrome.runtime.reload()` was the obvious alternative and does not work here: on an
 * extension loaded with `--load-extension` it unloads the extension permanently, and every
 * extension URL afterwards answers ERR_BLOCKED_BY_CLIENT.
 */

const ALLOWED = 'UCallowedchannel0000001';
const OTHER = 'UCotherchannel000000002';

/**
 * A realistic v0.1.0 profile: one ordinary blocked site, plus a YouTube setup using most
 * of what v2 offered — Shorts hard-blocked, gray-screen on, a channel whitelist, comments
 * hidden. Deliberately NO youtube.com site rule, because v2's YouTube features lived
 * outside the rules list entirely and most users never added one.
 */
const LEGACY_V2_PROFILE = {
  schemaVersion: 2,
  globalEnabled: true,
  onboardingComplete: true,
  rules: [
    {
      id: 'rule-legacy-1',
      domain: 'blocked.test',
      mode: 'HARD_BLOCK',
      delaySeconds: 15,
      dailyLimitMinutes: null,
      enabled: true,
      createdAt: 1,
      showTimeRemaining: false,
      schedule: null,
    },
  ],
  youtube: {
    shortsMode: 'HARD_BLOCK',
    hideShortsShelf: true,
    shortsDelaySeconds: 15,
    channelMode: 'WHITELIST',
    channels: [
      { channelId: ALLOWED, handle: null, displayName: 'Allowed Channel', addedAt: 1 },
    ],
    channelBlockMode: 'HARD_BLOCK',
    channelDelaySeconds: 15,
    grayScreen: true,
    hideHomeFeed: false,
    hideSidebarRecs: false,
    hideEndScreen: false,
    hideComments: true,
    disableAutoplay: false,
  },
  messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
  strictMode: { enabled: false, challengeLength: 24 },
  emergencyPass: { enabled: true },
  tempAllowMinutes: 10,
};

/** Strip every trace of state the running extension DERIVED from those settings. */
async function clearDerivedState(worker: Worker): Promise<void> {
  await worker.evaluate(async (scriptId) => {
    const existing = await chrome.declarativeNetRequest.getDynamicRules();
    await chrome.declarativeNetRequest.updateDynamicRules({
      removeRuleIds: existing.map((rule) => rule.id),
    });
    try {
      await chrome.scripting.unregisterContentScripts({ ids: [scriptId] });
    } catch {
      // Nothing registered — the state we wanted gone is already gone.
    }
  }, GRAYSCALE_SCRIPT_ID);
}

const test = base.extend<{ updated: { context: BrowserContext; worker: Worker } }>({
  // eslint-disable-next-line no-empty-pattern
  updated: async ({}, use) => {
    const server = await startTestServer();
    const { port } = server.address() as AddressInfo;
    const profileDir = mkdtempSync(`${tmpdir()}/nudge-v2-profile-`);

    const planting = await launchExtensionContext(port, profileDir);
    const plantingWorker = await extensionWorker(planting);
    await seedRawSettings(plantingWorker, LEGACY_V2_PROFILE);
    await clearDerivedState(plantingWorker);
    await planting.close();

    const context = await launchExtensionContext(port, profileDir);
    const worker = await extensionWorker(context);
    // Derived from the migrated settings, never counted by hand: what the migration
    // produces is the question, so a hand-written number would be asserting the answer.
    await waitForRuleCount(worker, expectedRuleCount(LEGACY_V2_PROFILE));

    await use({ context, worker });

    await context.close();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(profileDir, { recursive: true, force: true });
  },
});

test.describe('a v2 profile opened by v0.2.0', () => {
  test('a site blocked before the update is still blocked after it', async ({ updated }) => {
    // The floor. The fold rewrites the rules array in place, and a migration that MOVES
    // data has every opportunity to drop the rows it was not thinking about.
    const page = await updated.context.newPage();
    await page.goto('https://blocked.test/');

    await expect(page).toHaveURL(/blocked\.html\?target=/);
    await expect(page.getByText('Rule: blocked.test')).toBeVisible();
  });

  test('the channel whitelist survives: one channel plays, another is interrupted', async ({
    updated,
  }) => {
    // A channel list is data the user TYPED, not a stance to be re-derived. Both
    // directions are asserted because a list that survived as an EMPTY array would pass
    // "the allowed one plays" on its own — with an empty whitelist nothing is gated at all.
    const allowed = await updated.context.newPage();
    await allowed.goto(
      `https://www.youtube.com/watch?v=abc&channel=${ALLOWED}&name=Allowed%20Channel`,
    );
    await allowed.waitForTimeout(3_000);
    await expect(allowed.getByText('This channel is off your list')).toHaveCount(0);
    await allowed.close();

    const other = await updated.context.newPage();
    await other.goto(
      `https://www.youtube.com/watch?v=def&channel=${OTHER}&name=Some%20Other%20Channel`,
    );
    await expect(other.getByText('This channel is off your list')).toBeVisible({
      timeout: 15_000,
    });
  });

  test("gray-screen is still in force as the rule's own grayscale", async ({ updated }) => {
    // v2 stored `grayScreen` as a YouTube-only global; v3 stores it on the rule. The user
    // never asked for it to move, so from their side nothing may change — and the computed
    // style is the only thing that proves the re-derived registration actually loaded.
    await waitForGrayscaleDomains(updated.worker, ['youtube.com']);

    const page = await updated.context.newPage();
    await page.goto(
      `https://www.youtube.com/watch?v=def&channel=${OTHER}&name=Some%20Other%20Channel`,
    );

    await expect
      .poll(() => page.evaluate(() => getComputedStyle(document.documentElement).filter), {
        timeout: 15_000,
      })
      .toContain('grayscale');
  });

  test('the Shorts block becomes a gate on a youtube.com rule that did not exist', async ({
    updated,
  }) => {
    // v2's `shortsMode` lived outside the rules list with its own enforcement; v3 makes it
    // one of the site rule's gates. So the migration has to CREATE a youtube.com rule this
    // profile never had — and if it creates it wrong, Shorts quietly opens for someone who
    // blocked it.
    const page = await updated.context.newPage();
    await page.goto('https://www.youtube.com/shorts/xyz789');

    await expect(page).toHaveURL(/blocked\.html\?target=/);
    await expect(page.getByText('Rule: youtube.com · Shorts')).toBeVisible();
  });

  test('a hide toggle the user switched on is still hiding', async ({ updated }) => {
    // The hide toggles moved onto the rule's `features` like everything else, and they are
    // the quietest thing to lose: nothing breaks, the comments simply come back.
    const page = await updated.context.newPage();
    await page.goto(
      `https://www.youtube.com/watch?v=abc&channel=${ALLOWED}&name=Allowed%20Channel`,
    );

    await expect(page.locator('#comments #contents')).toBeHidden({ timeout: 15_000 });
  });

  test('YouTube itself is not blocked, because this user never blocked it', async ({
    updated,
  }) => {
    // The other direction, and the one a migration is likeliest to get wrong by being
    // "safe": folding YouTube's features into a rule must not invent a site block nobody
    // asked for. Over-blocking on an auto-update is how an extension gets uninstalled.
    const page = await updated.context.newPage();
    await page.goto('https://www.youtube.com/feed/subscriptions');

    await expect(page.locator('#host')).toHaveText('www.youtube.com');
  });
});
