import { describe, expect, it } from 'vitest';
import {
  DEFAULT_DELAY_SUBTITLES,
  DEFAULT_DELAY_TITLES,
  DEFAULT_HARD_BLOCK_MESSAGES,
  pickRandom,
  resolvePool,
} from '../../src/core/messages';

describe('messages: default pool contents', () => {
  it('DEFAULT_DELAY_TITLES matches the Android defaults exactly', () => {
    expect(DEFAULT_DELAY_TITLES).toEqual([
      'Take a moment to think...',
      'Pause and reflect',
      'Is this intentional?',
      'Before you scroll...',
      'A moment of awareness',
    ]);
  });

  it('DEFAULT_DELAY_SUBTITLES matches the Android defaults, with the one app->site swap', () => {
    expect(DEFAULT_DELAY_SUBTITLES).toEqual([
      'Do you really need to open this site right now?',
      'What were you about to do instead?',
      'Will this bring you closer to your goals?',
      'You chose to add friction here for a reason.',
      'This is your future self thanking you.',
    ]);
  });

  it('DEFAULT_HARD_BLOCK_MESSAGES matches the Android defaults, with the two app->site swaps', () => {
    expect(DEFAULT_HARD_BLOCK_MESSAGES).toEqual([
      "You've blocked access to this site",
      'This site is off-limits right now',
      'Your future self will thank you',
      'Time to do something else',
      'You set this boundary for a reason',
    ]);
  });

  it('every default pool is non-empty', () => {
    expect(DEFAULT_DELAY_TITLES.length).toBeGreaterThan(0);
    expect(DEFAULT_DELAY_SUBTITLES.length).toBeGreaterThan(0);
    expect(DEFAULT_HARD_BLOCK_MESSAGES.length).toBeGreaterThan(0);
  });

  it('none of the default pools contain the word "app" (browser-appropriate copy)', () => {
    for (const pool of [DEFAULT_DELAY_TITLES, DEFAULT_DELAY_SUBTITLES, DEFAULT_HARD_BLOCK_MESSAGES]) {
      for (const message of pool) {
        expect(message.toLowerCase()).not.toContain('app');
      }
    }
  });
});

describe('messages: resolvePool() — string input', () => {
  const defaults = ['Default A', 'Default B'];

  it('falls back to defaults when custom is an empty string', () => {
    expect(resolvePool('', defaults)).toEqual(defaults);
  });

  it('falls back to defaults when custom is blank (whitespace only)', () => {
    expect(resolvePool('   ', defaults)).toEqual(defaults);
  });

  it('falls back to defaults when custom is only newlines and whitespace', () => {
    expect(resolvePool('\n  \n\t\n', defaults)).toEqual(defaults);
  });

  it('parses multiline custom text into trimmed lines', () => {
    const custom = 'First message\nSecond message\nThird message';
    expect(resolvePool(custom, defaults)).toEqual([
      'First message',
      'Second message',
      'Third message',
    ]);
  });

  it('handles a single line', () => {
    expect(resolvePool('Only one', defaults)).toEqual(['Only one']);
  });

  it('trims surrounding whitespace on each line', () => {
    const custom = '  leading\ntrailing  \n  both  ';
    expect(resolvePool(custom, defaults)).toEqual(['leading', 'trailing', 'both']);
  });

  it('drops blank lines mixed among real ones', () => {
    const custom = 'First\n\n   \nSecond\n\t\nThird\n';
    expect(resolvePool(custom, defaults)).toEqual(['First', 'Second', 'Third']);
  });
});

describe('messages: resolvePool() — array input', () => {
  const defaults = ['Default A', 'Default B'];

  it('falls back to defaults when custom is an empty array', () => {
    expect(resolvePool([], defaults)).toEqual(defaults);
  });

  it('falls back to defaults when custom is an array of blank strings', () => {
    expect(resolvePool(['', '   ', '\t'], defaults)).toEqual(defaults);
  });

  it('trims and keeps non-blank array entries, dropping blanks mixed among them', () => {
    expect(resolvePool([' First ', '', 'Second', '   '], defaults)).toEqual(['First', 'Second']);
  });

  it('an already-clean array round-trips unchanged', () => {
    expect(resolvePool(['One', 'Two', 'Three'], defaults)).toEqual(['One', 'Two', 'Three']);
  });
});

describe('messages: pickRandom()', () => {
  it('returns an element that belongs to the pool (default Math.random)', () => {
    const pool = ['a', 'b', 'c'];
    for (let i = 0; i < 20; i++) {
      expect(pool).toContain(pickRandom(pool));
    }
  });

  it('is deterministic with an injected rng returning 0 -> first element', () => {
    const pool = ['first', 'second', 'third'];
    expect(pickRandom(pool, () => 0)).toBe('first');
  });

  it('is deterministic with an injected rng returning just under 1 -> last element', () => {
    const pool = ['first', 'second', 'third'];
    expect(pickRandom(pool, () => 0.999999)).toBe('third');
  });

  it('is deterministic with an injected rng landing mid-pool', () => {
    const pool = ['first', 'second', 'third', 'fourth'];
    // 0.5 * 4 = 2 -> index 2 -> 'third'.
    expect(pickRandom(pool, () => 0.5)).toBe('third');
  });

  it('clamps a misbehaving rng that returns exactly 1', () => {
    const pool = ['first', 'second', 'third'];
    expect(pickRandom(pool, () => 1)).toBe('third');
  });

  it('throws on an empty pool rather than returning undefined', () => {
    expect(() => pickRandom([])).toThrow(RangeError);
  });

  it('works over the real default message pools', () => {
    const message = pickRandom(DEFAULT_DELAY_TITLES);
    expect(DEFAULT_DELAY_TITLES).toContain(message);
  });
});
