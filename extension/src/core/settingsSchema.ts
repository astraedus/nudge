/**
 * The persisted settings shape (chrome.storage.sync) + defaults + migrations.
 *
 * PURE — no chrome.* imports. The storage adapter lives in src/background/storage.ts.
 *
 * Shapes are designed to become the `extension` JSONB namespace if optional account
 * sync ever ships (ext-06), hence `schemaVersion` from day one.
 * Usage/stats data NEVER lives here — it is storage.local only and never leaves the device.
 */

import {
  gateModeStrength,
  isKnownGate,
  isKnownHide,
  platformById,
  platformForDomain,
  type GateId,
  type HideId,
  type Platform,
} from './platforms';
import type { BlockMode, SiteMode } from './types';

/**
 * 1 -> 2 (Phase 4): YouTube channel lists, gray-screen mode, and the Unhook-parity hide
 * toggles. Purely additive, every new default "off".
 *
 * 2 -> 3 (v0.2): per-site everything. The rule becomes the unit — it gains an ALLOW mode,
 * a `grayscale` toggle and a `features` block — and the top-level `youtube` object FOLDS
 * INTO the youtube.com rule. This is the first migration that MOVES data rather than only
 * adding fields, so it is also the first one that can lose a setting a real user is
 * relying on (v0.1.0 is live on the Web Store). `foldLegacyYoutube` therefore merges
 * rather than assigns, taking the stronger value on every axis, so the migration can be
 * run twice, run against an already-v3 blob, or run against a half-written one, and never
 * reduce what the user is protected from.
 *
 * `migrateSettings` remains the ONLY migration path and stays total — it rebuilds every
 * field from whatever it is handed — so there is still no per-version branch to keep in
 * sync. The fold keys off the PRESENCE of the legacy shape, not off `schemaVersion`,
 * because a hand-edited or imported blob may carry the old data with any version number
 * on it (or none).
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

/**
 * One feature gate's settings — a mini site rule for a URL-addressable sub-surface.
 *
 * `mode: 'OFF'` and a `dailyLimitMinutes` are INDEPENDENT, not two ways of saying the same
 * thing: "don't gate Shorts, but stop me after 10 minutes of them" is OFF + a limit, and
 * is one of the two shapes this feature exists for.
 */
export interface GateSetting {
  mode: 'OFF' | BlockMode;
  delaySeconds: number;
  dailyLimitMinutes: number | null;
}

/** YouTube's channel lists, which no other platform has an analog for. */
export interface YoutubeFeatureSettings {
  channelMode: ChannelListMode;
  channels: ChannelEntry[];
  /**
   * The mode applied to a disallowed channel when the SITE itself is in ALLOW mode. When
   * the site is in a block mode, a disallowed channel falls to the site's own mode instead
   * — the user already said what "blocked" means for this site, and having a second,
   * quieter answer for the same question is how a whitelist ends up weaker than the rule
   * the user thought they wrote.
   */
  channelBlockMode: BlockMode;
  channelDelaySeconds: number;
  disableAutoplay: boolean;
}

/**
 * The feature surfaces configured for one site. Present only for domains the platform
 * registry knows; `null` for an ordinary site, where there is nothing to configure.
 *
 * Both maps are PARTIAL and validated against the registry on every load: an id the
 * platform does not define is dropped rather than kept, so a stale key from an older
 * build (or a hand-edited import) can never resurrect a surface the code no longer knows
 * how to enforce.
 */
export interface SiteFeatures {
  platform: Platform;
  gates: Partial<Record<GateId, GateSetting>>;
  hides: Partial<Record<HideId, boolean>>;
  /** Only on platform 'youtube'. */
  youtube?: YoutubeFeatureSettings;
}

/** A per-site rule. One row per site; the schedule override is nested, not a mirrored row. */
export interface SiteRule {
  id: string;
  /** Normalized base domain, e.g. "youtube.com". Matches www./m./mobile. automatically. */
  domain: string;
  /**
   * "Default behaviour". 'ALLOW' means the site opens normally and the rule exists only
   * for its limit, grayscale or features — see `core/applies.ts`, which is the single
   * place that turns this field into "blocked right now" or not.
   */
  mode: SiteMode;
  delaySeconds: number;
  dailyLimitMinutes: number | null;
  enabled: boolean;
  createdAt: number;
  /** Show remaining budget as badge text on the toolbar icon. */
  showTimeRemaining: boolean;
  /** "Scheduled Override" — an independent mode+delay inside the window. */
  schedule: ScheduleOverride | null;
  /** Grayscale every page of this site, flash-free. */
  grayscale: boolean;
  /** Platform feature settings, or null when this domain is not a known platform. */
  features: SiteFeatures | null;
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
  /**
   * Also a `SiteMode` since v0.2, and deliberately so: 'ALLOW' inside the window is what
   * finally expresses "block this all day EXCEPT lunchtime", and 'ALLOW' outside it (i.e.
   * an ALLOW rule with a blocking window) expresses "block only during work hours" — the
   * gap v0.1's Known Gaps section admitted to.
   */
  mode: SiteMode;
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

/**
 * The v2 top-level YouTube block. **LEGACY — read on migration, never written.**
 *
 * Kept as a named type rather than inlined into the migration because it is the exact
 * shape stored on every v0.1.0 install in the wild, and naming it is what lets the fold be
 * type-checked against reality instead of against a hand-copied field list.
 */
export interface LegacyYoutubeSettings {
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

export interface NudgeSettings {
  schemaVersion: number;
  /** Master toggle. Off = Nudge behaves as if uninstalled (Android v1.9.2 semantics). */
  globalEnabled: boolean;
  onboardingComplete: boolean;
  rules: SiteRule[];
  messages: MessageSettings;
  strictMode: StrictModeSettings;
  emergencyPass: EmergencyPassSettings;
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
  tempAllowMinutes: DEFAULT_TEMP_ALLOW_MINUTES,
};

/** A gate that does nothing: no gating, no budget. The default for every surface. */
export function defaultGateSetting(): GateSetting {
  return { mode: 'OFF', delaySeconds: DEFAULT_DELAY_SECONDS, dailyLimitMinutes: null };
}

export function defaultYoutubeFeatureSettings(): YoutubeFeatureSettings {
  return {
    channelMode: 'OFF',
    channels: [],
    channelBlockMode: 'DELAY',
    channelDelaySeconds: DEFAULT_DELAY_SECONDS,
    disableAutoplay: false,
  };
}

/**
 * A features block with every surface present and every surface off.
 *
 * Every gate and hide the platform defines is materialized rather than left absent, so the
 * rule editor can render the full list from the stored object alone and a missing key can
 * never be mistaken for "this platform doesn't have that surface". Nothing is enabled, so
 * attaching this to an existing rule changes no behaviour.
 */
export function defaultFeatures(platform: Platform): SiteFeatures {
  const definition = platformById(platform);
  const gates: Partial<Record<GateId, GateSetting>> = {};
  for (const gate of definition.gates) gates[gate.id] = defaultGateSetting();
  const hides: Partial<Record<HideId, boolean>> = {};
  for (const hide of definition.hides) hides[hide.id] = false;

  const features: SiteFeatures = { platform, gates, hides };
  if (platform === 'youtube') features.youtube = defaultYoutubeFeatureSettings();
  return features;
}

/** A brand-new rule for `domain`, with features seeded when it is a known platform. */
export function newSiteRule(params: {
  domain: string;
  mode: SiteMode;
  delaySeconds?: number;
  createdAt?: number;
}): SiteRule {
  const platform = platformForDomain(params.domain);
  return {
    id: `rule-${params.domain}-${params.createdAt ?? 0}`,
    domain: params.domain,
    mode: params.mode,
    delaySeconds: params.delaySeconds ?? DEFAULT_DELAY_SECONDS,
    dailyLimitMinutes: null,
    enabled: true,
    createdAt: params.createdAt ?? 0,
    showTimeRemaining: false,
    schedule: null,
    grayscale: false,
    features: platform === null ? null : defaultFeatures(platform.id),
  };
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, Math.round(value)));
}

const VALID_MODES: readonly BlockMode[] = ['HARD_BLOCK', 'DELAY', 'BREATHING'];

function coerceMode(value: unknown, fallback: BlockMode): BlockMode {
  return VALID_MODES.includes(value as BlockMode) ? (value as BlockMode) : fallback;
}

/**
 * A site/schedule mode, which since v0.2 may also be 'ALLOW'.
 *
 * The fallback is deliberately a parameter with no default: reading an unrecognized value
 * as ALLOW is a silent un-blocking, and reading it as HARD_BLOCK is a silent over-block.
 * Every call site has to say which direction is safe for it.
 */
function coerceSiteMode(value: unknown, fallback: SiteMode): SiteMode {
  if (value === 'ALLOW') return 'ALLOW';
  return VALID_MODES.includes(value as BlockMode) ? (value as BlockMode) : fallback;
}

function coerceGateMode(value: unknown): 'OFF' | BlockMode {
  if (value === 'OFF') return 'OFF';
  return VALID_MODES.includes(value as BlockMode) ? (value as BlockMode) : 'OFF';
}

function coerceDelay(value: unknown): number {
  return clamp(
    typeof value === 'number' ? value : DEFAULT_DELAY_SECONDS,
    DELAY_MIN_SECONDS,
    DELAY_MAX_SECONDS,
  );
}

function coerceDailyLimit(value: unknown): number | null {
  return typeof value === 'number'
    ? clamp(value, DAILY_LIMIT_MIN_MINUTES, DAILY_LIMIT_MAX_MINUTES)
    : null;
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
    // A schedule window is the "and during THIS time, do that instead" half of a rule; an
    // unreadable mode there falls to the strongest option, never to ALLOW.
    mode: coerceSiteMode(raw.mode, 'HARD_BLOCK'),
    delaySeconds: coerceDelay(raw.delaySeconds),
  };
}

function coerceGateSetting(value: unknown): GateSetting {
  if (!value || typeof value !== 'object') return defaultGateSetting();
  const raw = value as Partial<GateSetting>;
  return {
    mode: coerceGateMode(raw.mode),
    delaySeconds: coerceDelay(raw.delaySeconds),
    dailyLimitMinutes: coerceDailyLimit(raw.dailyLimitMinutes),
  };
}

function coerceYoutubeFeatures(value: unknown): YoutubeFeatureSettings {
  const raw = (value ?? {}) as Partial<YoutubeFeatureSettings>;
  return {
    channelMode: coerceChannelMode(raw.channelMode),
    channels: dedupeChannels(
      Array.isArray(raw.channels)
        ? raw.channels.map(coerceChannel).filter((c): c is ChannelEntry => c !== null)
        : [],
    ),
    channelBlockMode: coerceMode(raw.channelBlockMode, 'DELAY'),
    channelDelaySeconds: coerceDelay(raw.channelDelaySeconds),
    disableAutoplay: raw.disableAutoplay === true,
  };
}

/**
 * Normalize a stored features block for `domain`.
 *
 * The PLATFORM is derived from the domain, never trusted from the stored object: the two
 * can only disagree if the blob was hand-edited or the rule's domain was changed under it,
 * and in both cases the domain is what the network layer and the content script will act
 * on, so it has to be what the settings describe. Starting from `defaultFeatures` and
 * overlaying only ids the registry recognizes drops stale keys and materializes new
 * surfaces in one pass.
 */
function coerceFeatures(domain: string, value: unknown): SiteFeatures | null {
  const platform = platformForDomain(domain);
  if (platform === null) return null;

  const features = defaultFeatures(platform.id);
  if (!value || typeof value !== 'object') return features;
  const raw = value as Partial<SiteFeatures>;

  if (raw.gates && typeof raw.gates === 'object') {
    for (const [gateId, gate] of Object.entries(raw.gates)) {
      if (!isKnownGate(platform.id, gateId)) continue;
      features.gates[gateId] = coerceGateSetting(gate);
    }
  }
  if (raw.hides && typeof raw.hides === 'object') {
    for (const [hideId, hidden] of Object.entries(raw.hides)) {
      if (!isKnownHide(platform.id, hideId)) continue;
      features.hides[hideId] = hidden === true;
    }
  }
  if (platform.id === 'youtube') {
    features.youtube = coerceYoutubeFeatures(raw.youtube);
  }
  return features;
}

/**
 * Merge `incoming` into `base`, keeping the STRONGER value on every axis.
 *
 * Used only by the v2 fold, and the asymmetry is the point: folding the old top-level
 * YouTube block into a rule that may already carry v3 features must never turn a setting
 * off. Taking the stronger side makes the fold idempotent and order-independent, so
 * running the migration twice (or against a partially-migrated blob, which is exactly what
 * a sync conflict between a v0.1.0 and a v0.2.0 browser produces) cannot lose a setting.
 */
function mergeFeatures(base: SiteFeatures, incoming: SiteFeatures): SiteFeatures {
  const merged = defaultFeatures(base.platform);

  for (const gateId of Object.keys(merged.gates) as GateId[]) {
    const a = base.gates[gateId] ?? defaultGateSetting();
    const b = incoming.gates[gateId] ?? defaultGateSetting();
    const stronger = gateModeStrength(b.mode) > gateModeStrength(a.mode) ? b : a;
    merged.gates[gateId] = {
      mode: stronger.mode,
      delaySeconds: stronger.delaySeconds,
      // A tighter cap is stronger; "no cap" is the weakest, so any cap beats null.
      dailyLimitMinutes:
        a.dailyLimitMinutes === null
          ? b.dailyLimitMinutes
          : b.dailyLimitMinutes === null
            ? a.dailyLimitMinutes
            : Math.min(a.dailyLimitMinutes, b.dailyLimitMinutes),
    };
  }

  for (const hideId of Object.keys(merged.hides) as HideId[]) {
    merged.hides[hideId] = base.hides[hideId] === true || incoming.hides[hideId] === true;
  }

  if (merged.platform === 'youtube') {
    const a = base.youtube ?? defaultYoutubeFeatureSettings();
    const b = incoming.youtube ?? defaultYoutubeFeatureSettings();
    merged.youtube = {
      // WHITELIST (default-deny) > BLACKLIST (default-allow) > OFF.
      channelMode:
        channelModeStrength(b.channelMode) > channelModeStrength(a.channelMode)
          ? b.channelMode
          : a.channelMode,
      // The LIST is merged unconditionally, never chosen between. A channel list is data
      // the user typed, not a stance — and a user who built a list and then set the mode
      // to OFF still has the list on screen. Picking one side by its mode silently threw
      // that away on upgrade, which is the one thing a migration must never do.
      channels: dedupeChannels([...a.channels, ...b.channels]),
      channelBlockMode:
        modeStrengthOf(b.channelBlockMode) > modeStrengthOf(a.channelBlockMode)
          ? b.channelBlockMode
          : a.channelBlockMode,
      channelDelaySeconds: Math.max(a.channelDelaySeconds, b.channelDelaySeconds),
      disableAutoplay: a.disableAutoplay || b.disableAutoplay,
    };
  }
  return merged;
}

/** Local copy of strictMode's ordering — core/strictMode imports this file, so it cannot be the other way round. */
function modeStrengthOf(mode: BlockMode): number {
  return gateModeStrength(mode);
}

/** WHITELIST is default-DENY and therefore the strongest stance; OFF is no stance. */
function channelModeStrength(mode: ChannelListMode): number {
  return mode === 'WHITELIST' ? 2 : mode === 'BLACKLIST' ? 1 : 0;
}

/**
 * Normalize one arbitrary object into a `SiteRule`, or null when it cannot be one.
 *
 * Exported because the settings IMPORT path needs exactly this and nothing else: a second
 * copy of rule coercion living in `ui/exportImport.ts` is how an imported v3 blob would
 * quietly lose its `features` (or read `mode: 'ALLOW'` as a Hard Block) while the storage
 * path handled both correctly.
 */
export function coerceRule(value: unknown): SiteRule | null {
  if (!value || typeof value !== 'object') return null;
  const raw = value as Partial<SiteRule>;
  if (typeof raw.domain !== 'string' || raw.domain.trim() === '') return null;
  const domain = raw.domain.trim().toLowerCase();
  return {
    id: typeof raw.id === 'string' && raw.id ? raw.id : `rule-${domain}`,
    domain,
    // A v1/v2 rule always carried a real block mode, so an unreadable value there means
    // corruption, not an older shape — fall back to the strongest mode, never to ALLOW.
    mode: coerceSiteMode(raw.mode, 'HARD_BLOCK'),
    delaySeconds: coerceDelay(raw.delaySeconds),
    dailyLimitMinutes: coerceDailyLimit(raw.dailyLimitMinutes),
    enabled: raw.enabled !== false,
    createdAt: typeof raw.createdAt === 'number' ? raw.createdAt : 0,
    showTimeRemaining: raw.showTimeRemaining === true,
    schedule: coerceSchedule(raw.schedule),
    grayscale: raw.grayscale === true,
    features: coerceFeatures(domain, raw.features),
  };
}

/** The canonical YouTube domain, as the registry spells it. */
const YOUTUBE_DOMAIN = 'youtube.com';

/** True when any v2 YouTube feature was actually switched on. */
function legacyYoutubeIsActive(yt: Partial<LegacyYoutubeSettings>): boolean {
  return (
    (yt.shortsMode !== undefined && yt.shortsMode !== 'INHERIT') ||
    yt.hideShortsShelf === true ||
    (yt.channelMode !== undefined && yt.channelMode !== 'OFF') ||
    yt.grayScreen === true ||
    yt.hideHomeFeed === true ||
    yt.hideSidebarRecs === true ||
    yt.hideEndScreen === true ||
    yt.hideComments === true ||
    yt.disableAutoplay === true
  );
}

/** The v2 YouTube block expressed as v3 features. */
function legacyYoutubeAsFeatures(yt: Partial<LegacyYoutubeSettings>): SiteFeatures {
  const features = defaultFeatures('youtube');
  // 'INHERIT' meant "defer to the youtube.com site rule". In v3 the site rule applies to
  // the whole site by construction, so deferring IS the site rule and the gate is simply
  // off — the user loses no protection, because whatever the site rule does to /shorts/
  // it already did to everything else.
  const shortsMode = coerceShortsMode(yt.shortsMode);
  features.gates.shorts = {
    mode: shortsMode === 'INHERIT' ? 'OFF' : shortsMode,
    delaySeconds: coerceDelay(yt.shortsDelaySeconds),
    dailyLimitMinutes: null,
  };
  features.hides.shortsShelf = yt.hideShortsShelf === true;
  features.hides.homeFeed = yt.hideHomeFeed === true;
  features.hides.sidebarRecs = yt.hideSidebarRecs === true;
  features.hides.endScreen = yt.hideEndScreen === true;
  features.hides.comments = yt.hideComments === true;
  features.youtube = {
    channelMode: coerceChannelMode(yt.channelMode),
    channels: dedupeChannels(
      Array.isArray(yt.channels)
        ? yt.channels.map(coerceChannel).filter((c): c is ChannelEntry => c !== null)
        : [],
    ),
    channelBlockMode: coerceMode(yt.channelBlockMode, 'DELAY'),
    channelDelaySeconds: coerceDelay(yt.channelDelaySeconds),
    disableAutoplay: yt.disableAutoplay === true,
  };
  return features;
}

/**
 * Fold a v2 top-level `youtube` block into the youtube.com rule, in place.
 *
 * Keeps the existing rule's mode, delay, limit and schedule untouched — the user chose
 * those for the site and the fold is about features, not about what "blocked" means here.
 * Creates an ALLOW rule only when there is something to attach: a v2 user with every
 * YouTube feature off gains no rule at all, which is right, because they had no YouTube
 * protection to preserve.
 */
function foldLegacyYoutube(rules: SiteRule[], rawYoutube: unknown): void {
  if (!rawYoutube || typeof rawYoutube !== 'object') return;
  const yt = rawYoutube as Partial<LegacyYoutubeSettings>;

  const incoming = legacyYoutubeAsFeatures(yt);
  const grayscale = yt.grayScreen === true;
  const existing = rules.find((rule) => rule.domain === YOUTUBE_DOMAIN);

  if (existing !== undefined) {
    existing.features = mergeFeatures(existing.features ?? defaultFeatures('youtube'), incoming);
    existing.grayscale = existing.grayscale || grayscale;
    return;
  }

  if (!legacyYoutubeIsActive(yt)) return;

  const created = newSiteRule({ domain: YOUTUBE_DOMAIN, mode: 'ALLOW' });
  created.features = incoming;
  created.grayscale = grayscale;
  rules.push(created);
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

  // v2 -> v3: the top-level YouTube block becomes the youtube.com rule's features. Keyed
  // off the PRESENCE of the legacy object rather than schemaVersion, so an imported or
  // hand-edited blob carrying the old shape under any version number still migrates.
  foldLegacyYoutube(rules, (raw as { youtube?: unknown }).youtube);

  const messages = (input.messages ?? {}) as Partial<MessageSettings>;
  const strict = (input.strictMode ?? {}) as Partial<StrictModeSettings>;
  const pass = (input.emergencyPass ?? {}) as Partial<EmergencyPassSettings>;

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
