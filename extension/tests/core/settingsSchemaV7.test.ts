import { describe, expect, it } from 'vitest';

import { REPEAT_PAUSE_MAX_MINUTES } from '../../src/core/repeatPause';
import {
  SCHEMA_VERSION,
  coerceRule,
  migrateSettings,
  newSiteRule,
} from '../../src/core/settingsSchema';
import * as strict from '../../src/core/strictMode';
import { settings, siteRule } from '../helpers/rules';

/**
 * v6 -> v7: repeating pause. A v6 blob has no `repeatPauseMinutes` and must come out OFF: an
 * upgrade never changes how long a pause opens a site for.
 */

function v6Blob(rule: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schemaVersion: 6,
    globalEnabled: true,
    onboardingComplete: true,
    rules: [
      {
        id: 'r1',
        domain: 'example.com',
        mode: 'DELAY',
        delaySeconds: 15,
        dailyLimitMinutes: null,
        enabled: true,
        createdAt: 1,
        ...rule,
      },
    ],
    messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
    strictMode: { enabled: false, challengeLength: 24 },
    emergencyPass: { enabled: true },
    tempAllowMinutes: 10,
  };
}

describe('settings schema v7', () => {
  it('stamps the current version', () => {
    expect(SCHEMA_VERSION).toBeGreaterThanOrEqual(7);
    expect(migrateSettings(v6Blob()).schemaVersion).toBe(SCHEMA_VERSION);
  });

  it('migrates a v6 rule with the repeat switched off', () => {
    expect(migrateSettings(v6Blob()).rules[0]?.repeatPauseMinutes).toBeNull();
  });

  it('keeps a stored value through a migration and is idempotent', () => {
    const once = migrateSettings(v6Blob({ repeatPauseMinutes: 15 }));
    expect(once.rules[0]?.repeatPauseMinutes).toBe(15);
    expect(migrateSettings(once).rules[0]?.repeatPauseMinutes).toBe(15);
  });

  it.each([
    ['ten', null],
    [null, null],
    [0, 1],
    [500, REPEAT_PAUSE_MAX_MINUTES],
    [30, 30],
  ])('coerceRule reads %s as %s', (stored, expected) => {
    expect(coerceRule({ domain: 'example.com', repeatPauseMinutes: stored })?.repeatPauseMinutes).toBe(
      expected,
    );
  });

  it('a new rule starts off', () => {
    expect(newSiteRule({ domain: 'example.com', mode: 'DELAY' }).repeatPauseMinutes).toBeNull();
  });

  it('stays stored under a mode where it is inert, so a mode change does not lose it', () => {
    const rule = coerceRule({ domain: 'example.com', mode: 'ALLOW', repeatPauseMinutes: 20 });
    expect(rule?.repeatPauseMinutes).toBe(20);
  });
});

describe('Commitment Lock over the repeat', () => {
  const withRepeat = (minutes: number | null, tempAllowMinutes = 10) =>
    settings({
      tempAllowMinutes,
      rules: [siteRule({ id: 'r', domain: 'example.com', mode: 'DELAY', repeatPauseMinutes: minutes })],
    });

  it.each<[string, number | null, number | null, boolean]>([
    ['on -> off', 10, null, true],
    ['raising N', 10, 30, true],
    ['lowering N', 30, 10, false],
    ['off -> on at or under the grant', null, 10, false],
    ['off -> on past the grant', null, 11, true],
    ['unchanged', 15, 15, false],
  ])('%s', (_name, before, after, weakened) => {
    expect(strict.isWeakening(withRepeat(before), withRepeat(after))).toBe(weakened);
  });

  it('judges a stale save that drops the repeat exactly like a deliberate one', () => {
    // A dashboard tab opened before the block page's chooser set the value saves its whole
    // settings object, with the old null. That is an off under the lock, not a no-op.
    expect(strict.isWeakening(withRepeat(10), withRepeat(null))).toBe(true);
  });

  it('compares a first setting against the NEW temp allow, the grant it actually replaces', () => {
    // Raising tempAllowMinutes is itself gated, so lowering it in the same save must not
    // make a long repeat look free.
    expect(strict.isWeakening(withRepeat(null, 30), withRepeat(20, 10))).toBe(true);
  });
});
