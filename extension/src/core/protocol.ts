/**
 * The typed runtime-message contract between the service worker and every UI surface
 * (block page, popup, dashboard, onboarding) plus the YouTube content script. PURE.
 *
 * Every message is a discriminated union member keyed on `type`, and every request type
 * maps to exactly one response type via `ResponseFor`, so a handler that returns the wrong
 * shape is a compile error rather than a runtime surprise.
 */

import type { BlockDecision, BlockMode, SiteMode } from './types';
import type { EnrichmentReason } from './channels';
import type { GateId, HideId, Platform } from './platforms';
import type { SyncError, SyncTruncation } from './youtubeSubscriptions';
import type {
  ChannelEntry,
  NudgeSettings,
  SiteRule,
  YoutubeFeatureSettings,
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
  /**
   * Creator mode's doors, when the rule that blocked this page has it on; null otherwise.
   * A creator is blocked from YouTube's home feed too, so without these the page shows the
   * allowed channels and nothing about the Studio they turned creator mode on to reach.
   */
  creator: CreatorLinks | null;
  /** The rule's "pause again every N minutes", or null when off or there is no rule. */
  repeatPauseMinutes: number | null;
  /**
   * Whether this page may offer that setting: a SITE-level Delay or Breathing pause. A feature
   * gate's pause is not one, the repeat does not reach gates, and the page must not imply it.
   */
  repeatPauseOffered: boolean;
}

/** Where creator mode lets the user go from the block page. */
export interface CreatorLinks {
  /** YouTube Studio's address, built from the rule's domain. */
  studioUrl: string;
  /** The user's own channel, or null when "Your channel" is not set. */
  channel: ChannelEntry | null;
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
  /** The repeating pause in force on this page, in minutes, or null when none is. */
  currentRepeatEveryMinutes: number | null;
  /** When the next repeat is due (epoch ms), or null when none is in force or no grant is live. */
  currentNextPauseAt: number | null;
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
  | { type: 'SET_GRAYSCALE'; domain: string; grayscale: boolean; challengeResponse?: string }
  /**
   * A channel the YouTube content script positively identified on a watch page, carrying
   * every identifier that page revealed.
   *
   * The one message a content script sends that is not a question. It exists because a
   * stored `ChannelEntry` only holds the identifier the user happened to type, while a
   * watch page reveals both — see `enrichEntries` in `core/channels.ts` for the three holes
   * that half-blind entry leaves open, and what the worker is allowed to do with this.
   *
   * ONLY ever sent from a CONFIRMED detection (`core/channelFreshness.ts`): during the
   * settle window the byline may still name the PREVIOUS video's channel, and merging a
   * stale id into a fresh entry would corrupt the list rather than enrich it. The handle is
   * sent exactly as detected, leading '@' and all; the worker normalizes it.
   */
  | {
      type: 'CHANNEL_OBSERVED';
      channelId: string | null;
      handle: string | null;
      displayName: string | null;
    }
  /**
   * "The video at `url` is by this channel", from a CONFIRMED watch-page detection.
   *
   * Exists for the tracker (Anti, 2026-11-01): time on a whitelisted channel's video is not
   * counted against the YouTube daily limit, and a tab playing one is not redirected when the
   * limit runs out. A watch URL does not say whose video it is, so the page reports the FACT
   * and the worker stores it per video id (`background/videoChannels.ts`), re-deciding
   * against the current list every time it asks. The page never says "allowed".
   *
   * Sent only while the stored list is in WHITELIST mode and only from a confirmed detection,
   * for the same settle-window reason as `CHANNEL_OBSERVED`. No report means the video is
   * counted, so a lost message fails toward the budget, never away from it.
   */
  | { type: 'VIDEO_CHANNEL'; url: string; channelId: string | null; handle: string | null }
  /**
   * The channel gate on this watch page was served. A report, not a claim: the worker decides from
   * the current settings whether it starts the video's repeat clock, and it grants nothing at the
   * network layer either way.
   */
  | { type: 'CHANNEL_PAUSE_DONE'; url: string }
  /**
   * Close the tab the message came from.
   *
   * The one thing a content script cannot do for itself: `window.close()` only works on a
   * script-opened window, and `chrome.tabs` does not exist in an isolated world. It is a
   * request rather than a settings change, so it goes through the router like everything
   * else and the worker reads the tab id from the SENDER — a page cannot name a tab to
   * close, only its own.
   *
   * Used by YouTube's channel gate when "I changed my mind" has nowhere to go back to
   * (`content/overlay.ts`'s `bailAwayFromGate`): a tab opened straight onto a gated video
   * has no previous entry, and bailing to the site root would land on the block page,
   * which is the Known gap this closes.
   */
  | { type: 'CLOSE_TAB' }
  /**
   * The synced YouTube subscriptions' status, for the dashboard's channel-list section.
   * Read-only: the list itself reaches the UI through `GET_SETTINGS`, hydrated.
   */
  | { type: 'GET_YT_SUBSCRIPTIONS' }
  /**
   * "Sync now". Fetches the user's own subscriptions page from youtube.com and stores the
   * result (`background/youtubeSubscriptions.ts`). NOT a settings change and not gated:
   * it can only refresh the list of channels the user already consented to allow, and it
   * does nothing at all unless "Also allow channels I'm subscribed to" is on, which IS
   * gated (`core/strictMode.ts`).
   */
  | { type: 'SYNC_YT_SUBSCRIPTIONS' }
  /**
   * Is a repeating pause in force on this page, and when is it next due? The in-page
   * controller asks; it never resolves a rule itself, so it cannot disagree with the grant.
   */
  | { type: 'GET_REPEAT_PAUSE'; url: string }
  /**
   * The block page's "Pause again every" chooser. A settings mutation like `SET_GRAYSCALE`,
   * so it goes through `handleSave` and inherits the Commitment Lock. `target` is the blocked
   * URL, the worker resolves the rule; `minutes` null means off.
   */
  | {
      type: 'SET_REPEAT_PAUSE';
      target: string;
      minutes: number | null;
      challengeResponse?: string;
    }
  /** The in-page pause came up. Like the block page rendering, that IS a block event. */
  | { type: 'PAUSE_SHOWN'; url: string }
  /**
   * "I changed my mind" on the in-page pause: send this tab to the dashboard, the same
   * destination the block page's walk-away uses. A content script cannot navigate to an
   * extension page that is not web-accessible, so the worker does it, and as with `CLOSE_TAB`
   * the tab id comes from the SENDER.
   */
  | { type: 'LEAVE_TO_DASHBOARD' };

/** What `GET_REPEAT_PAUSE` answers. */
export type RepeatPauseState =
  | { active: false }
  | {
      active: true;
      mode: 'DELAY' | 'BREATHING';
      delaySeconds: number;
      everyMinutes: number;
      /**
       * When the next pause is due (epoch ms): the site's grant, or on a video from a channel the
       * user did not allow, that video's own clock. Null or past means it is due now.
       */
      until: number | null;
      domain: string;
    };

/** What the dashboard shows under "Also allow channels I'm subscribed to". */
export interface SubscriptionSyncStatus {
  /** Channels in the stored list. */
  count: number;
  /** When the list was last replaced by a successful sync (epoch ms), or null. */
  syncedAt: number | null;
  /** The last attempt's failure, or null when it succeeded or none was made. */
  error: SyncError | null;
  /** Why the list may be shorter than the user's real subscriptions, or null. */
  truncated: SyncTruncation | null;
  /** A sync is running right now. */
  syncing: boolean;
}

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
  /**
   * When a completed pause on this site stops opening it (epoch ms), or null. An end time,
   * not a boolean, so a page that cached its config never treats an expired grant as live.
   * The in-page SITE gate reads it (a granted site is open in-SPA as it is on a full load);
   * the GATES never do, see the note in `buildSiteConfig`.
   */
  tempAllowedUntil: number | null;
  /**
   * A repeating pause is in force on this page, so a grant running out is its business: the
   * YouTube page gate stands down rather than reload the page and lose the video's position.
   * The worker's `repeatInForce` for this URL, the same answer `GET_REPEAT_PAUSE` gives.
   */
  repeatPauseInForce: boolean;
  grayscale: boolean;
  gates: ResolvedGate[];
  hides: Partial<Record<HideId, boolean>>;
  /** YouTube's channel lists and creator mode; null on every other platform. */
  youtube: YoutubeFeatureSettings | null;
  /**
   * The schedule window whose own channel list is the one in `youtube` right now
   * (`core/applies.activeChannelWindow`), or null. The gate's footer names it, so a
   * usually-fine channel held during the window says why.
   */
  channelWindow: { startMinute: number | null; endMinute: number | null } | null;
}

/**
 * What the worker did with a `CHANNEL_OBSERVED` report.
 *
 * `changed` is what a caller acts on; `reason` exists so a refused observation stays
 * DIAGNOSABLE — in particular `'contradiction'`, which means the page named a channel that
 * disagrees with a stored entry on an axis they share, i.e. either YouTube put two channels
 * on one page or our detection is wrong. A silent refusal there would be indistinguishable
 * from "nothing to learn", which is the common case.
 */
export interface ChannelObservedResult {
  ok: boolean;
  /** True only when the list actually learned something and settings were saved. */
  changed: boolean;
  reason: EnrichmentReason | 'no-rule' | 'not-saved';
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
  CHANNEL_OBSERVED: ChannelObservedResult;
  /** `stored` is false for a URL that is not a watch page, or a report with no identifier. */
  VIDEO_CHANNEL: { ok: boolean; stored: boolean };
  /** `anchored` is false wherever the report changed nothing. */
  CHANNEL_PAUSE_DONE: { ok: boolean; anchored: boolean };
  /** `ok: false` when the sender had no tab id (a non-tab context) or the close failed. */
  CLOSE_TAB: { ok: boolean };
  GET_YT_SUBSCRIPTIONS: SubscriptionSyncStatus;
  /** The status after the sync finished (or was refused because the option is off). */
  SYNC_YT_SUBSCRIPTIONS: SubscriptionSyncStatus;
  GET_REPEAT_PAUSE: RepeatPauseState;
  SET_REPEAT_PAUSE: SaveResult;
  PAUSE_SHOWN: { ok: boolean };
  /** `ok: false` when the sender had no tab id (a non-tab context) or the update failed. */
  LEAVE_TO_DASHBOARD: { ok: boolean };
}

export type ResponseFor<T extends Request['type']> = ResponseMap[T];
