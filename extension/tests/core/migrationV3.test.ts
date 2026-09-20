import { describe, expect, it } from 'vitest';

import {
  SCHEMA_VERSION,
  migrateSettings,
  type NudgeSettings,
  type SiteRule,
} from '../../src/core/settingsSchema';

/**
 * v2 -> v3: the top-level `youtube` block folds into the youtube.com rule.
 *
 * This is the first migration that MOVES data rather than only adding fields, and v0.1.0
 * is live on the Chrome Web Store — so every one of these cases is a real user's settings
 * surviving an auto-update. The bar is "never weakens protection and never silently loses
 * something the user configured", not "the new shape parses".
 */

/** A realistic v2 blob with every YouTube feature switched on. */
function v2WithEverythingOn(rules: unknown[] = []): Record<string, unknown> {
  return {
    schemaVersion: 2,
    globalEnabled: true,
    onboardingComplete: true,
    rules,
    messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
    strictMode: { enabled: false, challengeLength: 24 },
    emergencyPass: { enabled: true },
    tempAllowMinutes: 10,
    youtube: {
      shortsMode: 'HARD_BLOCK',
      hideShortsShelf: true,
      shortsDelaySeconds: 25,
      channelMode: 'WHITELIST',
      channels: [
        { channelId: 'UCveritasium000000000001', handle: 'veritasium', displayName: 'Veritasium', addedAt: 1 },
      ],
      channelBlockMode: 'HARD_BLOCK',
      channelDelaySeconds: 40,
      grayScreen: true,
      hideHomeFeed: true,
      hideSidebarRecs: true,
      hideEndScreen: true,
      hideComments: true,
      disableAutoplay: true,
    },
  };
}

function ruleFor(migrated: NudgeSettings, domain: string): SiteRule {
  const rule = migrated.rules.find((r) => r.domain === domain);
  if (rule === undefined) throw new Error(`expected a ${domain} rule after migration`);
  return rule;
}

describe('v2 -> v3 with NO existing youtube.com rule', () => {
  it('creates an ALLOW rule so nothing that was on becomes off', () => {
    const migrated = migrateSettings(v2WithEverythingOn());
    const rule = ruleFor(migrated, 'youtube.com');
    // ALLOW, because the user never asked for YouTube itself to be blocked — they only
    // configured features. Blocking the whole site here would be a silent strengthening
    // just as damaging as a silent weakening: it breaks a site they were using.
    expect(rule.mode).toBe('ALLOW');
    expect(rule.enabled).toBe(true);
  });

  it('carries every YouTube feature across', () => {
    const rule = ruleFor(migrateSettings(v2WithEverythingOn()), 'youtube.com');

    expect(rule.grayscale).toBe(true);
    expect(rule.features?.platform).toBe('youtube');
    expect(rule.features?.gates.shorts).toMatchObject({
      mode: 'HARD_BLOCK',
      delaySeconds: 25,
    });
    expect(rule.features?.hides).toMatchObject({
      shortsShelf: true,
      homeFeed: true,
      sidebarRecs: true,
      endScreen: true,
      comments: true,
    });
    expect(rule.features?.youtube).toMatchObject({
      channelMode: 'WHITELIST',
      channelBlockMode: 'HARD_BLOCK',
      channelDelaySeconds: 40,
      disableAutoplay: true,
    });
    expect(rule.features?.youtube?.channels).toHaveLength(1);
    expect(rule.features?.youtube?.channels[0]?.handle).toBe('veritasium');
  });

  it('creates no rule at all when the user had every YouTube feature off', () => {
    const blob = v2WithEverythingOn();
    blob.youtube = {
      shortsMode: 'INHERIT',
      hideShortsShelf: false,
      shortsDelaySeconds: 15,
      channelMode: 'OFF',
      channels: [],
      channelBlockMode: 'DELAY',
      channelDelaySeconds: 15,
      grayScreen: false,
      hideHomeFeed: false,
      hideSidebarRecs: false,
      hideEndScreen: false,
      hideComments: false,
      disableAutoplay: false,
    };
    // There was no YouTube protection to preserve, so inventing a rule would put a row in
    // the user's settings list that they never created.
    expect(migrateSettings(blob).rules).toHaveLength(0);
  });

  it('drops the legacy top-level block once it has been folded', () => {
    const migrated = migrateSettings(v2WithEverythingOn()) as NudgeSettings & {
      youtube?: unknown;
    };
    expect(migrated.youtube).toBeUndefined();
    expect(migrated.schemaVersion).toBe(SCHEMA_VERSION);
  });
});

describe('v2 -> v3 WITH an existing youtube.com rule', () => {
  const existingRule = {
    id: 'rule-youtube.com',
    domain: 'youtube.com',
    mode: 'DELAY',
    delaySeconds: 30,
    dailyLimitMinutes: 45,
    enabled: true,
    createdAt: 1_700_000_000_000,
    showTimeRemaining: true,
    schedule: {
      enabled: true,
      days: [1, 2, 3, 4, 5],
      startMinute: 540,
      endMinute: 1020,
      mode: 'HARD_BLOCK',
      delaySeconds: 30,
    },
  };

  it('leaves the site rule itself completely untouched', () => {
    const rule = ruleFor(migrateSettings(v2WithEverythingOn([existingRule])), 'youtube.com');
    expect(rule).toMatchObject({
      id: 'rule-youtube.com',
      mode: 'DELAY',
      delaySeconds: 30,
      dailyLimitMinutes: 45,
      showTimeRemaining: true,
      createdAt: 1_700_000_000_000,
    });
    expect(rule.schedule).toMatchObject({ mode: 'HARD_BLOCK', startMinute: 540, endMinute: 1020 });
  });

  it('attaches the features to it rather than creating a second rule', () => {
    const migrated = migrateSettings(v2WithEverythingOn([existingRule]));
    expect(migrated.rules.filter((r) => r.domain === 'youtube.com')).toHaveLength(1);
    expect(ruleFor(migrated, 'youtube.com').features?.gates.shorts?.mode).toBe('HARD_BLOCK');
    expect(ruleFor(migrated, 'youtube.com').grayscale).toBe(true);
  });
});

describe("v2 -> v3: shortsMode 'INHERIT'", () => {
  it('becomes an OFF gate, because the site rule now covers the whole site', () => {
    const blob = v2WithEverythingOn();
    (blob.youtube as Record<string, unknown>).shortsMode = 'INHERIT';
    const rule = ruleFor(migrateSettings(blob), 'youtube.com');
    // 'INHERIT' meant "defer to the youtube.com rule". In v3 the rule applies to /shorts/
    // by construction, so the gate adds nothing and the user loses no protection.
    expect(rule.features?.gates.shorts?.mode).toBe('OFF');
  });
});

describe('v2 -> v3: the migration is total and non-weakening', () => {
  it('is idempotent — running it twice changes nothing', () => {
    const once = migrateSettings(v2WithEverythingOn());
    expect(migrateSettings(once)).toEqual(once);
  });

  it('round-trips a v3 blob unchanged', () => {
    const v3 = migrateSettings(v2WithEverythingOn());
    expect(migrateSettings(JSON.parse(JSON.stringify(v3)))).toEqual(v3);
  });

  it('never turns a feature off when a v3 rule and a v2 block are both present', () => {
    // The shape a sync conflict produces: one browser already upgraded and wrote v3
    // features, another is still writing the v2 block. Neither side may lose.
    const v3Rule = {
      ...migrateSettings(v2WithEverythingOn()).rules[0],
      features: {
        platform: 'youtube',
        gates: { shorts: { mode: 'BREATHING', delaySeconds: 5, dailyLimitMinutes: 90 } },
        hides: { comments: false, homeFeed: true },
        youtube: {
          channelMode: 'OFF',
          channels: [
            { channelId: null, handle: 'kurzgesagt', displayName: 'Kurzgesagt', addedAt: 2 },
          ],
          channelBlockMode: 'BREATHING',
          channelDelaySeconds: 5,
          disableAutoplay: false,
        },
      },
      grayscale: false,
    };

    const rule = ruleFor(migrateSettings(v2WithEverythingOn([v3Rule])), 'youtube.com');
    // Stronger gate mode wins; the tighter budget survives; every hide that was on stays on.
    expect(rule.features?.gates.shorts?.mode).toBe('HARD_BLOCK');
    expect(rule.features?.gates.shorts?.dailyLimitMinutes).toBe(90);
    expect(rule.features?.hides.comments).toBe(true);
    expect(rule.features?.hides.homeFeed).toBe(true);
    expect(rule.grayscale).toBe(true);
    expect(rule.features?.youtube?.channelMode).toBe('WHITELIST');
    expect(rule.features?.youtube?.disableAutoplay).toBe(true);
    // Both channel lists survive — a list is data the user typed, not a stance to choose
    // between, and dropping half of a whitelist silently blocks channels they allowed.
    expect(rule.features?.youtube?.channels.map((c) => c.handle).sort()).toEqual([
      'kurzgesagt',
      'veritasium',
    ]);
  });
});

describe('v3 features validation', () => {
  it('drops a gate or hide id the platform does not define', () => {
    const blob = v2WithEverythingOn([
      {
        id: 'r', domain: 'youtube.com', mode: 'ALLOW', delaySeconds: 15,
        dailyLimitMinutes: null, enabled: true, createdAt: 0, showTimeRemaining: false,
        schedule: null, grayscale: false,
        features: {
          platform: 'youtube',
          // 'reels' is Instagram's; 'trends' is X's. A stale key must not resurrect a
          // surface this platform's content script has no idea how to enforce.
          gates: { reels: { mode: 'HARD_BLOCK', delaySeconds: 15, dailyLimitMinutes: null } },
          hides: { trends: true },
        },
      },
    ]);
    const features = ruleFor(migrateSettings(blob), 'youtube.com').features;
    expect(features?.gates).not.toHaveProperty('reels');
    expect(features?.hides).not.toHaveProperty('trends');
  });

  it('derives the platform from the domain, not from the stored object', () => {
    const blob = v2WithEverythingOn([
      {
        id: 'r', domain: 'instagram.com', mode: 'ALLOW', delaySeconds: 15,
        dailyLimitMinutes: null, enabled: true, createdAt: 0, showTimeRemaining: false,
        schedule: null, grayscale: false,
        features: { platform: 'youtube', gates: {}, hides: {} },
      },
    ]);
    // The network layer and the content script both act on the DOMAIN, so that is what the
    // settings have to describe.
    expect(ruleFor(migrateSettings(blob), 'instagram.com').features?.platform).toBe('instagram');
  });

  it('leaves an ordinary site with no features to configure', () => {
    const blob = v2WithEverythingOn([
      {
        id: 'r', domain: 'news.ycombinator.com', mode: 'HARD_BLOCK', delaySeconds: 15,
        dailyLimitMinutes: null, enabled: true, createdAt: 0, showTimeRemaining: false,
        schedule: null,
      },
    ]);
    expect(ruleFor(migrateSettings(blob), 'news.ycombinator.com').features).toBeNull();
  });

  it('materializes every surface of a known platform, all switched off', () => {
    const blob = v2WithEverythingOn([
      {
        id: 'r', domain: 'tiktok.com', mode: 'ALLOW', delaySeconds: 15,
        dailyLimitMinutes: null, enabled: true, createdAt: 0, showTimeRemaining: false,
        schedule: null,
      },
    ]);
    const features = ruleFor(migrateSettings(blob), 'tiktok.com').features;
    expect(Object.keys(features?.gates ?? {}).sort()).toEqual(['explore', 'foryou', 'live']);
    expect(Object.values(features?.gates ?? {}).every((g) => g.mode === 'OFF')).toBe(true);
    expect(Object.values(features?.hides ?? {}).every((h) => h === false)).toBe(true);
    // Channel lists are a YouTube-only concept.
    expect(features?.youtube).toBeUndefined();
  });
});
