import {
  baseSettings,
  expect,
  expectedRuleCount,
  platformRule,
  sendFromExtensionPage,
  test,
  waitForRuleCount,
} from './fixtures';
import type { ChannelEntry, NudgeSettings } from '../src/core/settingsSchema';

/**
 * "Block YouTube by default, but let these channels through" — end to end.
 *
 * This is the feature Anti asked for by name, and it is the one that cannot be verified in
 * unit tests, because it only works when THREE layers agree: the site redirect blocks
 * YouTube, the channel allow-rules carve `/watch` and the listed channels' pages back out
 * of that redirect, and the content script decides per video whether the page that got
 * through actually plays. Any one of them alone produces a coherent-looking wrong answer —
 * all of YouTube blocked including the channels the user picked, or all of YouTube open.
 *
 * Every assertion below is what the user sees: the block page, the video playing, the
 * interstitial's own words.
 */

const ALLOWED = 'UCallowedchannel0000001';
const OTHER = 'UCotherchannel000000002';

function channel(channelId: string, displayName: string): ChannelEntry {
  return { channelId, handle: null, displayName, addedAt: 0 };
}

function watchUrl(video: string, channelId?: string, name = 'A Channel'): string {
  const base = `https://www.youtube.com/watch?v=${video}`;
  return channelId === undefined
    ? base
    : `${base}&channel=${channelId}&name=${encodeURIComponent(name)}`;
}

/** YouTube itself Hard Blocked, with one channel carved out. */
function blockedExcept(channels: ChannelEntry[]): Partial<NudgeSettings> {
  return baseSettings({
    rules: [
      platformRule(
        'youtube.com',
        { youtube: { channelMode: 'WHITELIST', channels } },
        { mode: 'HARD_BLOCK' },
      ),
    ],
  });
}

test.describe('YouTube Hard Block with a channel whitelist', () => {
  test('the home feed is blocked and the block page links the allowed channels', async ({
    context,
    setSettings,
  }) => {
    // Without the links this feature is technically correct and practically useless: the
    // home feed, search and subscriptions are all redirected, so the only way to reach a
    // channel the user explicitly allowed would be typing its URL from memory.
    await setSettings(blockedExcept([channel(ALLOWED, 'Allowed Channel')]));

    const page = await context.newPage();
    await page.goto('https://www.youtube.com/');

    await expect(page).toHaveURL(/blocked\.html\?target=/);
    await expect(page.getByText('Your allowed channels')).toBeVisible();

    const link = page.getByRole('link', { name: 'Allowed Channel' });
    await expect(link).toBeVisible();
    await expect(link).toHaveAttribute('href', `https://www.youtube.com/channel/${ALLOWED}`);
  });

  test('a video from an allowed channel plays', async ({ context, setSettings }) => {
    // Both halves at once: DNR has to let the request through (the site redirect would
    // otherwise catch it before any script runs) AND the content script has to decide the
    // channel is listed. A failure in either one looks identical from here — the block
    // page or an interstitial instead of the video.
    await setSettings(blockedExcept([channel(ALLOWED, 'Allowed Channel')]));

    const page = await context.newPage();
    await page.goto(watchUrl('abc123', ALLOWED, 'Allowed Channel'));

    await expect(page.locator('#player')).toBeVisible();
    // Give the content script the same window it would have had to interrupt.
    await page.waitForTimeout(3_000);
    await expect(page).toHaveURL(/youtube\.com\/watch/);
    await expect(page.getByText('This channel is off your list')).toHaveCount(0);
    await expect(page.getByText('YouTube is blocked right now')).toHaveCount(0);
  });

  test('a video from a channel that is not on the list is held behind the SITE rule', async ({
    context,
    setSettings,
  }) => {
    // The wording matters and is asserted for that reason: "YouTube is blocked except the
    // channels you picked" sends the user to the site rule, which is what they would have
    // to edit. The channel-list wording would send them to the wrong screen.
    await setSettings(blockedExcept([channel(ALLOWED, 'Allowed Channel')]));

    const page = await context.newPage();
    await page.goto(watchUrl('def456', OTHER, 'Some Other Channel'));

    await expect(page.getByText('This channel is off your list')).toBeVisible({
      timeout: 15_000,
    });
    await expect(
      page.getByText("YouTube is blocked except the channels you picked. This one isn't one of them."),
    ).toBeVisible();
  });

  test('a video whose channel cannot be identified falls to the site default and is blocked', async ({
    context,
    setSettings,
  }) => {
    // The fail-CLOSED direction, which exists only here. The site's stated default is
    // "blocked", so a rotted selector must not quietly open it — and the copy has to say
    // that this is Nudge's uncertainty rather than an accusation against the channel,
    // which might well be one the user allowed.
    await setSettings(blockedExcept([channel(ALLOWED, 'Allowed Channel')]));

    const page = await context.newPage();
    // No channel anywhere on the page: no inline player response, no owner byline.
    await page.goto(watchUrl('ghi789'));

    await expect(page.getByText('YouTube is blocked right now')).toBeVisible({
      timeout: 15_000,
    });
    await expect(
      page.getByText("Nudge couldn't identify this video's channel, so YouTube's default rule applies."),
    ).toBeVisible();
  });

  test('once the daily budget is spent, even an allowed channel is blocked', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
    seedUsage,
  }) => {
    // The documented ruling, and the one that is inverted if it is got wrong: a daily
    // limit budgets HOW MUCH of the site, a channel list restricts WHAT. If the allow-rules
    // survived an exhausted budget, "an hour of YouTube a day" would be unlimited for the
    // channels the user likes and would only ever bite the content they asked for less of.
    const settings = baseSettings({
      rules: [
        platformRule(
          'youtube.com',
          {
            youtube: {
              channelMode: 'WHITELIST',
              channels: [channel(ALLOWED, 'Allowed Channel')],
            },
          },
          { mode: 'ALLOW', dailyLimitMinutes: 10 },
        ),
      ],
    });

    // Under budget first: the allowed channel plays, so the block below is demonstrably
    // the budget and not the rule being broken all along.
    await seedUsage('youtube.com', 60);
    await setSettings(settings, { 'youtube.com': 60_000 });
    const open = await context.newPage();
    await open.goto(watchUrl('abc123', ALLOWED, 'Allowed Channel'));
    await expect(open.locator('#player')).toBeVisible();
    await open.close();

    // `SAVE_SETTINGS` is what makes the worker look again: writing an unchanged value to
    // storage fires no `onChanged`, while a save recompiles unconditionally — and the rule
    // set is a function of (settings, usage, now), so usage alone has to be able to move it.
    await seedUsage('youtube.com', 10 * 60);
    await sendFromExtensionPage(context, extensionId, {
      type: 'SAVE_SETTINGS',
      settings: settings as NudgeSettings,
    });
    await waitForRuleCount(
      serviceWorker,
      expectedRuleCount(settings, { 'youtube.com': 10 * 60 * 1_000 }),
    );

    const spent = await context.newPage();
    await spent.goto(watchUrl('abc123', ALLOWED, 'Allowed Channel'));

    await expect(spent).toHaveURL(/blocked\.html\?target=/);
    await expect(spent.getByText('Daily limit reached')).toBeVisible();
  });
});

/**
 * The stored entry learning the identifier the user never typed, end to end.
 *
 * Unit tests prove each half (`enrichEntries` folds the id in, and the DNR compiler emits a
 * `/channel/UC…` allow-rule once the entry has one). What only a real browser can prove is
 * that the halves MEET: the content script has to identify the channel on a page that keeps
 * its two identifiers in different places, the worker has to persist it, and the network
 * layer has to be recompiled before the next navigation. A failure anywhere in that chain
 * looks identical from the outside — the channel's own page stays blocked.
 */
test.describe('a whitelist entry learns the identifier it was missing', () => {
  const LEARNED_ID = 'UClearnedchannel0000001';
  const LEARNED_HANDLE = 'learnedchannel';
  const LEARNED_NAME = 'Learned Channel';

  /** What the UI writes when the user types "@learnedchannel": a handle and nothing else. */
  const byHandleOnly: ChannelEntry = {
    channelId: null,
    handle: LEARNED_HANDLE,
    displayName: `@${LEARNED_HANDLE}`,
    addedAt: 0,
  };

  const afterLearning: ChannelEntry = {
    channelId: LEARNED_ID,
    handle: LEARNED_HANDLE,
    displayName: LEARNED_NAME,
    addedAt: 0,
  };

  /** A watch page shaped like the real thing: id in the inline JSON, handle in the byline. */
  function watchUrlWithHandle(video: string): string {
    return (
      `https://www.youtube.com/watch?v=${video}&channel=${LEARNED_ID}` +
      `&handle=${LEARNED_HANDLE}&name=${encodeURIComponent(LEARNED_NAME)}`
    );
  }

  test('so the channel page it always allowed stops being redirected', async ({
    context,
    extensionId,
    serviceWorker,
    setSettings,
  }) => {
    await setSettings(blockedExcept([byHandleOnly]));

    // The hole, demonstrated before it is closed. The user allowed this channel, but the
    // network layer only knows the handle, so the channel's own canonical URL is redirected
    // — and no content script can rescue it, the block page has already won.
    const cold = await context.newPage();
    await cold.goto(`https://www.youtube.com/channel/${LEARNED_ID}`);
    await expect(cold).toHaveURL(/blocked\.html\?target=/);
    await cold.close();

    // Watch one of that channel's videos. It plays (the handle already matches in-page),
    // and detection assembles both identifiers off the one page.
    const watch = await context.newPage();
    await watch.goto(watchUrlWithHandle('enrich01'));
    await expect(watch.locator('#player')).toBeVisible();

    // The recompile is the observable that the worker has finished: an entry with an id
    // earns one allow-rule more than the same entry without one.
    await waitForRuleCount(serviceWorker, expectedRuleCount(blockedExcept([afterLearning])));

    const stored = await sendFromExtensionPage<NudgeSettings>(context, extensionId, {
      type: 'GET_SETTINGS',
    });
    expect(stored.rules[0]?.features?.youtube?.channels).toEqual([afterLearning]);

    // The payoff, and the reason the feature exists at all.
    const warm = await context.newPage();
    await warm.goto(`https://www.youtube.com/channel/${LEARNED_ID}`);
    await expect(warm).toHaveURL(new RegExp(`youtube\\.com/channel/${LEARNED_ID}`));
    await expect(warm).not.toHaveURL(/blocked\.html/);
  });

  test('without ever opening the rest of YouTube', async ({
    context,
    serviceWorker,
    setSettings,
  }) => {
    // Enrichment adds identifiers to a channel the user already listed. It must never add a
    // channel, so everything that was blocked before it ran is still blocked after.
    await setSettings(blockedExcept([byHandleOnly]));

    const watch = await context.newPage();
    await watch.goto(watchUrlWithHandle('enrich02'));
    await expect(watch.locator('#player')).toBeVisible();
    await waitForRuleCount(serviceWorker, expectedRuleCount(blockedExcept([afterLearning])));
    await watch.close();

    const home = await context.newPage();
    await home.goto('https://www.youtube.com/');
    await expect(home).toHaveURL(/blocked\.html\?target=/);
    await home.close();

    const other = await context.newPage();
    await other.goto(`https://www.youtube.com/channel/${OTHER}`);
    await expect(other).toHaveURL(/blocked\.html\?target=/);
  });
});
