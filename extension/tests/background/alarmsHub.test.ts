import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  ensureAlarms,
  ensureScheduleAlarm,
  handleAlarm,
  nextScheduleBoundary,
  HEARTBEAT_ALARM,
  MIDNIGHT_ALARM,
  SCHEDULE_ALARM,
} from '../../src/background/alarmsHub';
import { saveSettings } from '../../src/background/storage';
import { scheduleOverride, settings, siteRule } from '../helpers/rules';
import { installAttention, installDnr, resetBrowser, type DnrState } from './fakeApis';

/**
 * The schedule-edge alarm exists because v0.2 lets a schedule flip a rule in BOTH
 * directions: "Allow all day, Hard Block 9–5" means a redirect has to come into existence
 * at 09:00 with nobody navigating. Nothing else in the worker wakes up for that.
 */

const MIDDAY = new Date(2026, 8, 20, 12, 0, 0);
const WORK_HOURS = () =>
  scheduleOverride({ mode: 'HARD_BLOCK', startMinute: 9 * 60, endMinute: 17 * 60 });

let dnr: DnrState;

beforeEach(() => {
  resetBrowser();
  dnr = installDnr();
  installAttention();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('nextScheduleBoundary', () => {
  it('is nothing at all when no rule has a schedule', () => {
    expect(nextScheduleBoundary(settings({ rules: [siteRule()] }), MIDDAY)).toBeNull();
  });

  it('is the next edge still ahead today', () => {
    const config = settings({
      rules: [siteRule({ mode: 'ALLOW', schedule: WORK_HOURS() })],
    });
    expect(nextScheduleBoundary(config, MIDDAY)).toBe(
      new Date(2026, 8, 20, 17, 0, 0).getTime(),
    );
  });

  it('rolls over to tomorrow once the day’s edges are behind us', () => {
    const config = settings({
      rules: [siteRule({ mode: 'ALLOW', schedule: WORK_HOURS() })],
    });
    const evening = new Date(2026, 8, 20, 21, 0, 0);
    expect(nextScheduleBoundary(config, evening)).toBe(
      new Date(2026, 8, 21, 9, 0, 0).getTime(),
    );
  });

  it('takes the earliest edge across several rules', () => {
    const config = settings({
      rules: [
        siteRule({ id: 'a', domain: 'a.com', schedule: WORK_HOURS() }),
        siteRule({
          id: 'b',
          domain: 'b.com',
          schedule: scheduleOverride({ startMinute: 13 * 60, endMinute: 14 * 60 }),
        }),
      ],
    });
    expect(nextScheduleBoundary(config, MIDDAY)).toBe(
      new Date(2026, 8, 20, 13, 0, 0).getTime(),
    );
  });

  it('ignores a switched-off rule and a switched-off schedule', () => {
    const off = settings({
      rules: [
        siteRule({ id: 'a', enabled: false, schedule: WORK_HOURS() }),
        siteRule({
          id: 'b',
          domain: 'b.com',
          schedule: scheduleOverride({ enabled: false, startMinute: 60, endMinute: 120 }),
        }),
      ],
    });
    expect(nextScheduleBoundary(off, MIDDAY)).toBeNull();
  });
});

describe('ensureScheduleAlarm', () => {
  it('arms the worker for the next edge', async () => {
    await ensureScheduleAlarm(
      settings({ rules: [siteRule({ mode: 'ALLOW', schedule: WORK_HOURS() })] }),
      MIDDAY,
    );
    const alarm = await chrome.alarms.get(SCHEDULE_ALARM);
    expect(alarm?.scheduledTime).toBe(new Date(2026, 8, 20, 17, 0, 0).getTime());
  });

  it('re-arms rather than stacking when the schedule changes', async () => {
    const rule = siteRule({ mode: 'ALLOW', schedule: WORK_HOURS() });
    await ensureScheduleAlarm(settings({ rules: [rule] }), MIDDAY);
    await ensureScheduleAlarm(
      settings({
        rules: [
          { ...rule, schedule: scheduleOverride({ startMinute: 60, endMinute: 13 * 60 }) },
        ],
      }),
      MIDDAY,
    );
    const alarm = await chrome.alarms.get(SCHEDULE_ALARM);
    expect(alarm?.scheduledTime).toBe(new Date(2026, 8, 20, 13, 0, 0).getTime());
  });

  it('clears the alarm once the last schedule is gone', async () => {
    await ensureScheduleAlarm(
      settings({ rules: [siteRule({ schedule: WORK_HOURS() })] }),
      MIDDAY,
    );
    await ensureScheduleAlarm(settings({ rules: [siteRule()] }), MIDDAY);
    expect(await chrome.alarms.get(SCHEDULE_ALARM)).toBeUndefined();
  });
});

describe('ensureAlarms', () => {
  it('puts the heartbeat, the midnight reset and the schedule edge in place', async () => {
    await ensureAlarms(
      settings({ rules: [siteRule({ mode: 'ALLOW', schedule: WORK_HOURS() })] }),
      MIDDAY,
    );
    expect(await chrome.alarms.get(HEARTBEAT_ALARM)).toBeDefined();
    expect(await chrome.alarms.get(MIDNIGHT_ALARM)).toBeDefined();
    expect(await chrome.alarms.get(SCHEDULE_ALARM)).toBeDefined();
  });
});

describe('when the schedule edge arrives', () => {
  it('blocks a site that was open all morning, with no navigation involved', async () => {
    const config = settings({
      rules: [
        siteRule({
          domain: 'youtube.com',
          mode: 'ALLOW',
          schedule: WORK_HOURS(),
        }),
      ],
    });
    await saveSettings(config);

    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 20, 8, 59, 0));
    await handleAlarm({ name: SCHEDULE_ALARM } as chrome.alarms.Alarm);
    expect(dnr.dynamic).toHaveLength(0);

    vi.setSystemTime(new Date(2026, 8, 20, 9, 0, 0));
    await handleAlarm({ name: SCHEDULE_ALARM } as chrome.alarms.Alarm);

    const blocked = dnr.dynamic.some((rule) =>
      new RegExp(rule.condition.regexFilter!).test('https://www.youtube.com/'),
    );
    expect(blocked).toBe(true);
    // And it has already armed itself for the other end of the window.
    expect((await chrome.alarms.get(SCHEDULE_ALARM))?.scheduledTime).toBe(
      new Date(2026, 8, 20, 17, 0, 0).getTime(),
    );
  });
});
