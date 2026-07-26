import { describe, expect, it } from 'vitest';

import {
  isoDayOfWeek,
  isScheduleActiveAt,
  localDayKey,
  minutesSinceMidnight,
  msUntilNextLocalMidnight,
  type ScheduleWindow,
} from '../../src/core/scheduleEvaluator';

/**
 * Port of app/src/test/java/.../ScheduleEvaluatorTest.kt, plus the overnight-boundary,
 * start===end, and DST-safety cases the extension spec calls out explicitly.
 *
 * All dates are built with the local `new Date(y, m, d, h, mi)` constructor (never a
 * hardcoded UTC string) and no test asserts a fixed absolute epoch — so the suite passes
 * in any machine timezone.
 *
 * Reference week used throughout (verified 2026 calendar):
 *   2026-01-04 Sun, 01-05 Mon, 01-06 Tue, 01-07 Wed, 01-08 Thu, 01-09 Fri, 01-10 Sat, 01-11 Sun.
 */

/** 1-based month, matching how humans read dates ("January" = 1), unlike raw `Date`. */
function at(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
  second = 0,
  ms = 0,
): Date {
  return new Date(year, month - 1, day, hour, minute, second, ms);
}

function window(overrides: Partial<ScheduleWindow> = {}): ScheduleWindow {
  return { days: null, startMinute: null, endMinute: null, ...overrides };
}

describe('isScheduleActiveAt — no schedule fields set = always active', () => {
  it('is active on a weekday', () => {
    expect(isScheduleActiveAt(window(), at(2026, 1, 5, 9, 0))).toBe(true); // Mon
  });

  it('is active on a weekend too', () => {
    expect(isScheduleActiveAt(window(), at(2026, 1, 10, 23, 59))).toBe(true); // Sat
  });
});

describe('isScheduleActiveAt — day-of-week only', () => {
  const weekdays = window({ days: [1, 2, 3, 4, 5] }); // Mon-Fri

  it('is active on a matching weekday', () => {
    expect(isScheduleActiveAt(weekdays, at(2026, 1, 5, 10, 0))).toBe(true); // Mon
  });

  it('is inactive on Saturday', () => {
    expect(isScheduleActiveAt(weekdays, at(2026, 1, 10, 10, 0))).toBe(false);
  });

  it('is inactive on Sunday', () => {
    expect(isScheduleActiveAt(weekdays, at(2026, 1, 11, 14, 0))).toBe(false);
  });

  it('only-days-set is active all day (midnight) on a matching day', () => {
    const weekend = window({ days: [6, 7] });
    expect(isScheduleActiveAt(weekend, at(2026, 1, 10, 0, 0))).toBe(true); // Sat 00:00
  });

  it('only-days-set is inactive on a non-matching day regardless of time', () => {
    const weekend = window({ days: [6, 7] });
    expect(isScheduleActiveAt(weekend, at(2026, 1, 5, 12, 0))).toBe(false); // Mon
  });
});

describe('isScheduleActiveAt — normal time range (end EXCLUSIVE)', () => {
  const nineToFive = window({ startMinute: 540, endMinute: 1020 }); // 09:00-17:00

  it('is active within the range', () => {
    expect(isScheduleActiveAt(nineToFive, at(2026, 1, 7, 10, 0))).toBe(true); // Wed
  });

  it('is inactive before the range', () => {
    expect(isScheduleActiveAt(nineToFive, at(2026, 1, 7, 8, 0))).toBe(false);
  });

  it('is active at the exact start time', () => {
    expect(isScheduleActiveAt(nineToFive, at(2026, 1, 7, 9, 0))).toBe(true);
  });

  it('is inactive at the exact end time (end is exclusive)', () => {
    expect(isScheduleActiveAt(nineToFive, at(2026, 1, 7, 17, 0))).toBe(false);
  });

  it('is inactive after the range', () => {
    expect(isScheduleActiveAt(nineToFive, at(2026, 1, 7, 18, 0))).toBe(false);
  });

  it('times-only (no days) applies on a weekend day too — every day in the window', () => {
    expect(isScheduleActiveAt(nineToFive, at(2026, 1, 10, 10, 0))).toBe(true); // Sat
  });
});

describe('isScheduleActiveAt — overnight span (end < start)', () => {
  const overnight = window({ startMinute: 1380, endMinute: 360 }); // 23:00-06:00

  it('is active at 23:30', () => {
    expect(isScheduleActiveAt(overnight, at(2026, 1, 9, 23, 30))).toBe(true); // Fri
  });

  it('is active at 02:00', () => {
    expect(isScheduleActiveAt(overnight, at(2026, 1, 9, 2, 0))).toBe(true);
  });

  it('is inactive at 12:00 (noon)', () => {
    expect(isScheduleActiveAt(overnight, at(2026, 1, 9, 12, 0))).toBe(false);
  });

  it('is inactive at 07:00', () => {
    expect(isScheduleActiveAt(overnight, at(2026, 1, 9, 7, 0))).toBe(false);
  });

  it('is active exactly at the 23:00 start boundary', () => {
    expect(isScheduleActiveAt(overnight, at(2026, 1, 9, 23, 0))).toBe(true);
  });

  it('is inactive exactly at the 06:00 end boundary (exclusive, same as the normal-range rule)', () => {
    expect(isScheduleActiveAt(overnight, at(2026, 1, 9, 6, 0))).toBe(false);
  });
});

describe('isScheduleActiveAt — start === end is a window that is NEVER active (deliberate Android parity)', () => {
  const zeroWidth = window({ startMinute: 600, endMinute: 600 }); // 10:00-10:00

  it('is inactive at the boundary minute itself', () => {
    expect(isScheduleActiveAt(zeroWidth, at(2026, 1, 7, 10, 0))).toBe(false);
  });

  it('is inactive at any other time of day', () => {
    expect(isScheduleActiveAt(zeroWidth, at(2026, 1, 7, 0, 0))).toBe(false);
    expect(isScheduleActiveAt(zeroWidth, at(2026, 1, 7, 23, 59))).toBe(false);
  });
});

describe('isScheduleActiveAt — combined day + time', () => {
  const weekdayNineToFive = window({ days: [1, 2, 3, 4, 5], startMinute: 540, endMinute: 1020 });

  it('is active on a weekday within the range', () => {
    expect(isScheduleActiveAt(weekdayNineToFive, at(2026, 1, 7, 10, 0))).toBe(true); // Wed
  });

  it('is inactive on the weekend even within the time range', () => {
    expect(isScheduleActiveAt(weekdayNineToFive, at(2026, 1, 10, 10, 0))).toBe(false); // Sat
  });

  it('is inactive on a weekday outside the time range', () => {
    expect(isScheduleActiveAt(weekdayNineToFive, at(2026, 1, 5, 8, 0))).toBe(false); // Mon 8am
  });
});

describe('isoDayOfWeek', () => {
  it('maps Sunday to ISO 7', () => {
    expect(isoDayOfWeek(at(2026, 1, 4, 12, 0))).toBe(7); // 2026-01-04 is a Sunday
  });

  it('maps Monday to ISO 1', () => {
    expect(isoDayOfWeek(at(2026, 1, 5, 12, 0))).toBe(1); // 2026-01-05 is a Monday
  });
});

describe('minutesSinceMidnight', () => {
  it('is 0 at local midnight', () => {
    expect(minutesSinceMidnight(at(2026, 1, 7, 0, 0))).toBe(0);
  });

  it('counts hours*60 + minutes', () => {
    expect(minutesSinceMidnight(at(2026, 1, 7, 14, 37))).toBe(14 * 60 + 37);
  });
});

describe('msUntilNextLocalMidnight', () => {
  it('from 23:59:59.000 local it is exactly 1000ms', () => {
    const now = at(2026, 6, 15, 23, 59, 59, 0);
    expect(msUntilNextLocalMidnight(now)).toBe(1000);
  });

  // Sample times spanning ordinary days, month-end/non-leap-Feb rollover, and the two
  // windows where DST transitions typically fall (late March / late October) in many
  // regions (EU: last Sunday of March & October; US: second Sunday of March / first of
  // November; AU: first Sunday of October). The assertions below are self-consistency
  // checks (re-derive from now+result and confirm it lands exactly on local midnight),
  // so they hold regardless of whether the machine running the suite actually observes
  // a DST shift on that date.
  const sampleTimes: Array<[number, number, number, number, number, number, number]> = [
    [2026, 1, 1, 0, 0, 0, 0],
    [2026, 2, 28, 23, 30, 0, 0], // month-end, non-leap Feb (2026 is not a leap year)
    [2026, 3, 28, 12, 0, 0, 0], // near typical DST transition (late March)
    [2026, 3, 29, 1, 30, 0, 0],
    [2026, 3, 31, 23, 59, 59, 999],
    [2026, 10, 24, 12, 0, 0, 0], // near typical DST transition (late October)
    [2026, 10, 25, 2, 30, 0, 0],
    [2026, 10, 31, 23, 0, 0, 0],
    [2026, 12, 31, 23, 59, 0, 0], // year rollover
  ];

  it.each(sampleTimes)(
    'is > 0, <= 25h, and lands exactly on local midnight (from %d-%d-%d %d:%d:%d.%d)',
    (y, m, d, h, mi, s, ms) => {
      const now = at(y, m, d, h, mi, s, ms);
      const result = msUntilNextLocalMidnight(now);

      expect(result).toBeGreaterThan(0);
      expect(result).toBeLessThanOrEqual(25 * 60 * 60 * 1000);

      const landed = new Date(now.getTime() + result);
      expect(landed.getHours()).toBe(0);
      expect(landed.getMinutes()).toBe(0);
      expect(landed.getSeconds()).toBe(0);
      expect(landed.getMilliseconds()).toBe(0);
    },
  );
});

describe('localDayKey', () => {
  it('zero-pads single-digit month and day', () => {
    expect(localDayKey(at(2026, 1, 5, 10, 0))).toBe('2026-01-05');
  });

  it('does not pad double-digit month/day', () => {
    expect(localDayKey(at(2026, 12, 25, 0, 0))).toBe('2026-12-25');
  });
});
