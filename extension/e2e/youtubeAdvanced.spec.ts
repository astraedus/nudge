import { baseSettings, expect, test } from './fixtures';
import type { Page } from '@playwright/test';
import type { ChannelEntry, NudgeSettings } from '../src/core/settingsSchema';

/**
 * Phase 4 end to end, against a REAL Chrome with the extension loaded.
 *
 * `*.youtube.com` is mapped onto the local test server (see fixtures.ts), so the actual
 * YouTube content script — the one that only matches `*://*.youtube.com/*` — runs against a
 * YouTube-shaped page carrying a real `ytInitialPlayerResponse`, with no network access.
 * That means channel detection, the gate, and the dynamically-registered grayscale CSS are
 * all exercised for real rather than simulated.
 */

const ALLOWED = 'UCallowedchannel0000001';
const OTHER = 'UCotherchannel000000002';

function channel(channelId: string, displayName: string): ChannelEntry {
  return { channelId, handle: null, displayName, addedAt: 0 };
}

function watchUrl(channelId: string, name: string): string {
  return `https://www.youtube.com/watch?v=abc&channel=${channelId}&name=${encodeURIComponent(name)}`;
}

function withYoutube(overrides: Partial<NudgeSettings['youtube']>): Partial<NudgeSettings> {
  const base = baseSettings();
  return { ...base, youtube: { ...base.youtube!, ...overrides } };
}

test.describe('YouTube channel lists', () => {
  test('a video from a channel that is not on the allow list is interrupted', async ({
    context,
    setSettings,
  }) => {
    await setSettings(
      withYoutube({
        channelMode: 'WHITELIST',
        channels: [channel(ALLOWED, 'Allowed Channel')],
        channelBlockMode: 'HARD_BLOCK',
      }),
    );

    const page = await context.newPage();
    await page.goto(watchUrl(OTHER, 'Some Other Channel'));

    await expect(page.getByText('This channel is off your list')).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByRole('button', { name: 'I changed my mind' })).toBeVisible();
  });

  test('a video from a channel on the allow list plays without interruption', async ({
    context,
    setSettings,
  }) => {
    await setSettings(
      withYoutube({
        channelMode: 'WHITELIST',
        channels: [channel(ALLOWED, 'Allowed Channel')],
        channelBlockMode: 'HARD_BLOCK',
      }),
    );

    const page = await context.newPage();
    await page.goto(watchUrl(ALLOWED, 'Allowed Channel'));
    // Give the content script the same window it would have had to interrupt.
    await page.waitForTimeout(3_000);

    await expect(page.getByText('This channel is off your list')).toHaveCount(0);
  });

  test('with channel lists off, nothing is interrupted', async ({ context, setSettings }) => {
    await setSettings(withYoutube({ channelMode: 'OFF' }));

    const page = await context.newPage();
    await page.goto(watchUrl(OTHER, 'Some Other Channel'));
    await page.waitForTimeout(3_000);

    await expect(page.getByText('This channel is off your list')).toHaveCount(0);
  });
});

test.describe('gray-screen mode', () => {
  test('turns YouTube grayscale, and only whitelisted channels come back in colour', async ({
    context,
    setSettings,
  }) => {
    await setSettings(
      withYoutube({
        grayScreen: true,
        channelMode: 'WHITELIST',
        channels: [channel(ALLOWED, 'Allowed Channel')],
        // Not blocking, so the page stays readable and we are testing colour alone.
        channelBlockMode: 'DELAY',
      }),
    );

    // A channel NOT on the list stays gray. Assert the COMPUTED style, which is what the
    // user actually sees — that also proves the dynamically-registered CSS really loaded.
    const gray = await context.newPage();
    await gray.goto(watchUrl(OTHER, 'Some Other Channel'));
    await expect
      .poll(
        () => gray.evaluate(() => getComputedStyle(document.documentElement).filter),
        { timeout: 15_000 },
      )
      .toContain('grayscale');
    await gray.close();

    // A channel ON the list is restored to full colour.
    const color = await context.newPage();
    await color.goto(watchUrl(ALLOWED, 'Allowed Channel'));
    await expect
      .poll(
        () => color.evaluate(() => getComputedStyle(document.documentElement).filter),
        { timeout: 15_000 },
      )
      .toBe('none');
  });

  test('with gray-screen off, YouTube is never greyed', async ({ context, setSettings }) => {
    await setSettings(withYoutube({ grayScreen: false, channelMode: 'OFF' }));

    const page = await context.newPage();
    await page.goto(watchUrl(OTHER, 'Some Other Channel'));
    await page.waitForTimeout(2_000);

    const filter = await page.evaluate(
      () => getComputedStyle(document.documentElement).filter,
    );
    expect(filter).toBe('none');
  });
});

test.describe('Unhook-parity hide toggles', () => {
  test('hiding comments removes them and leaves the recommendations alone', async ({
    context,
    setSettings,
  }) => {
    await setSettings(withYoutube({ hideComments: true, hideSidebarRecs: false }));

    const page = await context.newPage();
    await page.goto(watchUrl(OTHER, 'Some Other Channel'));

    await expect(page.locator('#comments #contents')).toBeHidden({ timeout: 15_000 });
    await expect(page.locator('#related')).toBeVisible();
  });

  test('hiding recommendations removes them and leaves the comments alone', async ({
    context,
    setSettings,
  }) => {
    await setSettings(withYoutube({ hideSidebarRecs: true, hideComments: false }));

    const page = await context.newPage();
    await page.goto(watchUrl(OTHER, 'Some Other Channel'));

    await expect(page.locator('#related')).toBeHidden({ timeout: 15_000 });
    await expect(page.locator('#comments #contents')).toBeVisible();
  });

  test('with every toggle off, the page is untouched', async ({ context, setSettings }) => {
    await setSettings(withYoutube({ hideComments: false, hideSidebarRecs: false }));

    const page = await context.newPage();
    await page.goto(watchUrl(OTHER, 'Some Other Channel'));
    await page.waitForTimeout(2_000);

    await expect(page.locator('#comments #contents')).toBeVisible();
    await expect(page.locator('#related')).toBeVisible();
  });
});

test.describe('SPA navigation between videos (staleness regression)', () => {
  /**
   * THE EXACT LIVE FAILURE (QA, 2026-07-26). YouTube does not rewrite its inline scripts on
   * a watch -> watch client-side navigation, so after hopping from an allowed video to a
   * disallowed one the page still carries JSON describing the ALLOWED one. Trusting it meant
   * the disallowed video played with no gate and in full colour.
   *
   * Reproduced here faithfully: load an allowed video normally, then pushState to a
   * disallowed video whose inline data is STILL pinned to the first one (`staleVideo`),
   * re-render the byline the way YouTube does, and fire `yt-navigate-finish`.
   */
  const FIRST_VIDEO = 'firstvideo0001';
  const SECOND_VIDEO = 'secondvideo002';

  async function spaNavigate(
    page: Page,
    toUrl: string,
    freshChannelId: string,
  ): Promise<void> {
    await page.evaluate(
      ([url, channelId]) => {
        history.pushState({}, '', url);
        // YouTube re-renders the byline on navigation; the inline <script> is left alone.
        const anchor = document.querySelector('#channel-name a');
        if (anchor !== null) anchor.setAttribute('href', `/channel/${channelId}`);
        document.dispatchEvent(new CustomEvent('yt-navigate-finish'));
      },
      [toUrl, freshChannelId] as const,
    );
  }

  test('gates the new video even though the inline data still describes the old one', async ({
    context,
    setSettings,
  }) => {
    await setSettings(
      withYoutube({
        channelMode: 'WHITELIST',
        channels: [channel(ALLOWED, 'Allowed Channel')],
        channelBlockMode: 'HARD_BLOCK',
      }),
    );

    const page = await context.newPage();
    // Full load of an ALLOWED video, no gate, as established elsewhere.
    await page.goto(
      `https://www.youtube.com/watch?v=${FIRST_VIDEO}&channel=${ALLOWED}&name=Allowed%20Channel`,
    );
    await page.waitForTimeout(1_500);
    await expect(page.getByText('This channel is off your list')).toHaveCount(0);

    // SPA-hop to a DISALLOWED video whose inline JSON is still pinned to the first video.
    await spaNavigate(
      page,
      `/watch?v=${SECOND_VIDEO}&channel=${ALLOWED}&name=Allowed%20Channel&staleVideo=${FIRST_VIDEO}&domChannel=${OTHER}`,
      OTHER,
    );

    await expect(page.getByText('This channel is off your list')).toBeVisible({
      timeout: 15_000,
    });
  });

  test('does not grant colour to the new video on the strength of the old one', async ({
    context,
    setSettings,
  }) => {
    await setSettings(
      withYoutube({
        grayScreen: true,
        channelMode: 'WHITELIST',
        channels: [channel(ALLOWED, 'Allowed Channel')],
        channelBlockMode: 'DELAY',
      }),
    );

    const page = await context.newPage();
    await page.goto(
      `https://www.youtube.com/watch?v=${FIRST_VIDEO}&channel=${ALLOWED}&name=Allowed%20Channel`,
    );
    await expect
      .poll(() => page.evaluate(() => getComputedStyle(document.documentElement).filter), {
        timeout: 15_000,
      })
      .toBe('none');

    await spaNavigate(
      page,
      `/watch?v=${SECOND_VIDEO}&channel=${ALLOWED}&name=Allowed%20Channel&staleVideo=${FIRST_VIDEO}&domChannel=${OTHER}`,
      OTHER,
    );

    await expect
      .poll(() => page.evaluate(() => getComputedStyle(document.documentElement).filter), {
        timeout: 15_000,
      })
      .toContain('grayscale');
  });
});
