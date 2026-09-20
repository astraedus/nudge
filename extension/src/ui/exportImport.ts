/**
 * Settings/rules export-import. PURE — no chrome.* imports.
 *
 * Android parity (data/export/RuleExporter.kt + RuleExporterTest.kt): version:1 envelope,
 * `exportedAt`, tolerant field-by-field parsing that never throws, version-gate on import,
 * and de-duplication of imported rows against what's already there. The extension's `SiteRule`
 * is domain-keyed (not packageName-keyed) and has no app-groups concept at MVP, so the export
 * carries `rules: SiteRule[]` directly rather than mirroring Android's flattened row shape —
 * the PARITY that matters is the envelope shape + the defensive parsing discipline, not a
 * byte-identical field list.
 */

import { coerceRule, type NudgeSettings, type SiteRule } from '../core/settingsSchema';

export const EXPORT_VERSION = 1;

export interface NudgeRuleExport {
  version: number;
  exportedAt: string;
  rules: SiteRule[];
}

export interface ImportResult {
  ok: boolean;
  rules?: SiteRule[];
  error?: string;
}

/**
 * Parse one entry of the `rules` array. Returns null (never throws) for anything that
 * can't be salvaged into a usable rule — the caller filters nulls out, so one garbage
 * entry in a batch never poisons the rest of the import.
 */
function coerceImportedRule(value: unknown): SiteRule | null {
  // Delegated to the schema so an import understands every field the storage path does —
  // including `mode: 'ALLOW'`, `grayscale` and the whole `features` block. The only
  // import-specific behaviour is the fallback id/timestamp: a file may legitimately have
  // been hand-written without them, and two rules sharing the default id would collide.
  const rule = coerceRule(value);
  if (rule === null) return null;

  const raw = value as Record<string, unknown>;
  if (typeof raw.id !== 'string' || raw.id === '') {
    rule.id = `rule-${rule.domain}-${Date.now()}`;
  }
  if (typeof raw.createdAt !== 'number') rule.createdAt = Date.now();
  return rule;
}

/** Build the exportable envelope from current settings. */
export function buildExport(settings: NudgeSettings): NudgeRuleExport {
  return {
    version: EXPORT_VERSION,
    exportedAt: new Date().toISOString(),
    rules: settings.rules.map((r) => ({
      ...r,
      schedule: r.schedule ? { ...r.schedule, days: r.schedule.days ? [...r.schedule.days] : null } : null,
    })),
  };
}

/**
 * Parse + validate an arbitrary JSON string into a rules array.
 *
 * Total and defensive by design (same posture as Android's `migrateSettings`/`RuleExporter`):
 * malformed JSON, a non-object root, a wrong/missing version, and garbage rule entries are
 * all reported via `{ ok: false, error }` rather than thrown — a settings-import surface must
 * never crash the dashboard.
 */
export function parseImport(json: string): ImportResult {
  let root: unknown;
  try {
    root = JSON.parse(json);
  } catch (e) {
    return { ok: false, error: `Invalid JSON: ${e instanceof Error ? e.message : String(e)}` };
  }

  if (!root || typeof root !== 'object' || Array.isArray(root)) {
    return { ok: false, error: 'Import file is not a valid export object.' };
  }

  const raw = root as Record<string, unknown>;
  const version = typeof raw.version === 'number' ? raw.version : 0;
  if (version < 1) {
    return { ok: false, error: 'Missing or invalid version field.' };
  }
  if (version > EXPORT_VERSION) {
    return {
      ok: false,
      error: `Export version ${version} is newer than supported (${EXPORT_VERSION}). Please update the extension.`,
    };
  }

  const rulesRaw = Array.isArray(raw.rules) ? raw.rules : [];
  const rules = rulesRaw
    .map(coerceImportedRule)
    .filter((r): r is SiteRule => r !== null);

  return { ok: true, rules };
}

/**
 * De-duplicate imported rules against what's already stored, keyed by normalized domain
 * (one rule per domain — matches the SiteRule model). Existing rules win; new domains from
 * the import are appended.
 */
export function dedupeImportedRules(existing: SiteRule[], imported: SiteRule[]): SiteRule[] {
  const existingDomains = new Set(existing.map((r) => r.domain));
  const additions = imported.filter((r) => !existingDomains.has(r.domain));
  // De-dupe within the imported batch too, keeping the first occurrence.
  const seen = new Set<string>();
  const deduped: SiteRule[] = [];
  for (const rule of additions) {
    if (seen.has(rule.domain)) continue;
    seen.add(rule.domain);
    deduped.push(rule);
  }
  return [...existing, ...deduped];
}
