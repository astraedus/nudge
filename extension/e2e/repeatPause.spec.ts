import type { Page, Worker } from '@playwright/test';
import {
  RICH_PAGE,
  STRICT_CSP_PAGE,
  baseSettings,
  expect,
  rule,
  sendFromExtensionPage,
  test,
} from './fixtures';
import { REPEAT_PAUSE_SCRIPT_FILE, REPEAT_PAUSE_SCRIPT_ID } from '../src/background/repeatPauseScript';
import { REPEAT_PAUSE_HOST_TAG } from '../src/content/repeatPauseHost';

const DOMAIN = 'repeat.test';
const TEMP_ALLOW_KEY = 'nudge:tempAllow';

const OVERLAY = '#nudge-repeat-pause';
const PILL = '.nudge-repeat-pill';

const TEN_MINUTES = /^Next pause in (10:00|9:\d\d)$/;
const FIVE_MINUTES = /^Next pause in (5:00|4:\d\d)$/;

/**
 * Bring the grant's end close instead of waiting out real minutes. The page only re-reads the
 * grant every 30 seconds, so a visibility event makes it ask now.
 */
async function grantEndsIn(worker: Worker, page: Page, ms: number): Promise<void> {
  await worker.evaluate(
    async ([key, domain, until]) => {
      const stored = await chrome.storage.session.get(key);
      const map = (stored[key] ?? {}) as Record<string, number>;
      map[domain] = until;
      await chrome.storage.session.set({ [key]: map });
    },
    [TEMP_ALLOW_KEY, DOMAIN, Date.now() + ms] as const,
  );
  await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
}

async function grantMsLeft(worker: Worker): Promise<number> {
  const until = await worker.evaluate(
    async ([key, domain]) => {
      const stored = await chrome.storage.session.get(key);
      return ((stored[key] ?? {}) as Record<string, number>)[domain] ?? 0;
    },
    [TEMP_ALLOW_KEY, DOMAIN] as const,
  );
  return until - Date.now();
}

function registeredMatches(worker: Worker): Promise<string[]> {
  return worker.evaluate(
    (id) =>
      chrome.scripting
        .getRegisteredContentScripts({ ids: [id] })
        .then((scripts) => scripts[0]?.matches ?? []),
    REPEAT_PAUSE_SCRIPT_ID,
  );
}

/** Through the worker's own message and lock, then until Chrome holds the registration. */
async function setRepeat(
  fixtures: {
    context: Parameters<typeof sendFromExtensionPage>[0];
    extensionId: string;
    serviceWorker: Worker;
  },
  minutes: number | null,
): Promise<void> {
  const result = await sendFromExtensionPage<{ ok: boolean }>(fixtures.context, fixtures.extensionId, {
    type: 'SET_REPEAT_PAUSE',
    target: `https://${DOMAIN}/`,
    minutes,
  });
  expect(result.ok).toBe(true);
  await expect
    .poll(() => registeredMatches(fixtures.serviceWorker))
    .toEqual(minutes === null ? [] : [`*://*.${DOMAIN}/*`, `*://${DOMAIN}/*`]);
}

async function throughThePause(page: Page, url: string): Promise<void> {
  await page.goto(url);
  await expect(page).toHaveURL(/blocked\.html\?target=/);
  await expect(page.locator('#host')).toHaveText(DOMAIN, { timeout: 20_000 });
}

test.describe('Repeating pause', () => {
  test('the pause comes back over the same document and renews the timer', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        tempAllowMinutes: 1,
        rules: [rule(DOMAIN, { mode: 'DELAY', delaySeconds: 2 })],
      }),
    );
    await setRepeat({ context, extensionId, serviceWorker }, 10);

    const page = await context.newPage();
    await throughThePause(page, siteUrl(DOMAIN, RICH_PAGE));

    // The grant is the repeat's length, not tempAllowMinutes, so the timer tells the truth.
    await expect(page.locator(PILL)).toHaveText(TEN_MINUTES);
    expect(await grantMsLeft(serviceWorker)).toBeGreaterThan(9 * 60_000);

    await page.fill('#note', 'half a comment');
    await page.evaluate(() => {
      (window as unknown as { sameDocument: string }).sameDocument = 'yes';
    });

    await grantEndsIn(serviceWorker, page, 1500);

    const overlay = page.locator(OVERLAY);
    await expect(overlay).toBeVisible();
    await expect(page.locator(PILL)).toHaveCount(0);
    await expect(page.locator('#note')).toHaveValue('half a comment');

    // The countdown ends, a new grant comes back, and the page never went anywhere.
    await expect(overlay).toHaveCount(0, { timeout: 15_000 });
    await expect(page.locator(PILL)).toHaveText(TEN_MINUTES);
    expect(await grantMsLeft(serviceWorker)).toBeGreaterThan(9 * 60_000);
    await expect(page.locator('#note')).toHaveValue('half a comment');
    expect(
      await page.evaluate(() => (window as unknown as { sameDocument: string }).sameDocument),
    ).toBe('yes');
    expect(page.url()).toBe(siteUrl(DOMAIN, RICH_PAGE));
  });

  test('the pill never takes a click from the site', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({ rules: [rule(DOMAIN, { mode: 'DELAY', delaySeconds: 2 })] }),
    );
    await setRepeat({ context, extensionId, serviceWorker }, 10);

    const page = await context.newPage();
    await throughThePause(page, siteUrl(DOMAIN, RICH_PAGE));

    const pill = page.locator(PILL);
    await expect(pill).toBeVisible();
    const box = await pill.boundingBox();
    expect(box).not.toBeNull();

    // The button sits in the same corner as the pill. A real click must reach it.
    await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height / 2);
    await expect(page.locator('#corner')).toHaveAttribute('data-clicked', 'yes');
  });

  for (const mode of ['DELAY', 'BREATHING'] as const) {
    test(`a strict CSP cannot strip the styles of the ${mode} overlay`, async ({
      context,
      extensionId,
      serviceWorker,
      setSettings,
      siteUrl,
    }) => {
      await setSettings(
        baseSettings({ rules: [rule(DOMAIN, { mode, delaySeconds: 8 })] }),
      );
      await setRepeat({ context, extensionId, serviceWorker }, 10);

      const page = await context.newPage();
      await throughThePause(page, siteUrl(DOMAIN, STRICT_CSP_PAGE));

      // Positive control: this page really does refuse an injected <style>.
      const refused = await page.evaluate(() => {
        const style = document.createElement('style');
        style.textContent = '#host { color: rgb(1, 2, 3); }';
        document.head.append(style);
        return getComputedStyle(document.getElementById('host')!).color !== 'rgb(1, 2, 3)';
      });
      expect(refused).toBe(true);

      const pillStyle = await page.locator(PILL).evaluate((el) => {
        const css = getComputedStyle(el);
        const box = el.getBoundingClientRect();
        return {
          position: css.position,
          pointerEvents: css.pointerEvents,
          inViewport: box.right <= innerWidth && box.bottom <= innerHeight && box.width > 0,
        };
      });
      expect(pillStyle).toEqual({ position: 'fixed', pointerEvents: 'none', inViewport: true });

      await grantEndsIn(serviceWorker, page, 500);

      const overlay = page.locator(OVERLAY);
      await expect(overlay).toBeVisible();

      // Attached is not enough: an unstyled overlay is a block of text at the end of the page.
      const geometry = await overlay.evaluate((el) => {
        const css = getComputedStyle(el);
        const box = el.getBoundingClientRect();
        return {
          position: css.position,
          background: css.backgroundColor,
          covers:
            box.left === 0 &&
            box.top === 0 &&
            box.width === innerWidth &&
            box.height === innerHeight,
        };
      });
      expect(geometry).toEqual({
        position: 'fixed',
        background: 'rgba(9, 20, 18, 0.92)',
        covers: true,
      });

      const cardBackground = await page
        .locator('.nudge-overlay__card')
        .evaluate((el) => getComputedStyle(el).backgroundColor);
      expect(cardBackground).toBe('rgb(18, 32, 29)');

      // It covers the page and still takes its own clicks.
      const top = await page.evaluate(
        ([x, y]) => document.elementFromPoint(x!, y!)?.tagName.toLowerCase(),
        [10, 10],
      );
      expect(top).toBe(REPEAT_PAUSE_HOST_TAG);
      await page.getByRole('button', { name: 'I changed my mind' }).click();
      await expect(page).toHaveURL(new RegExp(`${extensionId}/dashboard\\.html`));
    });
  }

  test('media stops while the overlay is up and carries on afterwards', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({ rules: [rule(DOMAIN, { mode: 'DELAY', delaySeconds: 3 })] }),
    );
    await setRepeat({ context, extensionId, serviceWorker }, 10);

    const page = await context.newPage();
    await throughThePause(page, siteUrl(DOMAIN, RICH_PAGE));

    const media = page.locator('#media');
    const paused = () => media.evaluate((el: HTMLAudioElement) => el.paused);
    await media.evaluate((el: HTMLAudioElement) => el.play());
    await expect.poll(paused).toBe(false);

    await grantEndsIn(serviceWorker, page, 500);

    const overlay = page.locator(OVERLAY);
    await expect(overlay).toBeVisible();
    await expect.poll(paused).toBe(true);

    await expect(overlay).toHaveCount(0, { timeout: 15_000 });
    await expect.poll(paused).toBe(false);
  });

  test('a pause that comes due leaves fullscreen, and the pill stays above it until then', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({ rules: [rule(DOMAIN, { mode: 'DELAY', delaySeconds: 2 })] }),
    );
    await setRepeat({ context, extensionId, serviceWorker }, 10);

    const page = await context.newPage();
    await throughThePause(page, siteUrl(DOMAIN, RICH_PAGE));
    await expect(page.locator(PILL)).toBeVisible();

    const fullscreen = () => page.evaluate(() => document.fullscreenElement !== null);
    await page.evaluate(() => document.getElementById('note')!.parentElement!.requestFullscreen());
    await expect.poll(fullscreen).toBe(true);

    const inTopLayer = () =>
      page
        .locator(REPEAT_PAUSE_HOST_TAG)
        .evaluate((host) => host.matches(':popover-open'));
    await expect.poll(inTopLayer).toBe(true);

    await grantEndsIn(serviceWorker, page, 500);

    await expect(page.locator(OVERLAY)).toBeVisible();
    await expect.poll(fullscreen).toBe(false);
  });

  test('the block page chooser saves, and the next grant uses it', async ({
    context,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        // A grant of 1 minute without the chooser, so 5 can only have come from it.
        tempAllowMinutes: 1,
        rules: [rule(DOMAIN, { mode: 'DELAY', delaySeconds: 5 })],
      }),
    );
    expect(await registeredMatches(serviceWorker)).toEqual([]);

    const page = await context.newPage();
    await page.goto(siteUrl(DOMAIN, RICH_PAGE));
    await expect(page).toHaveURL(/blocked\.html\?target=/);
    await page.getByLabel('Pause again every').selectOption({ label: '5 min' });

    await expect(page.locator('#host')).toHaveText(DOMAIN, { timeout: 20_000 });

    await expect(page.locator(PILL)).toHaveText(FIVE_MINUTES);
    expect(await grantMsLeft(serviceWorker)).toBeGreaterThan(4 * 60_000);
  });

  test('a tab already open picks it up without a reload, and loses it when turned off', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({
        tempAllowMinutes: 1,
        rules: [rule(DOMAIN, { mode: 'DELAY', delaySeconds: 2 })],
      }),
    );

    const page = await context.newPage();
    await throughThePause(page, siteUrl(DOMAIN, RICH_PAGE));
    await expect(page.locator(PILL)).toHaveCount(0);
    await page.evaluate(() => {
      (window as unknown as { sameDocument: string }).sameDocument = 'yes';
    });

    await setRepeat({ context, extensionId, serviceWorker }, 10);

    // The grant already running is the one it reads: under a minute left, not ten.
    await page.bringToFront();
    await expect(page.locator(PILL)).toHaveText(/^Next pause in 0:\d\d$/);
    expect(
      await page.evaluate(() => (window as unknown as { sameDocument: string }).sameDocument),
    ).toBe('yes');

    await setRepeat({ context, extensionId, serviceWorker }, null);
    await page.bringToFront();
    await expect(page.locator(PILL)).toHaveCount(0);
  });

  test('injecting twice leaves one pill and one pause', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    siteUrl,
  }) => {
    await setSettings(
      baseSettings({ rules: [rule(DOMAIN, { mode: 'DELAY', delaySeconds: 3 })] }),
    );
    await setRepeat({ context, extensionId, serviceWorker }, 10);

    const page = await context.newPage();
    await throughThePause(page, siteUrl(DOMAIN, RICH_PAGE));
    await expect(page.locator(PILL)).toBeVisible();

    for (let i = 0; i < 2; i += 1) {
      await serviceWorker.evaluate(
        async ([domain, file]) => {
          const tabs = await chrome.tabs.query({ url: `*://${domain}/*` });
          await chrome.scripting.executeScript({
            target: { tabId: tabs[0]!.id! },
            files: [file],
          });
        },
        [DOMAIN, REPEAT_PAUSE_SCRIPT_FILE] as const,
      );
    }

    await expect(page.locator(PILL)).toHaveCount(1);
    await expect(page.locator(REPEAT_PAUSE_HOST_TAG)).toHaveCount(1);

    await grantEndsIn(serviceWorker, page, 500);
    await expect(page.locator(OVERLAY)).toHaveCount(1);
    await expect(page.locator(OVERLAY)).toHaveCount(0, { timeout: 15_000 });
    await expect(page.locator(PILL)).toHaveCount(1);
  });
});
