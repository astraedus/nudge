/**
 * Persistence adapter. The ONLY module that talks to chrome.storage.
 *
 * Three tiers, deliberately separated (ext-08 Data):
 *  - settings  -> storage.sync   (~100KB, free cross-device sync via the user's Chrome
 *                                 account; falls back to local when sync is unavailable)
 *  - usage     -> storage.local  (+ unlimitedStorage). NEVER syncs, never leaves the device.
 *  - ephemeral -> storage.session (in-memory, dies with the browser — which is a feature
 *                                 for temp-allow grants: a crash can't leave a site unlocked)
 */

import { migrateSettings, type NudgeSettings } from '../core/settingsSchema';
import { localDayKey } from '../core/scheduleEvaluator';
import { coerceDayUsage, emptyDayUsage } from '../core/stats';
import { coerceSeenItems, emptySeenItems, type SeenItems } from '../core/seenItems';
import type { DayUsage, UsageByDay, UsageSnapshot } from '../core/protocol';

const SETTINGS_KEY = 'nudge:settings';
const USAGE_PREFIX = 'usage:';
const PASS_LEDGER_KEY = 'nudge:passLedger';
const TRACKER_STATE_KEY = 'nudge:tracker';
const TEMP_ALLOW_KEY = 'nudge:tempAllow';
const SEEN_ITEMS_KEY = 'nudge:seenItems';

/** Settings live in sync; a sync failure (quota, sync disabled) degrades to local. */
export async function loadSettings(): Promise<NudgeSettings> {
  try {
    const synced = await chrome.storage.sync.get(SETTINGS_KEY);
    if (synced[SETTINGS_KEY] !== undefined) return migrateSettings(synced[SETTINGS_KEY]);
  } catch {
    // fall through to local
  }
  const local = await chrome.storage.local.get(SETTINGS_KEY);
  return migrateSettings(local[SETTINGS_KEY]);
}

export async function saveSettings(settings: NudgeSettings): Promise<void> {
  const normalized = migrateSettings(settings);
  // Always mirror to local so a later sync outage still reads current settings.
  await chrome.storage.local.set({ [SETTINGS_KEY]: normalized });
  try {
    await chrome.storage.sync.set({ [SETTINGS_KEY]: normalized });
  } catch {
    // Sync unavailable or over quota — local mirror above is authoritative.
  }
}

function usageKey(dayKey: string): string {
  return `${USAGE_PREFIX}${dayKey}`;
}

/**
 * One day's rollups, every entry normalized on read (`core/stats.ts#coerceDayUsage`).
 *
 * Usage data carries no schema version, so a field added in a later release is simply
 * missing from every rollup written before it. Repairing the shape HERE, in the one module
 * that reads storage, is what keeps that from becoming an arithmetic bug in each consumer:
 * `items` (v0.3) is absent on every v0.2 rollup, and `undefined + 1` is NaN, which is never
 * over any limit, so a count budget would silently never fire.
 */
export async function loadDay(dayKey: string): Promise<Record<string, DayUsage>> {
  const stored = await chrome.storage.local.get(usageKey(dayKey));
  const day = stored[usageKey(dayKey)];
  if (!day || typeof day !== 'object') return {};
  const normalized: Record<string, DayUsage> = {};
  for (const [key, rollup] of Object.entries(day as Record<string, unknown>)) {
    normalized[key] = coerceDayUsage(rollup);
  }
  return normalized;
}

export async function saveDay(
  dayKey: string,
  day: Record<string, DayUsage>,
): Promise<void> {
  await chrome.storage.local.set({ [usageKey(dayKey)]: day });
}

/**
 * Read-modify-write one domain's rollup for one day.
 *
 * Every accounting step is a complete atomic read-modify-write rather than an increment
 * against a value cached in a service-worker global — the worker can be torn down between
 * any two events, so nothing may be held in memory across them (ext-01 §2).
 */
export async function updateDomainUsage(
  dayKey: string,
  domain: string,
  mutate: (usage: DayUsage) => DayUsage,
): Promise<DayUsage> {
  const day = await loadDay(dayKey);
  const next = mutate(day[domain] ?? emptyDayUsage());
  day[domain] = next;
  await saveDay(dayKey, day);
  return next;
}

/**
 * Active milliseconds spent on one usage key today.
 *
 * The key is a base domain, or a `domain#gate` SURFACE key (`core/surfaceKeys.ts`) — both
 * live in the same day map because they are the same number measured over the same day.
 */
export async function todayUsageMs(key: string, now: Date): Promise<number> {
  const day = await loadDay(localDayKey(now));
  return (day[key]?.activeSec ?? 0) * 1000;
}

/** Items viewed today on one SURFACE key (`core/surfaceKeys.ts`). 0 when none. */
export async function todayItemCount(key: string, now: Date): Promise<number> {
  const day = await loadDay(localDayKey(now));
  return day[key]?.items ?? 0;
}

/**
 * Today's usage for EVERY key, sites and surfaces alike, on BOTH budget axes.
 *
 * The DNR compiler needs the whole thing at once: it has to answer "is this rule in force"
 * for every rule and "is this gate's budget spent" for every gate in a single pass, and
 * reading storage once per rule would turn a recompile into dozens of round trips.
 *
 * Both axes travel together in ONE snapshot rather than as two parameters. A compiler that
 * took them separately would have a call site that passed only the minutes, and that call
 * site would compile a rule set in which no count budget is ever spent: a count-only gate
 * that quietly never redirects, which is the exact failure mode this feature must not have.
 */
export async function todayUsageSnapshot(now: Date): Promise<UsageSnapshot> {
  const day = await loadDay(localDayKey(now));
  const ms: Record<string, number> = {};
  const counts: Record<string, number> = {};
  for (const [key, rollup] of Object.entries(day)) {
    ms[key] = rollup.activeSec * 1000;
    counts[key] = rollup.items;
  }
  return { ms, counts };
}

/**
 * Today's seen-item set, or an empty one when the stored blob belongs to another day.
 *
 * `storage.local`, like the rollups it de-duplicates for: it is usage data and must never
 * reach `storage.sync`, both because it would burn the sync quota and because a list of
 * every Short someone watched is exactly the kind of thing this product promises never
 * leaves the device.
 */
export async function loadSeenItems(now: Date): Promise<SeenItems> {
  const today = localDayKey(now);
  try {
    const stored = await chrome.storage.local.get(SEEN_ITEMS_KEY);
    return coerceSeenItems(stored[SEEN_ITEMS_KEY], today);
  } catch {
    return emptySeenItems(today);
  }
}

export async function saveSeenItems(state: SeenItems): Promise<void> {
  await chrome.storage.local.set({ [SEEN_ITEMS_KEY]: state });
}

/** Load every stored day, newest keys included. Used by the dashboard. */
export async function loadAllUsage(): Promise<UsageByDay> {
  const all = await chrome.storage.local.get(null);
  const usage: UsageByDay = {};
  for (const [key, value] of Object.entries(all)) {
    if (key.startsWith(USAGE_PREFIX) && value && typeof value === 'object') {
      const day: Record<string, DayUsage> = {};
      for (const [domain, rollup] of Object.entries(value as Record<string, unknown>)) {
        day[domain] = coerceDayUsage(rollup);
      }
      usage[key.slice(USAGE_PREFIX.length)] = day;
    }
  }
  return usage;
}

/** The emergency-pass ledger. Device-local: the lockout is per device, like Android's. */
export async function loadPassLedger(): Promise<string> {
  const stored = await chrome.storage.local.get(PASS_LEDGER_KEY);
  const raw = stored[PASS_LEDGER_KEY];
  return typeof raw === 'string' ? raw : '';
}

export async function savePassLedger(raw: string): Promise<void> {
  await chrome.storage.local.set({ [PASS_LEDGER_KEY]: raw });
}

/**
 * What the tracker was counting when it last woke. Ephemeral by design.
 *
 * `url` is carried alongside `domain` because a feature budget is measured per SURFACE
 * ("10 minutes of Shorts"), and which surface a tab is on is a property of its path, not
 * its host. Re-reading the tab at accounting time would answer for where the user is NOW,
 * not for the interval being closed out — so the URL that earned the time has to be the
 * one stored when the interval opened.
 */
export interface TrackerState {
  domain: string | null;
  /** The full URL that was focused, or null when nothing trackable was. */
  url: string | null;
  since: number;
}

export async function loadTrackerState(): Promise<TrackerState> {
  const stored = await chrome.storage.session.get(TRACKER_STATE_KEY);
  const state = stored[TRACKER_STATE_KEY];
  if (state && typeof state === 'object' && typeof (state as TrackerState).since === 'number') {
    const known = state as Partial<TrackerState>;
    return {
      domain: typeof known.domain === 'string' ? known.domain : null,
      url: typeof known.url === 'string' ? known.url : null,
      since: known.since as number,
    };
  }
  return { domain: null, url: null, since: Date.now() };
}

export async function saveTrackerState(state: TrackerState): Promise<void> {
  await chrome.storage.session.set({ [TRACKER_STATE_KEY]: state });
}

/** domain -> epoch ms the temporary grant expires. */
export type TempAllowMap = Record<string, number>;

export async function loadTempAllow(): Promise<TempAllowMap> {
  const stored = await chrome.storage.session.get(TEMP_ALLOW_KEY);
  const map = stored[TEMP_ALLOW_KEY];
  return map && typeof map === 'object' ? (map as TempAllowMap) : {};
}

export async function saveTempAllow(map: TempAllowMap): Promise<void> {
  await chrome.storage.session.set({ [TEMP_ALLOW_KEY]: map });
}
