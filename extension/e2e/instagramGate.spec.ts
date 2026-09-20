import { baseSettings, expect, platformRule, test } from './fixtures';

/**
 * Instagram's Reels gate, on both routes a user can actually reach it by.
 *
 * A gate surface is enforced by TWO independent mechanisms that have to agree, and the
 * classic half-shipped feature is one of them working alone: DNR catches full page loads
 * and typed URLs but is blind to client-side routing, while the content script catches an
 * in-app tap but never sees a request. Both are driven from the same path patterns in
 * `core/platforms.ts` precisely so they cannot disagree — so both are tested, and each
 * against the surface the OTHER one cannot see.
 *
 * `*.instagram.com` is mapped onto the local HTTPS fixture server (see fixtures.ts).
 * HTTPS is not optional: instagram.com is in Chrome's HSTS preload list, exactly like
 * youtube.com, so a plain-HTTP fixture is force-upgraded and answers
 * ERR_SSL_PROTOCOL_ERROR before the resolver rule is ever consulted.
 */

/** Instagram allowed as a site, with only the Reels surface gated. */
function reelsGated(mode: 'HARD_BLOCK' | 'DELAY' = 'HARD_BLOCK') {
  return baseSettings({
    rules: [
      platformRule(
        'instagram.com',
        { gates: { reels: { mode, delaySeconds: 15 } } },
        { mode: 'ALLOW' },
      ),
    ],
  });
}

test.describe('Instagram Reels gate', () => {
  test('a full load of /reels/ lands on the block page while the rest of the site opens', async ({
    context,
    setSettings,
  }) => {
    // The pair is the claim: "Instagram is allowed, Reels is not" is a single rule, and a
    // gate redirect that also caught the site (or a site that swallowed the gate) would
    // fail one half of this.
    await setSettings(reelsGated());

    const home = await context.newPage();
    await home.goto('https://www.instagram.com/');
    await expect(home.locator('#host')).toHaveText('www.instagram.com');
    await home.close();

    const reels = await context.newPage();
    await reels.goto('https://www.instagram.com/reels/');

    await expect(reels).toHaveURL(/blocked\.html\?target=/);
    // The page names the SURFACE, not the domain — someone who gated Reels and left
    // Instagram open must not be told "instagram.com is blocked".
    await expect(reels.getByText('Rule: instagram.com · Reels')).toBeVisible();
    await expect(reels.getByRole('button', { name: /go back/i })).toBeVisible();
  });

  test('an individual reel page is gated too, not just the Reels tab', async ({
    context,
    setSettings,
  }) => {
    // `/reel/<id>` is a different path pattern from `/reels/` and is how a shared link or
    // a profile tap actually arrives. One pattern shipped and the other forgotten is
    // invisible until someone follows a link from a friend.
    await setSettings(reelsGated());

    const page = await context.newPage();
    await page.goto('https://www.instagram.com/reel/Cxyz123/');

    await expect(page).toHaveURL(/blocked\.html\?target=/);
    await expect(page.getByText('Rule: instagram.com · Reels')).toBeVisible();
  });

  test('navigating to Reels inside the app raises the interstitial', async ({
    context,
    setSettings,
  }) => {
    // The route DNR cannot see. Instagram is a single-page app, so tapping Reels calls
    // `history.pushState` and issues no request at all — and an isolated-world content
    // script cannot observe that call either, which is why the href-diff poll in
    // `content/spaNav.ts` exists. If it ever stops running, this is the test that notices,
    // and the user-visible symptom is precisely this: the surface just opens.
    await setSettings(reelsGated());

    const page = await context.newPage();
    await page.goto('https://www.instagram.com/');
    await expect(page.locator('#host')).toHaveText('www.instagram.com');
    // Nothing is gated where we landed.
    await expect(page.getByText('Reels is blocked')).toHaveCount(0);

    await page.evaluate(() => {
      history.pushState({}, '', '/reels/');
    });

    await expect(page.getByText('Reels is blocked')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'I changed my mind' })).toBeVisible();
    // Still the same document — the interstitial is in-page, not a navigation.
    await expect(page).toHaveURL('https://www.instagram.com/reels/');
  });

  test('leaving Reels inside the app dismisses the interstitial', async ({
    context,
    setSettings,
  }) => {
    // The other half of the in-app path, and the one that turns a gate into a trap if it
    // is missing: an overlay that never tears down leaves the whole site unusable after a
    // single stray tap, which is a bug report about Instagram being "broken", not about
    // Reels being blocked.
    await setSettings(reelsGated());

    const page = await context.newPage();
    await page.goto('https://www.instagram.com/');
    await page.evaluate(() => {
      history.pushState({}, '', '/reels/');
    });
    await expect(page.getByText('Reels is blocked')).toBeVisible({ timeout: 15_000 });

    await page.evaluate(() => {
      history.pushState({}, '', '/');
    });

    await expect(page.getByText('Reels is blocked')).toHaveCount(0, { timeout: 15_000 });
  });
});
