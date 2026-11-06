import { describe, expect, it } from 'vitest';

import {
  REPEAT_PAUSE_MAX_MINUTES,
  REPEAT_PAUSE_MIN_MINUTES,
  coerceRepeatPauseMinutes,
  formatTimeLeft,
  isRepeatPauseWeakening,
  nextPauseLabel,
  pauseGrantMinutes,
  repeatPauseSentence,
} from '../../src/core/repeatPause';

describe('coerceRepeatPauseMinutes', () => {
  it.each([
    [undefined, null],
    [null, null],
    ['10', null],
    [true, null],
    [Number.NaN, null],
    [Number.POSITIVE_INFINITY, null],
    [{}, null],
    [10, 10],
    [0, REPEAT_PAUSE_MIN_MINUTES],
    [-5, REPEAT_PAUSE_MIN_MINUTES],
    [121, REPEAT_PAUSE_MAX_MINUTES],
    [9999, REPEAT_PAUSE_MAX_MINUTES],
    [7.6, 8],
  ])('reads %s as %s', (input, expected) => {
    expect(coerceRepeatPauseMinutes(input)).toBe(expected);
  });

  it('holds the agreed range', () => {
    expect(REPEAT_PAUSE_MIN_MINUTES).toBe(1);
    expect(REPEAT_PAUSE_MAX_MINUTES).toBe(120);
  });
});

describe('isRepeatPauseWeakening (temp allow 10 minutes)', () => {
  const TEMP = 10;
  it.each<[string, number | null, number | null, boolean]>([
    ['off stays off', null, null, false],
    ['unchanged', 10, 10, false],
    ['on -> off', 10, null, true],
    ['raising N', 10, 11, true],
    ['lowering N', 10, 5, false],
    ['off -> on below the grant', null, 5, false],
    ['off -> on equal to the grant', null, 10, false],
    ['off -> on above the grant lengthens access', null, 11, true],
    ['lowering N while still above the grant is free', 60, 30, false],
  ])('%s', (_name, before, after, weakened) => {
    expect(isRepeatPauseWeakening(before, after, TEMP)).toBe(weakened);
  });
});

describe('pauseGrantMinutes', () => {
  it('is the repeat while one is in force and the temp allow otherwise', () => {
    expect(pauseGrantMinutes(25, 10)).toBe(25);
    expect(pauseGrantMinutes(3, 10)).toBe(3);
    expect(pauseGrantMinutes(null, 10)).toBe(10);
  });
});

describe('formatTimeLeft', () => {
  it.each([
    [0, '0:00'],
    [-500, '0:00'],
    [1, '0:01'],
    [1000, '0:01'],
    [1001, '0:02'],
    [59_000, '0:59'],
    [60_000, '1:00'],
    [572_000, '9:32'],
    [7_200_000, '120:00'],
  ])('%sms reads %s', (ms, text) => {
    expect(formatTimeLeft(ms)).toBe(text);
  });

  it('builds the exact pill line', () => {
    expect(nextPauseLabel(572_000)).toBe('Next pause in 9:32');
  });
});

describe('repeatPauseSentence', () => {
  it('says it plainly, with a correct singular', () => {
    expect(repeatPauseSentence(10)).toBe('Pauses again every 10 minutes');
    expect(repeatPauseSentence(1)).toBe('Pauses again every minute');
  });
});
