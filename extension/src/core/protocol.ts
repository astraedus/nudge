/**
 * The typed runtime-message contract between the service worker and every UI surface
 * (block page, popup, dashboard, onboarding) plus the YouTube content script. PURE.
 *
 * Every message is a discriminated union member keyed on `type`, and every request type
 * maps to exactly one response type via `ResponseFor`, so a handler that returns the wrong
 * shape is a compile error rather than a runtime surprise.
 */

import type { BlockDecision, BlockMode, SiteMode } from './types';
import type { GateId, HideId, Platform } from './platforms';
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
  /**
   * The feature gate the blocked URL landed on, when it was a gate rather than the whole
   * site — so the page can say "Shorts" instead of "youtube.com".
   */
  gateId: GateId | null;
  /** Human label for `gateId`. */
  gateLabel: string | null;
  /**
   * WHICH budget of that gate ran out, when one did: 'minutes' for the daily time limit,
   * 'count' for the daily item count, null when the gate is in force for another reason
   * (or no gate is involved).
   *
   * The page needs this because the two produce the same verdict and completely different
   * copy: "Daily Shorts limit reached" is wrong, and actively confusing, for someone who
   * set a count of 20 and has no time budget at all. They would go looking in the editor
   * for a minutes limit they never set.
   */
  gateLimitKind: 'minutes' | 'count' | null;
  /** Items of this surface viewed today; 0 when the surface has no item stream. */
  gateItemsToday: number;
  /** The gate's count budget, or null when it carries none. */
  gateCountLimit: number | null;
  /** Plural noun for one item of this gate's stream ("Shorts"), or null when it has none. */
  gateItemNoun: string | null;
  /**
   * The channels the user still wants to be able to reach, when a YouTube whitelist is
   * active. Empty otherwise.
   *
   * Without this the "block YouTube except these channels" setup is technically correct
   * and practically useless: every route into an allowed channel (the home feed, search,
   * the subscriptions page) is blocked, so the user can only reach what they allowed by
   * typing a URL from memory. The block page has to BE the way in.
   */
  allowedChannels: ChannelEntry[];
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
  /** The current site's mode right now, schedule applied. null when no rule covers it. */
  currentMode: SiteMode | null;
  /** Whether that rule is in force right now (`core/applies.ts`). */
  currentApplies: boolean;
  /** Grayscale state for the current site — what the popup's quick toggle reflects. */
  currentGrayscale: boolean;
  /** One line summarising the site's active features, or null when there are none. */
  currentFeatureSummary: string | null;
}

/** A per-domain daily rollup. Stored in storage.local, never transmitted anywhere. */
export interface DayUsage {
  activeSec: number;
  blocked: number;
  walkedAway: number;
  /** 24 buckets of active seconds, indexed by local hour. */
  hourly: number[];
  /**
   * Distinct short-form ITEMS viewed today (v0.3), for the count budget.
   *
   * Only ever non-zero on a `domain#gate` SURFACE key (`core/surfaceKeys.ts`): "one item"
   * is a property of a gate's stream, not of a whole site. It lives in the same rollup as
   * the minutes rather than in a store of its own because it is the same measurement over
   * the same day, and a second store would mean a second midnight reset and a second
   * rollover bug.
   *
   * ABSENT on every rollup written before v0.3, so it is filled in on read
   * (`core/stats.ts#coerceDayUsage`) rather than assumed present. Adding a numeric field
   * and incrementing it straight off storage is how `undefined + 1` becomes NaN, and a
   * NaN count is never over any limit: the budget would fail OPEN, silently.
   */
  items: number;
}

/** `yyyy-mm-dd` -> domain -> rollup. */
export type UsageByDay = Record<string, Record<string, DayUsage>>;

/**
 * Today's usage on BOTH budget axes, keyed by usage key (plain domains AND `domain#gate`
 * surface keys, see `core/surfaceKeys.ts`). A missing key means zero on both.
 *
 * One snapshot rather than two parameters, deliberately. The DNR compiler and every
 * "is this in force right now" caller need both axes for the same instant, and a pair of
 * separate arguments is a call site waiting to pass only the minutes: a rule set compiled
 * with no counts is one in which a count budget is never spent, i.e. a count-only gate
 * that silently never redirects.
 */
export interface UsageSnapshot {
  /** Active milliseconds per usage key. */
  ms: Readonly<Record<string, number>>;
  /** Items viewed today per SURFACE key. */
  counts: Readonly<Record<string, number>>;
}

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
  | { type: 'ADD_SITE'; domain: string; mode: SiteMode; delaySeconds: number }
  | { type: 'SAVE_SETTINGS'; settings: NudgeSettings; challengeResponse?: string }
  | { type: 'GET_SETTINGS' }
  /**
   * Replaces GET_YOUTUBE_CONFIG. Every platform content script asks the same question —
   * "what am I supposed to do on this page?" — and takes the page URL rather than a
   * platform name so the worker resolves the domain, the rule and the gate exactly the way
   * the network layer does, instead of each script carrying its own copy of that logic.
   */
  | { type: 'GET_SITE_CONFIG'; url: string }
  /**
   * "The page just moved onto one item of a gate's stream" (v0.3): one Short, one Reel,
   * one For You video, observed by the content script via SPA navigation.
   *
   * The content script reports WHERE it is and nothing else. The worker owns the truth:
   * it resolves the URL to a gate and an item id, de-duplicates against the day's seen
   * set, increments the surface's rollup and fires the crossing. A content script that
   * counted for itself would be a page the user can open devtools on deciding how much of
   * its own budget it has spent, and six platform scripts each keeping their own tally is
   * six chances to drift from the budget the block page reads.
   */
  | { type: 'ITEM_VIEWED'; url: string }
  /**
   * The popup's grayscale quick toggle. It is a settings mutation like any other, so it
   * goes through the worker and through the Strict Mode gate — turning grayscale ON is
   * never a weakening, turning it OFF is.
   */
  | { type: 'SET_GRAYSCALE'; domain: string; grayscale: boolean; challengeResponse?: string };

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

/** One gate, already resolved for "right now" by the worker. */
export interface ResolvedGate {
  id: GateId;
  /** 'ALLOW' = this surface is not gated at the moment. */
  mode: BlockMode | 'ALLOW';
  delaySeconds: number;
  /** True when this surface's own daily TIME budget is spent. */
  limitReached: boolean;
  /** True when this surface's own daily ITEM COUNT is spent (v0.3). */
  countReached: boolean;
  /** Items of this surface viewed today. 0 when it has no item stream. */
  itemsToday: number;
  /** This surface's count budget, or null when it carries none. */
  countLimit: number | null;
}

/**
 * Everything a platform content script needs, resolved by the worker in one round trip.
 *
 * Resolution happens in the WORKER, not the page: the page is the untrusted side (a user
 * can open devtools on it) and, more practically, it is the side that would otherwise need
 * a copy of the schedule evaluator, the budget math and the applies predicate. One
 * resolved answer means the overlay a content script shows and the redirect DNR performs
 * can never disagree about the same surface.
 */
export interface SiteConfig {
  /** False when Nudge is off, or no enabled rule covers this domain. */
  enabled: boolean;
  domain: string;
  platform: Platform | null;
  /** The site's mode right now, schedule applied. */
  siteMode: SiteMode;
  siteDelaySeconds: number;
  /** Whether the site rule itself is in force right now. */
  siteApplies: boolean;
  /** True when the site rule is in force only because its daily budget is spent. */
  siteLimitReached: boolean;
  grayscale: boolean;
  gates: ResolvedGate[];
  hides: Partial<Record<HideId, boolean>>;
  /** YouTube's channel lists; null on every other platform. */
  youtube: {
    channelMode: ChannelListMode;
    channels: ChannelEntry[];
    channelBlockMode: BlockMode;
    channelDelaySeconds: number;
    disableAutoplay: boolean;
  } | null;
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
  GET_SITE_CONFIG: SiteConfig;
  /** `counted` is false for a URL that is not an item, or an item already seen today. */
  ITEM_VIEWED: { ok: boolean; counted: boolean };
  SET_GRAYSCALE: SaveResult;
}

export type ResponseFor<T extends Request['type']> = ResponseMap[T];
