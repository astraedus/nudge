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
import { emptyDayUsage } from '../core/stats';
import type { DayUsage, UsageByDay } from '../core/protocol';

const SETTINGS_KEY = 'nudge:settings';
const USAGE_PREFIX = 'usage:';
const PASS_LEDGER_KEY = 'nudge:passLedger';
const TRACKER_STATE_KEY = 'nudge:tracker';
const TEMP_ALLOW_KEY = 'nudge:tempAllow';

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

export async function loadDay(dayKey: string): Promise<Record<string, DayUsage>> {
  const stored = await chrome.storage.local.get(usageKey(dayKey));
  const day = stored[usageKey(dayKey)];
  return day && typeof day === 'object' ? (day as Record<string, DayUsage>) : {};
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

/** Active seconds spent on `domain` today. */
export async function todayUsageMs(domain: string, now: Date): Promise<number> {
  const day = await loadDay(localDayKey(now));
  return (day[domain]?.activeSec ?? 0) * 1000;
}

/** Load every stored day, newest keys included. Used by the dashboard. */
export async function loadAllUsage(): Promise<UsageByDay> {
  const all = await chrome.storage.local.get(null);
  const usage: UsageByDay = {};
  for (const [key, value] of Object.entries(all)) {
    if (key.startsWith(USAGE_PREFIX) && value && typeof value === 'object') {
      usage[key.slice(USAGE_PREFIX.length)] = value as Record<string, DayUsage>;
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

/** What the tracker was counting when it last woke. Ephemeral by design. */
export interface TrackerState {
  domain: string | null;
  since: number;
}

export async function loadTrackerState(): Promise<TrackerState> {
  const stored = await chrome.storage.session.get(TRACKER_STATE_KEY);
  const state = stored[TRACKER_STATE_KEY];
  if (state && typeof state === 'object' && typeof (state as TrackerState).since === 'number') {
    return state as TrackerState;
  }
  return { domain: null, since: Date.now() };
}

export async function saveTrackerState(state: TrackerState): Promise<void> {
  await chrome.storage.session.set({ [TRACKER_STATE_KEY]: state });
}

/**
 * Which valve opened a temporary grant.
 *
 * PAUSE is access earned by completing a Delay/Breathing pause; it sits BELOW a Lights Off
 * lockdown, so a grant taken out at 21:58 stops meaning anything at 22:00. EMERGENCY is the
 * daily 2-minute Escape Hatch — the one grant that outranks a lockdown (Anti's locked
 * decision). The tier picks the DNR priority; see the ladder at the top of background/dnr.ts.
 */
export type GrantTier = 'PAUSE' | 'EMERGENCY';

export interface TempAllowEntry {
  /** Epoch ms the grant expires. */
  until: number;
  tier: GrantTier;
}

/** domain -> live grant. */
export type TempAllowMap = Record<string, TempAllowEntry>;

/**
 * Read the grant map, normalizing whatever is there.
 *
 * Tolerates the pre-tier shape (a bare epoch-ms number per domain) by reading it as a PAUSE
 * grant. That shape can only survive an extension reload inside a single browser session —
 * session storage dies with the browser — but a grant map that threw or silently emptied on
 * read would either break enforcement or hand out free access, so it is coerced the way
 * settings are.
 */
export async function loadTempAllow(): Promise<TempAllowMap> {
  const stored = await chrome.storage.session.get(TEMP_ALLOW_KEY);
  const raw = stored[TEMP_ALLOW_KEY];
  if (!raw || typeof raw !== 'object') return {};

  const map: TempAllowMap = {};
  for (const [domain, value] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof value === 'number') {
      if (Number.isFinite(value)) map[domain] = { until: value, tier: 'PAUSE' };
      continue;
    }
    if (value === null || typeof value !== 'object') continue;
    const entry = value as Partial<TempAllowEntry>;
    if (typeof entry.until !== 'number' || !Number.isFinite(entry.until)) continue;
    map[domain] = {
      until: entry.until,
      // An unknown or absent tier falls back to PAUSE, the WEAKER grant. Guessing EMERGENCY
      // would hand a corrupt entry the one privilege that punches through a lockdown.
      tier: entry.tier === 'EMERGENCY' ? 'EMERGENCY' : 'PAUSE',
    };
  }
  return map;
}

export async function saveTempAllow(map: TempAllowMap): Promise<void> {
  await chrome.storage.session.set({ [TEMP_ALLOW_KEY]: map });
}
