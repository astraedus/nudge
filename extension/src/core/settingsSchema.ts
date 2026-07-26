/**
 * The persisted settings shape (chrome.storage.sync) + defaults + migrations.
 *
 * PURE — no chrome.* imports. The storage adapter lives in src/background/storage.ts.
 *
 * Shapes are designed to become the `extension` JSONB namespace if optional account
 * sync ever ships (ext-06), hence `schemaVersion` from day one.
 * Usage/stats data NEVER lives here — it is storage.local only and never leaves the device.
 */

import type { BlockMode } from './types';

export const SCHEMA_VERSION = 1;

/** Delay presets, seconds (Android parity). Custom range 1–300. */
export const DELAY_PRESETS = [5, 15, 30, 60] as const;
export const DELAY_MIN_SECONDS = 1;
export const DELAY_MAX_SECONDS = 300;
export const DEFAULT_DELAY_SECONDS = 15;

/** Daily Time Limit presets, minutes (Android parity). Custom range 1–480. */
export const DAILY_LIMIT_PRESETS = [15, 30, 60, 120] as const;
export const DAILY_LIMIT_MIN_MINUTES = 1;
export const DAILY_LIMIT_MAX_MINUTES = 480;

/**
 * Temporary access granted after completing a Delay/Breathing pause — the browser
 * analog of Android's "the app opens". Per-origin, not per-tab (ext-08 fixed decision).
 */
export const TEMP_ALLOW_MIN_MINUTES = 1;
export const TEMP_ALLOW_MAX_MINUTES = 60;
export const DEFAULT_TEMP_ALLOW_MINUTES = 10;

/** Strict Mode challenge difficulty presets (raw characters to type). */
export const CHALLENGE_LENGTH_EASY = 12;
export const CHALLENGE_LENGTH_MEDIUM = 24;
export const CHALLENGE_LENGTH_HARD = 48;

/** A per-site rule. One row per site; the schedule override is nested, not a mirrored row. */
export interface SiteRule {
  id: string;
  /** Normalized base domain, e.g. "youtube.com". Matches www./m./mobile. automatically. */
  domain: string;
  mode: BlockMode;
  delaySeconds: number;
  dailyLimitMinutes: number | null;
  enabled: boolean;
  createdAt: number;
  /** Show remaining budget as badge text on the toolbar icon. */
  showTimeRemaining: boolean;
  /** "Scheduled Override" — an independent mode+delay inside the window. */
  schedule: ScheduleOverride | null;
}

/**
 * Scheduled Override. Inside the window the scheduled mode/delay REPLACES the rule's
 * default behavior; outside it the default applies.
 *
 * Overnight spans (end < start, e.g. 23:00–06:00) are supported — see scheduleEvaluator.
 */
export interface ScheduleOverride {
  enabled: boolean;
  /** ISO day numbers 1=Mon..7=Sun. null/empty = all days. */
  days: number[] | null;
  /** Minutes from local midnight, 0..1439. Both null = all day on the chosen days. */
  startMinute: number | null;
  endMinute: number | null;
  mode: BlockMode;
  delaySeconds: number;
}

/** Rotating message pools. Empty array = use the bundled Android defaults. */
export interface MessageSettings {
  delayTitles: string[];
  delaySubtitles: string[];
  hardBlockMessages: string[];
}

export interface StrictModeSettings {
  enabled: boolean;
  /** 12 (Easy) / 24 (Medium, default) / 48 (Hard). */
  challengeLength: number;
}

export interface EmergencyPassSettings {
  /** "Escape Hatch" — the daily 2-minute pass. Hidden entirely under Strict Mode. */
  enabled: boolean;
}

/** YouTube feature rule — the Android "Feature Rules" analog. */
export interface YoutubeSettings {
  /** 'INHERIT' defers to the site rule for youtube.com (if any). */
  shortsMode: 'INHERIT' | BlockMode;
  /** Hide the Shorts shelf/tab/cards across YouTube surfaces. */
  hideShortsShelf: boolean;
  shortsDelaySeconds: number;
}

export interface NudgeSettings {
  schemaVersion: number;
  /** Master toggle. Off = Nudge behaves as if uninstalled (Android v1.9.2 semantics). */
  globalEnabled: boolean;
  onboardingComplete: boolean;
  rules: SiteRule[];
  messages: MessageSettings;
  strictMode: StrictModeSettings;
  emergencyPass: EmergencyPassSettings;
  youtube: YoutubeSettings;
  tempAllowMinutes: number;
}

export const DEFAULT_SETTINGS: NudgeSettings = {
  schemaVersion: SCHEMA_VERSION,
  globalEnabled: true,
  onboardingComplete: false,
  rules: [],
  messages: { delayTitles: [], delaySubtitles: [], hardBlockMessages: [] },
  strictMode: { enabled: false, challengeLength: CHALLENGE_LENGTH_MEDIUM },
  emergencyPass: { enabled: true },
  youtube: {
    shortsMode: 'INHERIT',
    hideShortsShelf: false,
    shortsDelaySeconds: DEFAULT_DELAY_SECONDS,
  },
  tempAllowMinutes: DEFAULT_TEMP_ALLOW_MINUTES,
};

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, Math.round(value)));
}

const VALID_MODES: readonly BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];

function coerceMode(value: unknown, fallback: BlockMode): BlockMode {
  return VALID_MODES.includes(value as BlockMode) ? (value as BlockMode) : fallback;
}

function coerceStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((v): v is string => typeof v === 'string');
}

function coerceSchedule(value: unknown): ScheduleOverride | null {
  if (!value || typeof value !== 'object') return null;
  const raw = value as Partial<ScheduleOverride>;
  const days = Array.isArray(raw.days)
    ? raw.days.filter((d): d is number => Number.isInteger(d) && d >= 1 && d <= 7)
    : null;
  return {
    enabled: raw.enabled !== false,
    days: days && days.length > 0 ? days : null,
    startMinute:
      typeof raw.startMinute === 'number' ? clamp(raw.startMinute, 0, 1439) : null,
    endMinute: typeof raw.endMinute === 'number' ? clamp(raw.endMinute, 0, 1439) : null,
    mode: coerceMode(raw.mode, 'HARD_BLOCK'),
    delaySeconds: clamp(
      typeof raw.delaySeconds === 'number' ? raw.delaySeconds : DEFAULT_DELAY_SECONDS,
      DELAY_MIN_SECONDS,
      DELAY_MAX_SECONDS,
    ),
  };
}

function coerceRule(value: unknown): SiteRule | null {
  if (!value || typeof value !== 'object') return null;
  const raw = value as Partial<SiteRule>;
  if (typeof raw.domain !== 'string' || raw.domain.trim() === '') return null;
  return {
    id: typeof raw.id === 'string' && raw.id ? raw.id : `rule-${raw.domain}`,
    domain: raw.domain.trim().toLowerCase(),
    mode: coerceMode(raw.mode, 'HARD_BLOCK'),
    delaySeconds: clamp(
      typeof raw.delaySeconds === 'number' ? raw.delaySeconds : DEFAULT_DELAY_SECONDS,
      DELAY_MIN_SECONDS,
      DELAY_MAX_SECONDS,
    ),
    dailyLimitMinutes:
      typeof raw.dailyLimitMinutes === 'number'
        ? clamp(raw.dailyLimitMinutes, DAILY_LIMIT_MIN_MINUTES, DAILY_LIMIT_MAX_MINUTES)
        : null,
    enabled: raw.enabled !== false,
    createdAt: typeof raw.createdAt === 'number' ? raw.createdAt : 0,
    showTimeRemaining: raw.showTimeRemaining === true,
    schedule: coerceSchedule(raw.schedule),
  };
}

/**
 * Normalize arbitrary persisted data into a valid `NudgeSettings`.
 *
 * Deliberately total and lenient: settings come from storage that may have been written
 * by an older version, hand-edited via import, or corrupted. Anything unrecognized falls
 * back to a default rather than throwing — a settings read must never break the block path.
 *
 * Failing toward the DEFAULTS is also the safe direction: defaults enforce (globalEnabled
 * true), so corruption can never silently disable protection.
 */
export function migrateSettings(raw: unknown): NudgeSettings {
  if (!raw || typeof raw !== 'object') return structuredCloneSettings(DEFAULT_SETTINGS);
  const input = raw as Partial<NudgeSettings>;

  const rules = Array.isArray(input.rules)
    ? input.rules.map(coerceRule).filter((r): r is SiteRule => r !== null)
    : [];

  const messages = (input.messages ?? {}) as Partial<MessageSettings>;
  const strict = (input.strictMode ?? {}) as Partial<StrictModeSettings>;
  const pass = (input.emergencyPass ?? {}) as Partial<EmergencyPassSettings>;
  const yt = (input.youtube ?? {}) as Partial<YoutubeSettings>;

  return {
    schemaVersion: SCHEMA_VERSION,
    globalEnabled: input.globalEnabled !== false,
    onboardingComplete: input.onboardingComplete === true,
    rules,
    messages: {
      delayTitles: coerceStringArray(messages.delayTitles),
      delaySubtitles: coerceStringArray(messages.delaySubtitles),
      hardBlockMessages: coerceStringArray(messages.hardBlockMessages),
    },
    strictMode: {
      enabled: strict.enabled === true,
      challengeLength:
        typeof strict.challengeLength === 'number'
          ? clamp(strict.challengeLength, 1, 256)
          : CHALLENGE_LENGTH_MEDIUM,
    },
    emergencyPass: { enabled: pass.enabled !== false },
    youtube: {
      shortsMode:
        yt.shortsMode === 'INHERIT' ? 'INHERIT' : coerceMode(yt.shortsMode, 'INHERIT'),
      hideShortsShelf: yt.hideShortsShelf === true,
      shortsDelaySeconds: clamp(
        typeof yt.shortsDelaySeconds === 'number'
          ? yt.shortsDelaySeconds
          : DEFAULT_DELAY_SECONDS,
        DELAY_MIN_SECONDS,
        DELAY_MAX_SECONDS,
      ),
    },
    tempAllowMinutes: clamp(
      typeof input.tempAllowMinutes === 'number'
        ? input.tempAllowMinutes
        : DEFAULT_TEMP_ALLOW_MINUTES,
      TEMP_ALLOW_MIN_MINUTES,
      TEMP_ALLOW_MAX_MINUTES,
    ),
  };
}

function structuredCloneSettings(settings: NudgeSettings): NudgeSettings {
  return JSON.parse(JSON.stringify(settings)) as NudgeSettings;
}

export { structuredCloneSettings };
