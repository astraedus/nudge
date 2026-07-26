/**
 * The persisted settings shape (chrome.storage.sync) + defaults + migrations.
 *
 * PURE — no chrome.* imports. The storage adapter lives in src/background/storage.ts.
 *
 * Shapes are designed to become the `extension` JSONB namespace if optional account
 * sync ever ships (ext-06), hence `schemaVersion` from day one.
 * Usage/stats data NEVER lives here — it is storage.local only and never leaves the device.
 */

import { normalizeAllowedDomain } from './domainMatcher';
import type { BlockMode } from './types';

/**
 * 1 -> 2 (Phase 4): YouTube channel lists, gray-screen mode, and the Unhook-parity hide
 * toggles.
 * 2 -> 3 (Phase 5): Lights Off — the scheduled global lockdown.
 *
 * Purely ADDITIVE, and every new default is "off", so a v1 user's protection is neither
 * weakened nor silently strengthened by the upgrade. `migrateSettings` is the ONLY migration
 * path and is already total — it rebuilds every field from whatever it is handed — so there
 * is no per-version branch to keep in sync as the schema grows.
 */
export const SCHEMA_VERSION = 3;

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

/**
 * How the channel list is interpreted. The list itself is one array either way — the mode
 * decides whether being ON it means "block this" or "this is the only thing allowed".
 */
export type ChannelListMode = 'OFF' | 'BLACKLIST' | 'WHITELIST';

/**
 * One channel in the list.
 *
 * BOTH identifiers are stored and EITHER matches. They are captured from different places
 * and neither is always available: a feed card usually exposes only `/@handle`, while the
 * watch page's player response gives the canonical `UCxxxx` id. Storing one and hoping is
 * how a whitelist silently fails to recognise a channel the user added.
 */
export interface ChannelEntry {
  /** Canonical channel id, `UCxxxx…`. null when the user supplied only a handle. */
  channelId: string | null;
  /** Handle WITHOUT the leading '@', lowercased. null when only an id is known. */
  handle: string | null;
  /** What the UI shows. Falls back to the handle or id when YouTube never told us a name. */
  displayName: string;
  addedAt: number;
}

/** YouTube feature rules — the Android "Feature Rules" analog, plus the v1.1 additions. */
export interface YoutubeSettings {
  /** 'INHERIT' defers to the site rule for youtube.com (if any). */
  shortsMode: 'INHERIT' | BlockMode;
  /** Hide the Shorts shelf/tab/cards across YouTube surfaces. */
  hideShortsShelf: boolean;
  shortsDelaySeconds: number;

  // --- v1.1: channel lists ---
  channelMode: ChannelListMode;
  channels: ChannelEntry[];
  /** The block mode applied to a channel the list disallows. */
  channelBlockMode: BlockMode;
  channelDelaySeconds: number;

  // --- v1.1: gray-screen mode ---
  /**
   * Grayscale ALL of YouTube; whitelisted channels come back in colour. Nobody else ships
   * this (ext-02), and it is the softest possible intervention: the content is still there,
   * it just stops being candy.
   */
  grayScreen: boolean;

  // --- v1.1: Unhook-parity hide toggles, each independent, all default off ---
  hideHomeFeed: boolean;
  hideSidebarRecs: boolean;
  hideEndScreen: boolean;
  hideComments: boolean;
  disableAutoplay: boolean;
}

// ── Lights Off — the scheduled GLOBAL lockdown (v3) ──

/** Minutes in a day; a minute-of-day is 0..MINUTES_PER_DAY-1. */
export const MINUTES_PER_DAY = 1440;
export const MINUTE_OF_DAY_MAX = MINUTES_PER_DAY - 1;

/** The bedtime window the feature exists for: 22:00 -> 07:00. */
export const LIGHTS_OFF_DEFAULT_START_MINUTE = 22 * 60;
export const LIGHTS_OFF_DEFAULT_END_MINUTE = 7 * 60;
export const LIGHTS_OFF_DEFAULT_PROFILE_NAME = 'Lights Off';

/**
 * How deep the lockdown cuts.
 *
 * BASIC (default) matches the per-site rules' scope: `main_frame` only, so a whitelisted page
 * that embeds a non-whitelisted iframe is not cut. STRICT additionally blocks `sub_frame`
 * requests, closing the embed side-door at the cost of breaking legitimately-embedded content
 * on pages that were already open when the window began.
 */
export type LightsOffStrictness = 'BASIC' | 'STRICT';

/**
 * One Lights Off window.
 *
 * v1 ships ONE global lockdown, but the settings model is a LIST from day one so "add a
 * second profile" is a feature, not a migration (design §3d). Everything reads `profiles[0]`.
 */
export interface LightsOffProfile {
  id: string;
  /** Shown once multiple profiles exist; v1 renders a single unnamed panel. */
  name: string;
  enabled: boolean;
  /** ISO day numbers 1=Mon..7=Sun. null/empty = every day. */
  days: number[] | null;
  /**
   * Minutes from local midnight, 0..1439. Overnight spans (end < start) are supported;
   * start === end is an EMPTY window (never active) — the same semantics as
   * `ScheduleOverride`, deliberately, so one evaluator serves both.
   */
  startMinute: number;
  endMinute: number;
  /**
   * Base domains that stay reachable while the lockdown is on. Subdomains of an entry are
   * covered too, matching how a site rule's domain behaves.
   */
  allowedDomains: string[];
  strictness: LightsOffStrictness;
}

export interface LightsOffSettings {
  /** Always at least one entry (coercion guarantees it); v1 uses `profiles[0]`. */
  profiles: LightsOffProfile[];
  /**
   * "Start Lights Off now until [time]" — epoch ms. While this is in the future the lockdown
   * is on regardless of any schedule, which is the ad-hoc wind-down people actually reach for.
   */
  manualUntil: number | null;
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
  lightsOff: LightsOffSettings;
  tempAllowMinutes: number;
}

/**
 * A fresh, DISABLED Lights Off profile.
 *
 * Deterministic (no clock, no randomness) so `migrateSettings(garbage)` is exactly
 * `DEFAULT_SETTINGS` — a settings read has to be reproducible.
 */
export function defaultLightsOffProfile(): LightsOffProfile {
  return {
    id: 'lights-off-1',
    name: LIGHTS_OFF_DEFAULT_PROFILE_NAME,
    enabled: false,
    days: null,
    startMinute: LIGHTS_OFF_DEFAULT_START_MINUTE,
    endMinute: LIGHTS_OFF_DEFAULT_END_MINUTE,
    allowedDomains: [],
    strictness: 'BASIC',
  };
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
    channelMode: 'OFF',
    channels: [],
    channelBlockMode: 'DELAY',
    channelDelaySeconds: DEFAULT_DELAY_SECONDS,
    grayScreen: false,
    hideHomeFeed: false,
    hideSidebarRecs: false,
    hideEndScreen: false,
    hideComments: false,
    disableAutoplay: false,
  },
  lightsOff: { profiles: [defaultLightsOffProfile()], manualUntil: null },
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

/**
 * The YouTube feature rule carries one extra state beyond the three block modes:
 * 'INHERIT', meaning "defer to the site rule for youtube.com". It needs its own
 * coercion because 'INHERIT' is not a `BlockMode` and so cannot be a `coerceMode` fallback.
 */
function coerceShortsMode(value: unknown): 'INHERIT' | BlockMode {
  if (value === 'INHERIT') return 'INHERIT';
  return VALID_MODES.includes(value as BlockMode) ? (value as BlockMode) : 'INHERIT';
}

const VALID_CHANNEL_MODES: readonly ChannelListMode[] = ['OFF', 'BLACKLIST', 'WHITELIST'];

function coerceChannelMode(value: unknown): ChannelListMode {
  return VALID_CHANNEL_MODES.includes(value as ChannelListMode)
    ? (value as ChannelListMode)
    : 'OFF';
}

/**
 * Normalize one stored channel entry.
 *
 * An entry with NEITHER identifier is dropped: it could never match anything, so keeping it
 * would only show the user a list row that silently does nothing.
 */
function coerceChannel(value: unknown): ChannelEntry | null {
  if (!value || typeof value !== 'object') return null;
  const raw = value as Partial<ChannelEntry>;

  const channelId =
    typeof raw.channelId === 'string' && raw.channelId.trim() !== ''
      ? raw.channelId.trim()
      : null;
  const handle =
    typeof raw.handle === 'string' && raw.handle.trim() !== ''
      ? raw.handle.trim().replace(/^@/, '').toLowerCase()
      : null;
  if (channelId === null && handle === null) return null;

  const displayName =
    typeof raw.displayName === 'string' && raw.displayName.trim() !== ''
      ? raw.displayName.trim()
      : (handle !== null ? `@${handle}` : (channelId ?? ''));

  return {
    channelId,
    handle,
    displayName,
    addedAt: typeof raw.addedAt === 'number' ? raw.addedAt : 0,
  };
}

/**
 * De-duplicate a channel list, merging entries that describe the same channel.
 *
 * The same channel can be added twice by different routes (once by handle from a feed card,
 * once by id from a watch page). Left un-merged, a whitelist would show it twice and a
 * blacklist would look inconsistent, so entries that share EITHER identifier are folded
 * together — which also fills in the identifier the other copy was missing.
 */
function dedupeChannels(entries: readonly ChannelEntry[]): ChannelEntry[] {
  const merged: ChannelEntry[] = [];
  for (const entry of entries) {
    const existing = merged.find(
      (candidate) =>
        (entry.channelId !== null && candidate.channelId === entry.channelId) ||
        (entry.handle !== null && candidate.handle === entry.handle),
    );
    if (existing === undefined) {
      merged.push(entry);
      continue;
    }
    existing.channelId ??= entry.channelId;
    existing.handle ??= entry.handle;
    if (existing.displayName.startsWith('@') && !entry.displayName.startsWith('@')) {
      existing.displayName = entry.displayName;
    }
  }
  return merged;
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

/** ISO day numbers only; anything else is dropped. Empty means "every day", stored as null. */
function coerceIsoDays(value: unknown): number[] | null {
  if (!Array.isArray(value)) return null;
  const days = [
    ...new Set(value.filter((d): d is number => Number.isInteger(d) && d >= 1 && d <= 7)),
  ].sort((a, b) => a - b);
  return days.length > 0 ? days : null;
}

/**
 * Normalize a Lights Off allow-list. Entries are deduplicated and sorted so the compiled DNR
 * rule ids are stable across saves, and anything unreadable as a host is dropped rather than
 * kept as a row that silently grants nothing.
 */
function coerceAllowedDomains(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const domains = new Set<string>();
  for (const entry of value) {
    if (typeof entry !== 'string') continue;
    const normalized = normalizeAllowedDomain(entry);
    if (normalized !== null) domains.add(normalized);
  }
  return [...domains].sort();
}

function coerceLightsOffProfile(value: unknown, index: number): LightsOffProfile {
  const raw = (value && typeof value === 'object' ? value : {}) as Partial<LightsOffProfile>;
  const fallback = defaultLightsOffProfile();
  return {
    id: typeof raw.id === 'string' && raw.id.trim() !== '' ? raw.id : `lights-off-${index + 1}`,
    name:
      typeof raw.name === 'string' && raw.name.trim() !== ''
        ? raw.name.trim()
        : fallback.name,
    // Fails toward OFF, unlike `SiteRule.enabled` (which fails toward ON). A per-site rule
    // failing on is fail-SAFE; a GLOBAL lockdown failing on would lock a user out of the
    // whole internet because one stored field was garbage. Off is the safe direction here.
    enabled: raw.enabled === true,
    days: coerceIsoDays(raw.days),
    startMinute:
      typeof raw.startMinute === 'number'
        ? clamp(raw.startMinute, 0, MINUTE_OF_DAY_MAX)
        : fallback.startMinute,
    endMinute:
      typeof raw.endMinute === 'number'
        ? clamp(raw.endMinute, 0, MINUTE_OF_DAY_MAX)
        : fallback.endMinute,
    allowedDomains: coerceAllowedDomains(raw.allowedDomains),
    strictness: raw.strictness === 'STRICT' ? 'STRICT' : 'BASIC',
  };
}

/**
 * Normalize the Lights Off block. TOTAL — parallel to `coerceChannel`.
 *
 * Always yields at least one profile, so every reader can use `profiles[0]` without an
 * empty-state branch (and so v1's single-panel UI always has something to render).
 */
export function coerceLightsOff(value: unknown): LightsOffSettings {
  const raw = (value && typeof value === 'object' ? value : {}) as Partial<LightsOffSettings>;
  const profiles =
    Array.isArray(raw.profiles) && raw.profiles.length > 0
      ? raw.profiles.map((profile, index) => coerceLightsOffProfile(profile, index))
      : [defaultLightsOffProfile()];
  return {
    profiles,
    manualUntil:
      typeof raw.manualUntil === 'number' && Number.isFinite(raw.manualUntil) && raw.manualUntil > 0
        ? Math.round(raw.manualUntil)
        : null,
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
      shortsMode: coerceShortsMode(yt.shortsMode),
      hideShortsShelf: yt.hideShortsShelf === true,
      shortsDelaySeconds: clamp(
        typeof yt.shortsDelaySeconds === 'number'
          ? yt.shortsDelaySeconds
          : DEFAULT_DELAY_SECONDS,
        DELAY_MIN_SECONDS,
        DELAY_MAX_SECONDS,
      ),
      // v2 fields. A v1 settings object has none of these, so each falls back to its
      // default — all "off", so upgrading never changes what a user is protected from.
      channelMode: coerceChannelMode(yt.channelMode),
      channels: dedupeChannels(
        Array.isArray(yt.channels)
          ? yt.channels.map(coerceChannel).filter((c): c is ChannelEntry => c !== null)
          : [],
      ),
      channelBlockMode: coerceMode(yt.channelBlockMode, 'DELAY'),
      channelDelaySeconds: clamp(
        typeof yt.channelDelaySeconds === 'number'
          ? yt.channelDelaySeconds
          : DEFAULT_DELAY_SECONDS,
        DELAY_MIN_SECONDS,
        DELAY_MAX_SECONDS,
      ),
      grayScreen: yt.grayScreen === true,
      hideHomeFeed: yt.hideHomeFeed === true,
      hideSidebarRecs: yt.hideSidebarRecs === true,
      hideEndScreen: yt.hideEndScreen === true,
      hideComments: yt.hideComments === true,
      disableAutoplay: yt.disableAutoplay === true,
    },
    // v3 field. A v1/v2 settings object has no `lightsOff` at all, so it becomes one
    // DISABLED default profile — the upgrade cannot start a lockdown nobody asked for.
    lightsOff: coerceLightsOff(input.lightsOff),
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
