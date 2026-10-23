// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';

import { handleRequest } from '../../src/background/messagesRouter';
import { loadSettings, saveSettings } from '../../src/background/storage';
import { createChannelObserver } from '../../src/content/channelObserver';
import type { ChannelConfig } from '../../src/content/channelFilter';
import type { ChannelObservation } from '../../src/core/channels';
import type { SaveResult } from '../../src/core/protocol';
import type { ChannelEntry, ChannelListMode, NudgeSettings } from '../../src/core/settingsSchema';
import {
  LIVE_OWNER_CHANNEL_ID,
  LIVE_OWNER_HANDLE,
  LIVE_OWNER_NAME,
  channelPageHtml,
} from '../content/fixtures/youtubeLiveWatch';
import { featuresWith, settings, siteRule } from '../helpers/rules';
import { installAttention, installDnr, resetBrowser } from './fakeApis';

/**
 * A channel id the worker LEARNED must survive a save made from an older copy of the settings.
 *
 * Signed-in QA (0.4.0, 2026-10-23) added "@veritasium" in the dashboard, visited the channel
 * page (which taught the entry its `UC…` id) and then, in the SAME dashboard tab, edited the
 * rule and pressed Save. The dashboard had loaded the settings before the visit, so its
 * `SAVE_SETTINGS` carried the handle-only entry and replaced the learned id wholesale. A live
 * probe confirmed the enrichment itself works on that exact path: the id was written and then
 * overwritten.
 *
 * The observation here is not typed by hand. It is what the real content-script observer
 * reports from the live-captured channel page, so the test follows the bytes the worker
 * really receives.
 */

const NOW = new Date(2026, 9, 23, 12, 0, 0);
const CHANNEL_URL = `https://www.youtube.com/@${LIVE_OWNER_HANDLE}/videos`;

/** What the dashboard writes when the user types "@veritasium": a handle and nothing else. */
const typedByHandle: ChannelEntry = {
  channelId: null,
  handle: LIVE_OWNER_HANDLE,
  displayName: `@${LIVE_OWNER_HANDLE}`,
  addedAt: 1,
};

const learned: ChannelEntry = {
  channelId: LIVE_OWNER_CHANNEL_ID,
  handle: LIVE_OWNER_HANDLE,
  displayName: LIVE_OWNER_NAME,
  addedAt: 1,
};

const OTHER: ChannelEntry = {
  channelId: null,
  handle: 'kurzgesagt',
  displayName: '@kurzgesagt',
  addedAt: 2,
};

function youtubeSettings(
  channels: ChannelEntry[],
  options: { channelMode?: ChannelListMode; creatorMode?: boolean; strict?: boolean } = {},
): NudgeSettings {
  const base = settings();
  return settings({
    strictMode: { ...base.strictMode, enabled: options.strict ?? false },
    rules: [
      siteRule({
        id: 'yt',
        domain: 'youtube.com',
        mode: 'HARD_BLOCK',
        features: featuresWith('youtube', {
          youtube: {
            channelMode: options.channelMode ?? 'WHITELIST',
            channels,
            creatorMode: options.creatorMode ?? false,
            myChannel: null,
          },
        }),
      }),
    ],
  });
}

function storedYoutube(stored: NudgeSettings) {
  return stored.rules[0]?.features?.youtube;
}

async function ask<T>(request: unknown): Promise<T> {
  return (await handleRequest(request as never, NOW)) as T;
}

/** The content script's config for the stored settings, as the worker would hand it over. */
function pageConfig(stored: NudgeSettings): ChannelConfig {
  return {
    enabled: true,
    siteMode: 'HARD_BLOCK',
    siteApplies: true,
    siteDelaySeconds: 15,
    grayscale: false,
    youtube: storedYoutube(stored) ?? null,
  };
}

/** Full-load the live channel page and deliver whatever the real observer reports. */
async function visitChannelPage(): Promise<void> {
  document.body.innerHTML = channelPageHtml();
  const reports: ChannelObservation[] = [];
  createChannelObserver((o) => reports.push(o)).observe(document, pageConfig(await loadSettings()), {
    url: CHANNEL_URL,
  });
  expect(reports).toHaveLength(1);
  for (const observation of reports) {
    await ask({
      type: 'CHANNEL_OBSERVED',
      channelId: observation.channelId ?? null,
      handle: observation.handle ?? null,
      displayName: observation.displayName ?? null,
    });
  }
}

/** The dashboard's "Edit rule -> change something -> Save rule", from its own copy. */
function editYoutube(
  snapshot: NudgeSettings,
  change: (yt: NonNullable<ReturnType<typeof storedYoutube>>) => Partial<
    NonNullable<ReturnType<typeof storedYoutube>>
  >,
): NudgeSettings {
  return {
    ...snapshot,
    rules: snapshot.rules.map((rule) => {
      const yt = rule.features?.youtube;
      if (rule.features === null || yt === undefined) return rule;
      return { ...rule, features: { ...rule.features, youtube: { ...yt, ...change(yt) } } };
    }),
  };
}

beforeEach(() => {
  resetBrowser();
  installDnr();
  installAttention();
  document.body.innerHTML = '';
});

describe('a learned channel id survives a save from a dashboard opened before it was learned', () => {
  it('keeps the id when the stale save only changed something else (the QA sequence)', async () => {
    await saveSettings(youtubeSettings([typedByHandle], { creatorMode: true }));
    // The dashboard tab loads here, and keeps this copy for the rest of the session.
    const dashboardCopy = await ask<NudgeSettings>({ type: 'GET_SETTINGS' });

    await visitChannelPage();
    // Precondition, so a failure below can only mean the save dropped it.
    expect(storedYoutube(await loadSettings())?.channels).toEqual([learned]);

    const result = await ask<SaveResult>({
      type: 'SAVE_SETTINGS',
      settings: editYoutube(dashboardCopy, () => ({ creatorMode: false })),
    });

    expect(result.ok).toBe(true);
    const after = storedYoutube(await loadSettings());
    expect(after?.creatorMode).toBe(false); // the user's edit landed
    expect(after?.channels).toEqual([learned]); // and the learned id is still there
  });

  it('keeps the canonical channel URL open at the network layer after that save', async () => {
    await saveSettings(youtubeSettings([typedByHandle]));
    const dashboardCopy = await ask<NudgeSettings>({ type: 'GET_SETTINGS' });
    await visitChannelPage();

    await ask<SaveResult>({
      type: 'SAVE_SETTINGS',
      settings: editYoutube(dashboardCopy, () => ({ disableAutoplay: true })),
    });

    const rules = await chrome.declarativeNetRequest.getDynamicRules();
    expect(
      rules.some(
        (rule) =>
          rule.action.type === 'allow' &&
          rule.condition.regexFilter?.includes(LIVE_OWNER_CHANNEL_ID) === true,
      ),
    ).toBe(true);
  });

  it('keeps it when the stale save ADDS another channel', async () => {
    await saveSettings(youtubeSettings([typedByHandle]));
    const dashboardCopy = await ask<NudgeSettings>({ type: 'GET_SETTINGS' });
    await visitChannelPage();

    await ask<SaveResult>({
      type: 'SAVE_SETTINGS',
      settings: editYoutube(dashboardCopy, (yt) => ({ channels: [...yt.channels, OTHER] })),
    });

    expect(storedYoutube(await loadSettings())?.channels).toEqual([learned, OTHER]);
  });

  it('never brings back a channel the stale save REMOVED', async () => {
    await saveSettings(youtubeSettings([typedByHandle, OTHER]));
    const dashboardCopy = await ask<NudgeSettings>({ type: 'GET_SETTINGS' });
    await visitChannelPage();

    await ask<SaveResult>({
      type: 'SAVE_SETTINGS',
      settings: editYoutube(dashboardCopy, () => ({ channels: [OTHER] })),
    });

    expect(storedYoutube(await loadSettings())?.channels).toEqual([OTHER]);
  });
});

describe('the Commitment Lock still judges the user, not the stale copy', () => {
  it('does not challenge a harmless edit on a blocklist whose entry learned its id', async () => {
    // The fold runs before the lock judges the save. It must not turn a harmless edit into a
    // challenge, and the blocklist entry must come out still carrying its learned id (without
    // the fold, the save went through but stored the handle-only entry, losing the id).
    await saveSettings(youtubeSettings([typedByHandle], { channelMode: 'BLACKLIST', strict: true }));
    const dashboardCopy = await ask<NudgeSettings>({ type: 'GET_SETTINGS' });
    await visitChannelPage();
    expect(storedYoutube(await loadSettings())?.channels).toEqual([learned]);

    const result = await ask<SaveResult>({
      type: 'SAVE_SETTINGS',
      settings: editYoutube(dashboardCopy, () => ({ disableAutoplay: true })),
    });

    expect(result).toEqual({ ok: true });
    expect(storedYoutube(await loadSettings())?.channels).toEqual([learned]);
  });

  it('still challenges removing that channel from a blocklist', async () => {
    await saveSettings(youtubeSettings([typedByHandle], { channelMode: 'BLACKLIST', strict: true }));
    const dashboardCopy = await ask<NudgeSettings>({ type: 'GET_SETTINGS' });
    await visitChannelPage();

    const result = await ask<SaveResult>({
      type: 'SAVE_SETTINGS',
      settings: editYoutube(dashboardCopy, () => ({ channels: [] })),
    });

    expect(result.ok).toBe(false);
    expect(result.reason).toBe('challenge-required');
    expect(storedYoutube(await loadSettings())?.channels).toEqual([learned]);
  });

  it('still challenges allowing a NEW channel on a whitelist', async () => {
    await saveSettings(youtubeSettings([typedByHandle], { strict: true }));
    const dashboardCopy = await ask<NudgeSettings>({ type: 'GET_SETTINGS' });
    await visitChannelPage();

    const result = await ask<SaveResult>({
      type: 'SAVE_SETTINGS',
      settings: editYoutube(dashboardCopy, (yt) => ({ channels: [...yt.channels, OTHER] })),
    });

    expect(result.reason).toBe('challenge-required');
  });
});
