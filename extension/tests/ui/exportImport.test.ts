import { describe, expect, it } from 'vitest';
import {
  buildExport,
  dedupeImportedRules,
  EXPORT_VERSION,
  parseImport,
} from '../../src/ui/exportImport';
import { DEFAULT_SETTINGS, structuredCloneSettings } from '../../src/core/settingsSchema';
import type { SiteRule } from '../../src/core/settingsSchema';

function makeRule(overrides: Partial<SiteRule> = {}): SiteRule {
  return {
    id: 'rule-youtube.com',
    domain: 'youtube.com',
    mode: 'DELAY',
    delaySeconds: 15,
    dailyLimitMinutes: 30,
    enabled: true,
    createdAt: 1700000000000,
    showTimeRemaining: true,
    schedule: null,
    ...overrides,
  };
}

describe('buildExport', () => {
  it('produces the version:1 / exportedAt / rules envelope', () => {
    const settings = structuredCloneSettings(DEFAULT_SETTINGS);
    settings.rules = [makeRule()];

    const exported = buildExport(settings);

    expect(exported.version).toBe(1);
    expect(exported.version).toBe(EXPORT_VERSION);
    expect(typeof exported.exportedAt).toBe('string');
    // Must be a real ISO timestamp, not a placeholder.
    expect(new Date(exported.exportedAt).toISOString()).toBe(exported.exportedAt);
    expect(exported.rules).toHaveLength(1);
    expect(exported.rules[0]!.domain).toBe('youtube.com');
  });

  it('deep-clones the schedule so mutating the export never touches settings', () => {
    const settings = structuredCloneSettings(DEFAULT_SETTINGS);
    settings.rules = [
      makeRule({
        schedule: {
          enabled: true,
          days: [1, 2, 3],
          startMinute: 60,
          endMinute: 120,
          mode: 'HARD_BLOCK',
          delaySeconds: 5,
        },
      }),
    ];

    const exported = buildExport(settings);
    exported.rules[0]!.schedule!.days!.push(7);

    expect(settings.rules[0]!.schedule!.days).toEqual([1, 2, 3]);
  });
});

describe('parseImport round trip', () => {
  it('round-trips a built export back into equivalent rules', () => {
    const settings = structuredCloneSettings(DEFAULT_SETTINGS);
    settings.rules = [
      makeRule({ domain: 'instagram.com', mode: 'BREATHING' }),
      makeRule({
        domain: 'x.com',
        schedule: {
          enabled: true,
          days: [6, 7],
          startMinute: 1380,
          endMinute: 360,
          mode: 'HARD_BLOCK',
          delaySeconds: 30,
        },
      }),
    ];

    const json = JSON.stringify(buildExport(settings));
    const result = parseImport(json);

    expect(result.ok).toBe(true);
    expect(result.rules).toHaveLength(2);
    expect(result.rules?.map((r) => r.domain).sort()).toEqual(['instagram.com', 'x.com']);
    const overnight = result.rules?.find((r) => r.domain === 'x.com');
    expect(overnight?.schedule?.startMinute).toBe(1380);
    expect(overnight?.schedule?.endMinute).toBe(360);
  });
});

describe('parseImport validation', () => {
  it('rejects malformed JSON without throwing', () => {
    expect(() => parseImport('{not valid json')).not.toThrow();
    const result = parseImport('{not valid json');
    expect(result.ok).toBe(false);
    expect(result.error).toBeTruthy();
  });

  it('rejects a non-object root (array) without throwing', () => {
    const result = parseImport('[1,2,3]');
    expect(result.ok).toBe(false);
    expect(result.error).toBeTruthy();
  });

  it('rejects a non-object root (primitive) without throwing', () => {
    const result = parseImport('"just a string"');
    expect(result.ok).toBe(false);
  });

  it('rejects a missing version field', () => {
    const result = parseImport(JSON.stringify({ exportedAt: new Date().toISOString(), rules: [] }));
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/version/i);
  });

  it('rejects a version newer than supported', () => {
    const result = parseImport(
      JSON.stringify({ version: EXPORT_VERSION + 1, exportedAt: new Date().toISOString(), rules: [] }),
    );
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/newer/i);
  });

  it('tolerates missing optional fields on a rule (older export)', () => {
    const result = parseImport(
      JSON.stringify({
        version: 1,
        exportedAt: new Date().toISOString(),
        rules: [{ domain: 'reddit.com', mode: 'DELAY' }],
      }),
    );
    expect(result.ok).toBe(true);
    expect(result.rules).toHaveLength(1);
    expect(result.rules?.[0]?.domain).toBe('reddit.com');
    expect(result.rules?.[0]?.dailyLimitMinutes).toBeNull();
    expect(result.rules?.[0]?.schedule).toBeNull();
    expect(result.rules?.[0]?.delaySeconds).toBe(15); // clamped default
  });

  it('drops garbage entries inside the rules array without throwing or failing the whole import', () => {
    const result = parseImport(
      JSON.stringify({
        version: 1,
        exportedAt: new Date().toISOString(),
        rules: [
          { domain: 'youtube.com', mode: 'DELAY' },
          null,
          42,
          'garbage',
          { mode: 'HARD_BLOCK' }, // missing domain
          { domain: '   ' }, // blank domain
          { domain: 'valid-two.com' },
        ],
      }),
    );
    expect(result.ok).toBe(true);
    expect(result.rules?.map((r) => r.domain).sort()).toEqual(['valid-two.com', 'youtube.com']);
  });

  it('clamps an out-of-range delaySeconds/dailyLimitMinutes instead of rejecting the rule', () => {
    const result = parseImport(
      JSON.stringify({
        version: 1,
        exportedAt: new Date().toISOString(),
        rules: [{ domain: 'clamp.com', delaySeconds: 99999, dailyLimitMinutes: -5 }],
      }),
    );
    expect(result.ok).toBe(true);
    expect(result.rules?.[0]?.delaySeconds).toBeLessThanOrEqual(300);
    expect(result.rules?.[0]?.dailyLimitMinutes).toBeGreaterThanOrEqual(1);
  });

  it('falls back an unknown mode string to HARD_BLOCK rather than throwing', () => {
    const result = parseImport(
      JSON.stringify({
        version: 1,
        exportedAt: new Date().toISOString(),
        rules: [{ domain: 'weird.com', mode: 'NOT_A_REAL_MODE' }],
      }),
    );
    expect(result.ok).toBe(true);
    expect(result.rules?.[0]?.mode).toBe('HARD_BLOCK');
  });
});

describe('dedupeImportedRules', () => {
  it('drops imported rules whose domain already exists', () => {
    const existing = [makeRule({ domain: 'youtube.com' })];
    const imported = [makeRule({ domain: 'youtube.com', mode: 'HARD_BLOCK' }), makeRule({ domain: 'reddit.com' })];

    const merged = dedupeImportedRules(existing, imported);

    expect(merged).toHaveLength(2);
    expect(merged.find((r) => r.domain === 'youtube.com')?.mode).toBe('DELAY'); // existing wins
    expect(merged.some((r) => r.domain === 'reddit.com')).toBe(true);
  });

  it('de-dupes within the imported batch itself, keeping the first occurrence', () => {
    const existing: SiteRule[] = [];
    const imported = [
      makeRule({ domain: 'dup.com', mode: 'DELAY' }),
      makeRule({ domain: 'dup.com', mode: 'BREATHING' }),
    ];

    const merged = dedupeImportedRules(existing, imported);

    expect(merged).toHaveLength(1);
    expect(merged[0]!.mode).toBe('DELAY');
  });

  it('is a no-op when there is nothing new to import', () => {
    const existing = [makeRule({ domain: 'a.com' }), makeRule({ domain: 'b.com' })];
    const merged = dedupeImportedRules(existing, []);
    expect(merged).toEqual(existing);
  });
});
