import { baseSettings, expect, platformRule, test } from './fixtures';
import type { ChannelEntry, NudgeSettings, ScheduleChannelOverride } from '../src/core/settingsSchema';

/**
 * A Scheduled Override with its own channel list (schema v8), end to end. Anti's case:
 * YouTube open all day with junk channels blocked, but only study channels inside the
 * window. The in-page channel gate reads the list through `GET_SITE_CONFIG`, which the
 * worker resolves at `now`, so the same three videos get opposite answers inside and
 * outside the window. The gate's subtitle names which list held the video.
 */

const STUDY = 'UCstudychannel000000001';
const JUNK = 'UCjunkchannel0000000002';
const OTHER = 'UCotherchannel000000003';

const WHITELIST_SUBTITLE = 'You chose to watch only channels you picked. Still want this one?';
const BLACKLIST_SUBTITLE = 'You asked Nudge to keep you away from this channel.';

function channel(channelId: string, displayName: string): ChannelEntry {
  return { channelId, handle: null, displayName, addedAt: 0 };
}

function watchUrl(video: string, channelId: string, name: string): string {
  return `https://www.youtube.com/watch?v=${video}&channel=${channelId}&name=${encodeURIComponent(name)}`;
}

/** Minutes from local midnight, offset by `deltaMinutes`, wrapped into 0..1439. */
function minuteOfDay(deltaMinutes = 0): number {
  const now = new Date();
  return (((now.getHours() * 60 + now.getMinutes() + deltaMinutes) % 1440) + 1440) % 1440;
}

/** YouTube allowed with a blacklist; a window (relative to now) that allows only STUDY. */
function studyWindow(startDelta: number, endDelta: number): Partial<NudgeSettings> {
  const window: ScheduleChannelOverride = {
    channelMode: 'WHITELIST',
    channels: [channel(STUDY, 'Study Channel')],
    allowSubscriptions: false,
  };
  return baseSettings({
    rules: [
      platformRule(
        'youtube.com',
        {
          youtube: {
            channelMode: 'BLACKLIST',
            channels: [channel(JUNK, 'Junk Channel')],
            channelBlockMode: 'HARD_BLOCK',
          },
        },
        {
          mode: 'ALLOW',
          schedule: {
            enabled: true,
            days: null,
            startMinute: minuteOfDay(startDelta),
            endMinute: minuteOfDay(endDelta),
            mode: 'ALLOW',
            delaySeconds: 15,
            channels: window,
          },
        },
      ),
    ],
  });
}

test.describe('Scheduled Override channel list', () => {
  test('inside the window only the window whitelist plays', async ({ context, setSettings }) => {
    await setSettings(studyWindow(-30, 30));

    const study = await context.newPage();
    await study.goto(watchUrl('study1', STUDY, 'Study Channel'));
    await expect(study.locator('#player')).toBeVisible();
    // Give the content script the window it would have had to interrupt.
    await study.waitForTimeout(3_000);
    await expect(study.getByText('This channel is off your list')).toHaveCount(0);

    // Not on the default blacklist, so it would play all day; the window's list holds it.
    const other = await context.newPage();
    await other.goto(watchUrl('other1', OTHER, 'Other Channel'));
    await expect(other.getByText(WHITELIST_SUBTITLE)).toBeVisible({ timeout: 15_000 });
  });

  test('outside the window the default blacklist is back', async ({ context, setSettings }) => {
    await setSettings(studyWindow(120, 180));

    const other = await context.newPage();
    await other.goto(watchUrl('other1', OTHER, 'Other Channel'));
    await expect(other.locator('#player')).toBeVisible();
    await other.waitForTimeout(3_000);
    await expect(other.getByText('This channel is off your list')).toHaveCount(0);

    const junk = await context.newPage();
    await junk.goto(watchUrl('junk1', JUNK, 'Junk Channel'));
    await expect(junk.getByText(BLACKLIST_SUBTITLE)).toBeVisible({ timeout: 15_000 });
  });
});
