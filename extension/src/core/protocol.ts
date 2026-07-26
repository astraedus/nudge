/**
 * The typed runtime-message contract between the service worker and every UI surface
 * (block page, popup, dashboard, onboarding) plus the YouTube content script. PURE.
 *
 * Every message is a discriminated union member keyed on `type`, and every request type
 * maps to exactly one response type via `ResponseFor`, so a handler that returns the wrong
 * shape is a compile error rather than a runtime surprise.
 */

import type { BlockDecision, BlockMode } from './types';
import type {
  ChannelEntry,
  ChannelListMode,
  NudgeSettings,
  SiteRule,
} from './settingsSchema';

/** Everything the block page needs to render, fetched in one round trip. */
export interface BlockContext {
  target: string;
  domain: string;
  decision: BlockDecision;
  /** Message pools already resolved (custom overrides applied, defaults otherwise). */
  delayTitle: string;
  delaySubtitle: string;
  hardBlockMessage: string;
  /** "Escape Hatch" state. */
  passEnabled: boolean;
  passAvailable: boolean;
  /** Remaining global lockout in ms; 0 when available. */
  passNextAvailableMs: number;
  strictModeEnabled: boolean;
  tempAllowMinutes: number;
}

export interface PopupState {
  globalEnabled: boolean;
  /** Total tracked active seconds across all sites today. */
  todayTotalSeconds: number;
  /** The active tab's site, when it is a trackable http(s) page. */
  currentDomain: string | null;
  /** The rule covering the current domain, if any. */
  currentRule: SiteRule | null;
  /** Remaining daily budget in ms for the current domain; null when no limit is set. */
  currentRemainingMs: number | null;
  /** Active seconds spent on the current domain today. */
  currentUsageSeconds: number;
}

/** A per-domain daily rollup. Stored in storage.local, never transmitted anywhere. */
export interface DayUsage {
  activeSec: number;
  blocked: number;
  walkedAway: number;
  /** 24 buckets of active seconds, indexed by local hour. */
  hourly: number[];
}

/** `yyyy-mm-dd` -> domain -> rollup. */
export type UsageByDay = Record<string, Record<string, DayUsage>>;

export interface DashboardState {
  settings: NudgeSettings;
  /** The last 7 local days including today, oldest first. */
  recentDays: string[];
  usage: UsageByDay;
  allTimeBlocked: number;
  allTimeWalkedAway: number;
}

export type Request =
  | { type: 'GET_BLOCK_CONTEXT'; target: string }
  | { type: 'COMPLETE_PAUSE'; target: string }
  | { type: 'WALKED_AWAY'; target: string }
  | { type: 'USE_EMERGENCY_PASS'; target: string }
  | { type: 'GET_POPUP_STATE' }
  | { type: 'GET_DASHBOARD_STATE' }
  | { type: 'ADD_SITE'; domain: string; mode: BlockMode; delaySeconds: number }
  | { type: 'SAVE_SETTINGS'; settings: NudgeSettings; challengeResponse?: string }
  | { type: 'GET_SETTINGS' }
  | { type: 'GET_YOUTUBE_CONFIG' };

/** Granting temporary access, or refusing to. */
export interface GrantResult {
  ok: boolean;
  /** Epoch ms the grant expires; 0 when refused. */
  until: number;
  /** Present when `ok` is false. */
  reason?: string;
}

/** A save that Strict Mode intercepted: the UI must solve `challenge` and retry. */
export interface SaveResult {
  ok: boolean;
  /** Set when a Strict Mode challenge is required (or was answered incorrectly). */
  challenge?: string;
  reason?: string;
}

export interface YoutubeConfig {
  enabled: boolean;
  hideShortsShelf: boolean;
  /** The resolved mode for /shorts/*, already accounting for INHERIT. */
  shortsMode: BlockMode | 'ALLOW';
  shortsDelaySeconds: number;

  // --- v1.1 ---
  /** How `channels` is interpreted: off / listed-are-blocked / only-listed-are-allowed. */
  channelMode: ChannelListMode;
  /** The list itself. BOTH identifiers travel so the page can match on either. */
  channels: ChannelEntry[];
  /** Mode applied to a channel the list disallows. */
  channelBlockMode: BlockMode;
  channelDelaySeconds: number;
  /** Grayscale all of YouTube; allowed channels flip back to colour. */
  grayScreen: boolean;
  hideHomeFeed: boolean;
  hideSidebarRecs: boolean;
  hideEndScreen: boolean;
  hideComments: boolean;
  disableAutoplay: boolean;
}

export interface ResponseMap {
  GET_BLOCK_CONTEXT: BlockContext;
  COMPLETE_PAUSE: GrantResult;
  WALKED_AWAY: { ok: true };
  USE_EMERGENCY_PASS: GrantResult;
  GET_POPUP_STATE: PopupState;
  GET_DASHBOARD_STATE: DashboardState;
  ADD_SITE: { ok: boolean; reason?: string };
  SAVE_SETTINGS: SaveResult;
  GET_SETTINGS: NudgeSettings;
  GET_YOUTUBE_CONFIG: YoutubeConfig;
}

export type ResponseFor<T extends Request['type']> = ResponseMap[T];
