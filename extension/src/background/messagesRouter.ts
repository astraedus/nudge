/**
 * The typed runtime-message router — every UI surface talks to the worker through here.
 *
 * Design rules:
 *  - The engine is the single source of truth for "is this blocked and how". The block page
 *    never decides; it asks.
 *  - Every WEAKENING settings change is gated by the Commitment Lock (Strict Mode). The gate
 *    lives HERE, in the worker, not in the UI — a gate a page could skip is not a gate.
 *  - Handlers are total: an unknown message and a thrown handler both produce a defined
 *    response rather than a hung sendMessage promise.
 *
 * v0.2 adds `GET_SITE_CONFIG` (which replaced the YouTube-only config message) and
 * `SET_GRAYSCALE`, and teaches the block page about feature gates. The resolution work all
 * happens here rather than in the pages for the same reason it always did: a content script
 * that carried its own copy of the schedule evaluator, the budget math and the applies
 * predicate would eventually disagree with the network layer about the same surface, and
 * the user would see an overlay on a page DNR had already let through (or the reverse).
 */

import {
  appliedBlockMode,
  gateAppliesNow,
  resolveYoutubeFeatures,
  siteRuleAppliesNow,
} from '../core/applies';
import { ruleForHost, rulesForDomain, usageKeyForHost } from '../core/ruleResolver';
import { evaluate } from '../core/blockEngine';
import {
  enrichEntries,
  keepLearnedChannelIdentifiers,
  type ChannelObservation,
} from '../core/channels';
import { isOverBudget, remainingMs, tightestLimit } from '../core/budgets';
import { extractDomain, normalizeToBaseDomain, normalizeUserInput } from '../core/domainMatcher';
import * as pass from '../core/emergencyPass';
// Shared with the dashboard's rule card and the rule editor. One implementation on purpose:
// the worker resolves the popup's copy while the dashboard renders its own from settings,
// so a second string builder here would eventually describe the same rule two ways.
import { featureSummary } from '../core/featureSummary';
import { coerceRepeatPauseMinutes, pauseGrantMinutes } from '../core/repeatPause';
import {
  STUDIO_HOST_PREFIX,
  isWhitelistedVideoUrl,
  ruleUsesSubscriptions,
  watchPageVideoId,
  youtubePageAccess,
} from '../core/youtubeAccess';
import {
  DEFAULT_DELAY_SUBTITLES,
  DEFAULT_DELAY_TITLES,
  DEFAULT_HARD_BLOCK_MESSAGES,
  pickRandom,
  resolvePool,
} from '../core/messages';
import {
  gateForUrl,
  gateSupportsItemCount,
  platformById,
  platformForDomain,
  type GateDefinition,
  type GateId,
} from '../core/platforms';
import type {
  BlockContext,
  CreatorLinks,
  ChannelObservedResult,
  DashboardState,
  GrantResult,
  PopupState,
  RepeatPauseState,
  Request,
  ResolvedGate,
  SaveResult,
  SiteConfig,
} from '../core/protocol';
import { resolveActiveRules } from '../core/ruleResolver';
import { localDayKey } from '../core/scheduleEvaluator';
import {
  DEFAULT_DELAY_SECONDS,
  migrateSettings,
  newSiteRule,
  withSubscribedChannels,
  type ChannelEntry,
  type GateSetting,
  type NudgeSettings,
  type SiteRule,
} from '../core/settingsSchema';
import { allTimeTotals, lastNDayKeys, totalActiveSeconds } from '../core/stats';
import * as strict from '../core/strictMode';
import { surfaceKey } from '../core/surfaceKeys';
import { handleItemViewed } from './itemCounter';
import { renewRepeatAnchor, repeatAnchorFor } from './repeatAnchors';
import { recordVideoChannel, videoChannelLookup } from './videoChannels';
import type { ActiveRule, BlockDecision, SiteMode } from '../core/types';
import { isPauseMode } from '../core/types';
import { ensureScheduleAlarm } from './alarmsHub';
import { applyRules } from './dnr';
import {
  loadAllUsage,
  loadDay,
  loadPassLedger,
  loadSettings,
  loadSubscriptionState,
  saveSettings,
  savePassLedger,
  todayItemCount,
  todayUsageMs,
} from './storage';
import { grantTempAllow, tempAllowedUntil } from './tempAllow';
import { logBlocked, logWalkedAway, onActivityEvent } from './tracker';
import { maybeAutoSync, subscriptionStatus, syncSubscriptions } from './youtubeSubscriptions';
import { tabSignedBrowser } from './youtubeTabBrowse';

const PENDING_CHALLENGE_KEY = 'nudge:pendingChallenge';

async function loadPendingChallenge(): Promise<string | null> {
  const stored = await chrome.storage.session.get(PENDING_CHALLENGE_KEY);
  const value = stored[PENDING_CHALLENGE_KEY];
  return typeof value === 'string' ? value : null;
}

async function setPendingChallenge(challenge: string | null): Promise<void> {
  if (challenge === null) {
    await chrome.storage.session.remove(PENDING_CHALLENGE_KEY);
  } else {
    await chrome.storage.session.set({ [PENDING_CHALLENGE_KEY]: challenge });
  }
}


/** The gate a URL lands on, together with its stored settings. */
interface GateOnPage {
  definition: GateDefinition;
  setting: GateSetting;
}

function gateForPage(rule: SiteRule | null, url: string): GateOnPage | null {
  const features = rule?.features;
  if (features === undefined || features === null) return null;
  const gateId = gateForUrl(features.platform, url);
  if (gateId === null) return null;
  const setting = features.gates[gateId];
  if (setting === undefined) return null;
  const definition = platformById(features.platform).gates.find((g) => g.id === gateId);
  return definition === undefined ? null : { definition, setting };
}

/**
 * The gate's verdict expressed as engine input.
 *
 * A gate behaves like a mini site rule, so it goes through the SAME engine rather than
 * having the block page special-case it: that is what gives a gated surface the identical
 * "limit reached" copy, remaining-time readout and Hard-Block-vs-Delay resolution that a
 * site rule gets, without a second implementation to keep in step.
 */
function gateAsActiveRule(
  rule: SiteRule,
  gate: GateOnPage,
  delaySeconds: number,
  mode: NonNullable<ReturnType<typeof appliedBlockMode>>,
): ActiveRule {
  return {
    mode,
    delaySeconds,
    dailyLimitMinutes: gate.setting.dailyLimitMinutes,
    enabled: true,
    scheduleDays: null,
    scheduleStartMinute: null,
    scheduleEndMinute: null,
    ruleName: `${rule.domain} · ${gate.definition.label}`,
  };
}

/**
 * Everything a target URL means, resolved ONCE through the shared rule resolver.
 *
 * The block page was the consumer missed when subdomain matching landed (live QA R2,
 * 2026-09-20). It found the rule correctly, `ruleForDomain` was already subdomain-aware, 
 * but then read usage under the HOST (`en.wikipedia.org`) while the tracker had been
 * filling the RULE's bucket (`wikipedia.org`). So `usedMs` was 0, the spent Allow rule
 * looked under budget, and the engine answered ALLOW for a URL DNR had just redirected.
 *
 * That is the ENGINE INVARIANT breaking, and it is not a harmless no-op: the block page
 * bounced to the site, DNR redirected again, 217 main-frame navigations in 8 seconds and a
 * crashed renderer. Two halves of one question answered from two different keys is exactly
 * the failure the resolver exists to prevent, so nothing here derives a key by hand.
 */
interface ResolvedTarget {
  /** The rule covering this URL, or null. */
  rule: SiteRule | null;
  /** The domain to SPEAK about, the rule's own, so the block page names what is enforcing. */
  domain: string;
  /** The usage bucket to READ, the same one the tracker fills. */
  usageKey: string;
}

function resolveTarget(settings: NudgeSettings, url: string): ResolvedTarget {
  const host = extractDomain(url) ?? '';
  if (host === '') return { rule: null, domain: '', usageKey: '' };
  const rule = ruleForHost(settings.rules, host);
  // With no rule, the host is both the name and the bucket, unchanged behaviour.
  return { rule, domain: rule?.domain ?? host, usageKey: rule?.domain ?? host };
}

async function buildBlockContext(target: string, now: Date): Promise<BlockContext> {
  const settings = await loadSettings();
  const { rule, domain, usageKey } = resolveTarget(settings, target);
  const usedMs = usageKey === '' ? 0 : await todayUsageMs(usageKey, now);

  let decision: BlockDecision = { type: 'ALLOW' };
  let gateId: GateId | null = null;
  let gateLabel: string | null = null;
  let gateLimitKind: 'minutes' | 'count' | null = null;
  let gateItemsToday = 0;
  let gateCountLimit: number | null = null;
  let gateItemNoun: string | null = null;

  if (settings.globalEnabled) {
    // The gate is asked FIRST because the gate redirect outranks the site redirect at the
    // network layer (see dnr.ts priorities). If the block page resolved the site instead,
    // a Shorts gate on an otherwise-ALLOWed YouTube would render as "no rule applies" and
    // send the user straight back into the redirect.
    const gate = rule === null ? null : gateForPage(rule, target);
    if (gate !== null) {
      const key = surfaceKey(domain, gate.definition.id);
      const surfaceMs = await todayUsageMs(key, now);
      const surfaceCount = await todayItemCount(key, now);
      const verdict = gateAppliesNow(gate.setting, surfaceMs, surfaceCount);
      const mode = appliedBlockMode(verdict);
      if (mode !== null && rule !== null) {
        gateId = gate.definition.id;
        gateLabel = gate.definition.label;
        // WHICH budget ran out, for the page's copy. "Daily Shorts limit reached" is the
        // wrong sentence for someone who set a count of 20 and no time budget at all:
        // they would go looking in the editor for a minutes limit they never set.
        gateLimitKind =
          verdict.reason === 'limit-exhausted'
            ? 'minutes'
            : verdict.reason === 'count-exhausted'
              ? 'count'
              : null;
        gateItemsToday = gateSupportsItemCount(gate.definition) ? surfaceCount : 0;
        gateCountLimit = gate.setting.dailyLimitCount;
        gateItemNoun = gate.definition.itemNoun?.plural ?? null;
        decision = evaluate(
          [gateAsActiveRule(rule, gate, verdict.delaySeconds, mode)],
          surfaceMs,
          now,
        );
        // A count-driven block reaches the engine as a plain Hard Block (the engine knows
        // only the minute axis, and teaching it a second one would mean a second branch
        // inside the ENGINE INVARIANT it is not worth risking). The engine therefore does
        // not set `limitReached`, so the page would show no limit line at all. Set it here
        // from the verdict that actually decided, which is the one thing that knows.
        if (gateLimitKind === 'count' && decision.type === 'BLOCK') {
          decision = { ...decision, limitReached: true };
        }
      }
    }
    if (gateId === null) {
      decision = evaluate(resolveActiveRules(settings.rules, domain, now, usedMs), usedMs, now);
    }
  }

  const ledger = pass.parse(await loadPassLedger());
  const passAvailable = pass.canUseGlobal(ledger, now.getTime(), pass.LOCKOUT_MS);

  return {
    target,
    domain,
    decision,
    delayTitle: pickRandom(
      resolvePool(settings.messages.delayTitles, DEFAULT_DELAY_TITLES),
    ),
    delaySubtitle: pickRandom(
      resolvePool(settings.messages.delaySubtitles, DEFAULT_DELAY_SUBTITLES),
    ),
    hardBlockMessage: pickRandom(
      resolvePool(settings.messages.hardBlockMessages, DEFAULT_HARD_BLOCK_MESSAGES),
    ),
    passEnabled: settings.emergencyPass.enabled,
    passAvailable,
    passNextAvailableMs: pass.nextAvailableGlobalMs(
      ledger,
      now.getTime(),
      pass.LOCKOUT_MS,
    ),
    strictModeEnabled: settings.strictMode.enabled,
    tempAllowMinutes: settings.tempAllowMinutes,
    gateId,
    gateLabel,
    gateLimitKind,
    gateItemsToday,
    gateCountLimit,
    gateItemNoun,
    allowedChannels: allowedChannelsFor(rule, now),
    creator: creatorLinksFor(rule),
    repeatPauseMinutes: rule?.repeatPauseMinutes ?? null,
    repeatPauseOffered:
      rule !== null &&
      gateId === null &&
      decision.type === 'BLOCK' &&
      decision.mode !== 'HARD_BLOCK',
  };
}

/** What a site's repeat is, before asking which clock this page runs on. */
interface RepeatSetting {
  mode: 'DELAY' | 'BREATHING';
  delaySeconds: number;
  everyMinutes: number;
  domain: string;
}

interface RepeatOnPage extends RepeatSetting {
  /** The video, when this is a whitelist's carved watch page and not a redirected one. */
  carvedVideoId: string | null;
}

/** A repeating pause in force on one URL. */
interface RepeatInForce extends RepeatSetting {
  /** The video's own clock (`repeatAnchors.ts`). Null means the site's grant is the clock. */
  anchor: { videoId: string; until: number } | null;
}

/**
 * Could a repeating pause be in force on this URL? `repeatInForce` is THE question, asked by the
 * in-page controller, by the grant length and by the popup, so the timer, the DNR allow rule and
 * the readout cannot disagree. This is its first half, which `CHANNEL_PAUSE_DONE` shares.
 *
 * Off when a surface has its own answer or none is owed:
 *  - an exhausted budget: the engine has made it a Hard Block, there is no pause to repeat;
 *  - a gate in force on this page: the gate's own mode stands there, the setting is the
 *    SITE's, and `buildBlockContext` resolves such a page to the gate, so a grant asked for
 *    from here would be the gate's and could not agree with the timer;
 *  - a YouTube carve-out. An allowed channel's page or Studio never needed a pause, and
 *    repeating one on it punishes someone for watching what they allowed, which is the worst
 *    thing this product can do. Asked with `tempAllowed: false`, because the grant is exactly
 *    what is running out: only what DNR would redirect WITHOUT one stays in force. The one
 *    carve-out that goes on is a watch page, which is every video and not only allowed ones:
 *    `carvedVideoId` hands that to `repeatInForce` to settle per video.
 */
async function repeatOnPage(
  settings: NudgeSettings,
  url: string,
  now: Date,
): Promise<RepeatOnPage | null> {
  if (!settings.globalEnabled) return null;
  const { rule, domain, usageKey } = resolveTarget(settings, url);
  if (rule === null || rule.repeatPauseMinutes === null) return null;

  // Asked of the budget, not of `verdict.reason`: the predicate reports 'mode-blocks' for a
  // Delay rule whose limit is spent, and the engine still makes that a Hard Block.
  const usageMs = await todayUsageMs(usageKey, now);
  const verdict = siteRuleAppliesNow(rule, usageMs, now);
  if (!verdict.applies || isOverBudget(rule.dailyLimitMinutes, usageMs)) return null;
  if (!isPauseMode(verdict.mode)) return null;

  const gate = gateForPage(rule, url);
  if (gate !== null) {
    const key = surfaceKey(domain, gate.definition.id);
    const gateVerdict = gateAppliesNow(
      gate.setting,
      await todayUsageMs(key, now),
      await todayItemCount(key, now),
    );
    if (appliedBlockMode(gateVerdict) !== null) return null;
  }

  let carvedVideoId: string | null = null;
  const youtube = resolveYoutubeFeatures(rule, now);
  if (youtube !== undefined) {
    const access = youtubePageAccess({
      url,
      domain,
      youtube,
      tempAllowed: false,
      siteApplies: verdict.applies,
      limitReached: false,
    });
    carvedVideoId = access === 'carved' ? watchPageVideoId(url) : null;
    if (access !== 'blocked' && carvedVideoId === null) return null;
  }

  return {
    mode: verdict.mode,
    delaySeconds: verdict.delaySeconds,
    everyMinutes: rule.repeatPauseMinutes,
    domain,
    carvedVideoId,
  };
}

/**
 * The repeat on this page right now, if any. `repeatOnPage` says the site's rule allows one here;
 * a carved watch page also has to have earned it. Without an anchor nothing has established this
 * video is unlisted, and the channel gate has not had its say, so there is no pause and no pill.
 * A channel added to the list mid-watch ends it at once.
 */
async function repeatInForce(
  settings: NudgeSettings,
  url: string,
  now: Date,
): Promise<RepeatInForce | null> {
  const repeat = await repeatOnPage(settings, url, now);
  if (repeat === null) return null;
  const { carvedVideoId, ...fields } = repeat;
  if (carvedVideoId === null) return { ...fields, anchor: null };

  const until = await repeatAnchorFor(carvedVideoId);
  if (until === null) return null;
  if (isWhitelistedVideoUrl(settings.rules, url, await videoChannelLookup(), now)) return null;
  return { ...fields, anchor: { videoId: carvedVideoId, until } };
}

/**
 * When the next pause is due: the one answer for the pill and the popup, so they cannot disagree.
 * Null or past means due. Normalised to null on both clocks, as `tempAllowedUntil` already does.
 */
async function nextPauseAt(repeat: RepeatInForce, now: number): Promise<number | null> {
  if (repeat.anchor === null) return tempAllowedUntil(repeat.domain, now);
  return repeat.anchor.until > now ? repeat.anchor.until : null;
}

async function buildRepeatPauseState(url: string, now: Date): Promise<RepeatPauseState> {
  const repeat = await repeatInForce(await loadSettings(), url, now);
  if (repeat === null) return { active: false };
  return {
    active: true,
    mode: repeat.mode,
    delaySeconds: repeat.delaySeconds,
    everyMinutes: repeat.everyMinutes,
    domain: repeat.domain,
    until: await nextPauseAt(repeat, now.getTime()),
  };
}

/**
 * The channel gate on a watch page was served. The page reports, the worker decides: only a carved
 * watch page whose rule has a repeat in force starts a clock, so anything else this message says
 * changes nothing, and a forged one could only ever cost its sender another pause.
 */
async function handleChannelPauseDone(
  url: string,
  now: Date,
): Promise<{ ok: boolean; anchored: boolean }> {
  const repeat = await repeatOnPage(await loadSettings(), url, now);
  if (repeat === null || repeat.carvedVideoId === null) return { ok: true, anchored: false };
  await renewRepeatAnchor(repeat.carvedVideoId, repeat.everyMinutes, now.getTime());
  return { ok: true, anchored: true };
}

/**
 * The channels a YouTube whitelist still lets through, for the block page to link to.
 *
 * Without these links "block YouTube except these channels" is technically correct and
 * practically useless: the home feed, search and subscriptions are all redirected, so the
 * only way into an allowed channel is typing a URL from memory. The block page has to BE
 * the way in. The list is the one in force at `now`, a schedule window's included.
 */
function allowedChannelsFor(rule: SiteRule | null, now: Date): ChannelEntry[] {
  const youtube = rule === null ? undefined : resolveYoutubeFeatures(rule, now);
  if (youtube === undefined || youtube.channelMode !== 'WHITELIST') return [];
  // Synced subscriptions are allowed channels too (`effectiveChannelLists`), and they need
  // the links MORE than typed ones: the subscriptions page is itself redirected, so without
  // them a user who allowed "channels I'm subscribed to" and typed none was shown no way in
  // at all (live QA, 2026-11-01). Typed channels first and always all of them; subscriptions
  // after, in YouTube's own "most relevant" order, capped so 500 of them stay a short list.
  const subscribed = youtube.allowSubscriptions ? youtube.subscribedChannels : [];
  const typed = new Set(youtube.channels.flatMap(channelKeys));
  const extra = subscribed
    .filter((entry) => !channelKeys(entry).some((key) => typed.has(key)))
    .slice(0, MAX_BLOCK_PAGE_SUBSCRIPTIONS);
  return [...youtube.channels, ...extra];
}

/** At most this many synced subscriptions are linked from the block page. */
export const MAX_BLOCK_PAGE_SUBSCRIPTIONS = 12;

/** The identifiers that make two entries the same channel, normalised. */
function channelKeys(entry: ChannelEntry): string[] {
  const keys: string[] = [];
  if (entry.channelId !== null) keys.push(`id:${entry.channelId}`);
  if (entry.handle !== null) keys.push(`h:${entry.handle.replace(/^@/, '').toLowerCase()}`);
  return keys;
}

/**
 * Studio and the user's own channel, when the rule has creator mode on.
 *
 * Both are open at the network layer in exactly that case (`core/youtubeAccess.ts`), so a
 * link to either is never a link back to this page.
 */
function creatorLinksFor(rule: SiteRule | null): CreatorLinks | null {
  const youtube = rule?.features?.youtube;
  if (rule === null || youtube === undefined || !youtube.creatorMode) return null;
  return {
    studioUrl: `https://${STUDIO_HOST_PREFIX}${normalizeToBaseDomain(rule.domain)}/`,
    channel: youtube.myChannel,
  };
}

/**
 * Grant temporary access after a completed pause.
 *
 * Guarded: a pause can only buy access to something that was actually a DELAY or BREATHING
 * block. A HARD_BLOCK (including a budget-exhausted one) can never be completed away, so a
 * stray or replayed COMPLETE_PAUSE cannot unlock it.
 */
async function completePause(target: string, now: Date): Promise<GrantResult> {
  const context = await buildBlockContext(target, now);
  if (context.domain === '') return { ok: false, until: 0, reason: 'unknown-site' };

  if (context.decision.type === 'ALLOW') {
    return { ok: true, until: now.getTime() };
  }
  if (context.decision.mode === 'HARD_BLOCK') {
    return { ok: false, until: 0, reason: 'hard-block' };
  }

  // A gate's own pause keeps the ordinary grant: `repeatInForce` already answers null on a
  // surface where a gate is in force, which is exactly when `context` resolved to the gate.
  const repeat = await repeatInForce(await loadSettings(), target, now);
  // A video on its own clock renews that clock and nothing else: a grant here would open the home
  // feed and search, and completing a pause on one video must not buy the next.
  if (repeat !== null && repeat.anchor !== null) {
    const until = await renewRepeatAnchor(
      repeat.anchor.videoId,
      repeat.everyMinutes,
      now.getTime(),
    );
    return { ok: true, until };
  }
  const until = await grantTempAllow(
    context.domain,
    pauseGrantMinutes(repeat?.everyMinutes ?? null, context.tempAllowMinutes),
    now.getTime(),
  );
  return { ok: true, until };
}

/** The Escape Hatch: one 2-minute window per rolling 24h, globally. */
async function redeemEmergencyPass(target: string, now: Date): Promise<GrantResult> {
  const settings = await loadSettings();
  if (!settings.emergencyPass.enabled) {
    return { ok: false, until: 0, reason: 'disabled' };
  }
  // A commitment lock must not have a one-tap bypass.
  if (settings.strictMode.enabled) {
    return { ok: false, until: 0, reason: 'strict-mode' };
  }

  // The grant covers the RULE's domain, so a pass taken on en.wikipedia.org opens the site
  // the user actually ruled rather than that one host.
  const { domain } = resolveTarget(settings, target);
  if (domain === '') return { ok: false, until: 0, reason: 'unknown-site' };

  const ledger = pass.parse(await loadPassLedger());
  if (!pass.canUseGlobal(ledger, now.getTime(), pass.LOCKOUT_MS)) {
    return { ok: false, until: 0, reason: 'locked-out' };
  }

  await savePassLedger(pass.serialize(pass.recordGlobal(now.getTime())));
  const until = await grantTempAllow(
    domain,
    pass.PASS_DURATION_MS / 60_000,
    now.getTime(),
  );
  return { ok: true, until };
}

async function buildPopupState(now: Date): Promise<PopupState> {
  const settings = await loadSettings();
  const day = await loadDay(localDayKey(now));

  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  const currentDomain = tab?.url === undefined ? null : extractDomain(tab.url);

  const rules = currentDomain === null ? [] : rulesForDomain(settings.rules, currentDomain);
  const limit = tightestLimit(rules);
  // Read the bucket the TRACKER fills, which is the matching rule's domain rather than the
  // host, otherwise the popup counts down a budget nothing is adding to.
  const usageKey = currentDomain === null ? null : usageKeyForHost(settings.rules, currentDomain);
  const usedMs = usageKey === null ? 0 : (day[usageKey]?.activeSec ?? 0) * 1000;

  const currentRule = rules[0] ?? null;
  const verdict =
    currentRule === null ? null : siteRuleAppliesNow(currentRule, usedMs, now);
  const repeat = tab?.url === undefined ? null : await repeatInForce(settings, tab.url, now);

  return {
    globalEnabled: settings.globalEnabled,
    todayTotalSeconds: totalActiveSeconds(day),
    currentDomain,
    currentRule,
    currentRemainingMs: remainingMs(limit, usedMs),
    currentUsageSeconds: Math.floor(usedMs / 1000),
    currentMode: verdict?.mode ?? null,
    // The master toggle means "behave as if uninstalled", so nothing is in force under it
    // — the popup must not count down a budget the network layer is ignoring.
    currentApplies: settings.globalEnabled && (verdict?.applies ?? false),
    currentGrayscale: currentRule?.grayscale ?? false,
    // With `now`: inside a schedule window that has its own channel list, the popup names
    // that list, the one being enforced.
    currentFeatureSummary: currentRule === null ? null : featureSummary(currentRule, now),
    currentRepeatEveryMinutes: repeat?.everyMinutes ?? null,
    currentNextPauseAt: repeat === null ? null : await nextPauseAt(repeat, now.getTime()),
  };
}

async function buildDashboardState(now: Date): Promise<DashboardState> {
  const usage = await loadAllUsage();
  const totals = allTimeTotals(usage);
  return {
    settings: await loadSettings(),
    recentDays: lastNDayKeys(now, 7),
    usage,
    allTimeBlocked: totals.blocked,
    allTimeWalkedAway: totals.walkedAway,
  };
}

/**
 * Everything a platform content script needs for the page it is on.
 *
 * Replaces GET_YOUTUBE_CONFIG. The script sends a URL rather than a platform name so the
 * worker resolves domain -> rule -> platform -> gate exactly the way the network layer
 * does; a script that named its own platform would still have to be told which gate the
 * current path is, and that answer would then exist twice.
 */
async function buildSiteConfig(url: string, now: Date): Promise<SiteConfig> {
  const settings = await loadSettings();
  const { rule, domain } = resolveTarget(settings, url);
  const knownPlatform = domain === '' ? null : platformForDomain(domain);

  if (!settings.globalEnabled || rule === null) {
    // "Behave as if uninstalled": every feature off, not just blocking. Otherwise a
    // disabled Nudge would still be hiding comments and greying pages out.
    return {
      enabled: false,
      domain,
      platform: knownPlatform?.id ?? null,
      siteMode: 'ALLOW',
      siteDelaySeconds: DEFAULT_DELAY_SECONDS,
      siteApplies: false,
      siteLimitReached: false,
      tempAllowedUntil: null,
      repeatPauseInForce: false,
      grayscale: false,
      gates: [],
      hides: {},
      youtube: null,
    };
  }

  const usedMs = await todayUsageMs(domain, now);
  const verdict = siteRuleAppliesNow(rule, usedMs, now);

  // A live temp-allow grant is DELIBERATELY not consulted here, and must never be: a
  // completed pause on the SITE opens the site's gate surfaces at the network layer too
  // (temp-allow outranks the gate redirect, which is what stops a pause completed on a gate
  // from bouncing back into its own redirect — see the priority ladder in dnr.ts). The
  // in-page gate is the only thing standing between that grant and an ungated Shorts feed,
  // so it keeps enforcing the gate's own mode; only a pause completed ON the gate surface
  // satisfies the gate.
  const gates: ResolvedGate[] = [];
  const features = rule.features;
  if (features !== null) {
    for (const definition of platformById(features.platform).gates) {
      const setting = features.gates[definition.id];
      if (setting === undefined) continue;
      const key = surfaceKey(domain, definition.id);
      const surfaceMs = await todayUsageMs(key, now);
      const surfaceCount = await todayItemCount(key, now);
      const gateVerdict = gateAppliesNow(setting, surfaceMs, surfaceCount);
      gates.push({
        id: definition.id,
        mode: appliedBlockMode(gateVerdict) ?? 'ALLOW',
        delaySeconds: gateVerdict.delaySeconds,
        limitReached: gateVerdict.reason === 'limit-exhausted',
        countReached: gateVerdict.reason === 'count-exhausted',
        itemsToday: gateSupportsItemCount(definition) ? surfaceCount : 0,
        countLimit: setting.dailyLimitCount,
      });
    }
  }

  return {
    enabled: true,
    domain,
    platform: features?.platform ?? knownPlatform?.id ?? null,
    siteMode: verdict.mode,
    siteDelaySeconds: verdict.delaySeconds,
    siteApplies: verdict.applies,
    siteLimitReached: verdict.reason === 'limit-exhausted',
    // Read by the YouTube page gate ONLY (in-SPA navigation off a carve-out), never by a
    // gate: see the note above `gates`.
    tempAllowedUntil: await tempAllowedUntil(domain, now.getTime()),
    repeatPauseInForce: (await repeatInForce(settings, url, now)) !== null,
    grayscale: rule.grayscale,
    gates,
    hides: features?.hides ?? {},
    // The list in force NOW: a schedule window's own channel list replaces the rule's here,
    // so the feed filter and the watch gate enforce the same list DNR compiled.
    youtube: resolveYoutubeFeatures(rule, now) ?? null,
  };
}

/**
 * Persist settings, gating any WEAKENING change behind the Commitment Lock.
 *
 * Strengthening is never gated. The challenge is held in storage.session and reused across
 * retries so the user can keep typing the code they are looking at; it is cleared the moment
 * a save succeeds or the pending change is abandoned.
 */
async function handleSave(
  next: NudgeSettings,
  challengeResponse: string | undefined,
  now: Date,
): Promise<SaveResult> {
  const current = await loadSettings();
  // A screen saves the whole object it loaded, which can predate an identifier the worker
  // learned since (`CHANNEL_OBSERVED`). Fold those back in BEFORE the lock judges the save,
  // so neither the data nor the verdict depends on how long a dashboard tab has been open.
  //
  // The synced subscriptions are the WORKER's, never the caller's: whatever list a save
  // carries (a stale tab's, or one a page made up) is replaced by the stored one, so a
  // save can neither smuggle channels into the whitelist past the Commitment Lock nor
  // erase a sync that landed after the screen loaded.
  const stored = await loadSubscriptionState();
  const normalized = withSubscribedChannels(
    keepLearnedChannelIdentifiers(current, migrateSettings(next)),
    stored.channels,
  );

  if (current.strictMode.enabled && strict.isWeakening(current, normalized)) {
    const pending = (await loadPendingChallenge()) ?? strict.generate(
      current.strictMode.challengeLength,
    );
    await setPendingChallenge(pending);

    if (challengeResponse === undefined) {
      return { ok: false, challenge: pending, reason: 'challenge-required' };
    }
    if (!strict.verify(challengeResponse, pending)) {
      return { ok: false, challenge: pending, reason: 'challenge-incorrect' };
    }
  }

  await setPendingChallenge(null);
  await saveSettings(normalized);
  await applyRules(normalized, now);
  await ensureScheduleAlarm(normalized, now);
  await onActivityEvent(now.getTime());
  // Turning "Also allow channels I'm subscribed to" on syncs straight away, so the user sees
  // their list (or why there is none) without having to visit YouTube first. Not awaited:
  // the save is done, and the sync writes its own storage key when it lands.
  if (subscriptionsJustEnabled(current, normalized)) void syncSubscriptions();
  return { ok: true };
}

function anySubscriptionsOn(settings: NudgeSettings): boolean {
  return settings.rules.some(ruleUsesSubscriptions);
}

function subscriptionsJustEnabled(before: NudgeSettings, after: NudgeSettings): boolean {
  return !anySubscriptionsOn(before) && anySubscriptionsOn(after);
}

async function addSite(
  domain: string,
  mode: SiteMode,
  delaySeconds: number,
  now: Date,
): Promise<{ ok: boolean; reason?: string }> {
  const normalizedDomain = normalizeUserInput(domain);
  if (normalizedDomain === null) return { ok: false, reason: 'invalid-domain' };

  const settings = await loadSettings();
  if (settings.rules.some((rule) => rule.domain === normalizedDomain)) {
    return { ok: false, reason: 'already-blocked' };
  }

  // `newSiteRule` seeds the features block for a known platform, so a quick-add chip for
  // YouTube lands on a rule whose surfaces the editor can render immediately — building the
  // literal here instead is how the seeding would silently not happen on this path.
  const rule = newSiteRule({
    domain: normalizedDomain,
    mode,
    delaySeconds: delaySeconds > 0 ? delaySeconds : DEFAULT_DELAY_SECONDS,
    createdAt: now.getTime(),
  });

  // Adding a rule STRENGTHENS protection, so it is never gated by Strict Mode.
  const updated = migrateSettings({ ...settings, rules: [...settings.rules, rule] });
  await saveSettings(updated);
  await applyRules(updated, now);
  await ensureScheduleAlarm(updated, now);
  return { ok: true };
}

/**
 * The popup's grayscale quick toggle.
 *
 * Routed through `handleSave` rather than writing settings directly, so it inherits the
 * Strict Mode gate unchanged: turning grayscale OFF is a weakening and is challenged,
 * turning it ON is not. A shortcut that wrote the rule itself would be a hole in the
 * commitment lock reachable from a one-tap control, which is the worst possible place for
 * one.
 */
async function setGrayscale(
  domain: string,
  grayscale: boolean,
  challengeResponse: string | undefined,
  now: Date,
): Promise<SaveResult> {
  const normalizedDomain = normalizeUserInput(domain) ?? domain;
  const settings = await loadSettings();
  // Subdomain-aware: the popup offers this toggle for whatever page is open, so on
  // `en.wikipedia.org` it must find the `wikipedia.org` rule rather than fail `no-rule`.
  const target = ruleForHost(settings.rules, normalizedDomain);
  if (target === null) return { ok: false, reason: 'no-rule' };
  if (target.grayscale === grayscale) return { ok: true };

  const rules = settings.rules.map((rule) =>
    rule.id === target.id ? { ...rule, grayscale } : rule,
  );
  return handleSave({ ...settings, rules }, challengeResponse, now);
}

/**
 * Learn the identifier a stored channel entry is missing, from a page the user just watched.
 *
 * The decision is entirely `core/channels.enrichEntries` — this handler only finds the list,
 * hands it the observation and persists whatever comes back. That split is the same one the
 * channel verdict already uses: the worker owns storage and gating, `core/` owns meaning.
 *
 * ROUTED THROUGH `handleSave`, deliberately, rather than writing settings itself. That is
 * what makes the DNR recompile, the schedule alarm and the accounting flush happen exactly
 * as they do after a user edit — and learning an id is precisely the case that NEEDS the
 * recompile, because `/channel/UC…` only gains its allow-rule when `dnr.ts` runs again.
 *
 * ON THE COMMITMENT LOCK: `handleSave`'s Strict Mode gate is kept, not bypassed, and it can
 * never fire here. `strictMode.isWeakening` compares the two channel lists per identifier
 * (`channelMatches`, either axis), and an enrichment provably preserves that set: it never
 * adds a channel and never drops one, it only fills a null axis or merges two rows that
 * describe the SAME channel. Pinned by "it is not a weakening in either list mode" in
 * tests/core/channels.test.ts. Keeping the gate also means the failure mode, if that ever
 * stopped being true, is a refused save — a no-op — rather than a hole in the lock.
 */
/**
 * Store which channel a watch page's video is by, for the tracker's whitelist exemption.
 * Only the video id is derived here, from the URL the page reported: a URL that is not a
 * watch page (a Short, a channel page) stores nothing, so it stays counted.
 */
async function handleVideoChannel(
  url: string,
  channelId: string | null,
  handle: string | null,
  now: Date,
): Promise<{ ok: boolean; stored: boolean }> {
  const videoId = watchPageVideoId(url);
  if (videoId === null) return { ok: false, stored: false };
  const stored = await recordVideoChannel(videoId, { channelId, handle }, now.getTime());
  return { ok: true, stored };
}

async function handleChannelObserved(
  observation: ChannelObservation,
  now: Date,
): Promise<ChannelObservedResult> {
  const settings = await loadSettings();
  // The observation can only have come from a YouTube page, and the channel list lives on
  // that site's rule. Resolved with `ruleForHost` so a rule stored as "youtube.com" is found
  // the same way every other consumer finds it, subdomains included.
  const rule = ruleForHost(settings.rules, 'youtube.com');
  const features = rule?.features ?? null;
  const youtube = features?.youtube;
  if (rule === null || features === null || youtube === undefined) {
    return { ok: true, changed: false, reason: 'no-rule' };
  }

  // Both lists learn: the rule's own and its schedule window's (v0.8). Either can hold a
  // handle-only entry, and a window's whitelist is no less in need of the `/channel/UC...`
  // allow-rule an id unlocks.
  const enriched = enrichEntries(youtube.channels, observation);
  const scheduleChannels = rule.schedule?.channels ?? null;
  const enrichedScheduled =
    scheduleChannels === null ? null : enrichEntries(scheduleChannels.channels, observation);
  if (!enriched.changed && enrichedScheduled?.changed !== true) {
    if (enriched.reason === 'contradiction' || enrichedScheduled?.reason === 'contradiction') {
      // Worth saying out loud: the page named a channel that disagrees with a stored entry
      // on an axis they share, so either YouTube served two channels on one page or our
      // detection is wrong. Both are bugs, and both are invisible without this line.
      console.warn('[nudge] channel observation contradicts a stored entry, ignored', observation);
    }
    return { ok: true, changed: false, reason: enriched.reason };
  }

  const schedule =
    rule.schedule !== null && scheduleChannels !== null && enrichedScheduled?.changed === true
      ? {
          ...rule.schedule,
          channels: { ...scheduleChannels, channels: enrichedScheduled.entries },
        }
      : rule.schedule;
  const rules = settings.rules.map((candidate) =>
    candidate.id === rule.id
      ? {
          ...candidate,
          schedule,
          features: { ...features, youtube: { ...youtube, channels: enriched.entries } },
        }
      : candidate,
  );

  const saved = await handleSave({ ...settings, rules }, undefined, now);
  const reason = enriched.changed ? enriched.reason : (enrichedScheduled?.reason ?? enriched.reason);
  return {
    ok: saved.ok,
    changed: saved.ok,
    reason: saved.ok ? reason : 'not-saved',
  };
}

/**
 * Close the tab a message came from.
 *
 * The tab id comes from the SENDER and from nowhere else: a page gets to close ITSELF, and
 * cannot name someone else's tab. `sender.tab` is undefined for a message from the popup,
 * the dashboard or the worker, which is a refusal rather than an error.
 */
async function closeSenderTab(sender: chrome.runtime.MessageSender | undefined): Promise<{
  ok: boolean;
}> {
  const tabId = sender?.tab?.id;
  if (tabId === undefined) return { ok: false };
  try {
    await chrome.tabs.remove(tabId);
    return { ok: true };
  } catch {
    // The tab can already be gone (the user closed it, or a navigation raced us). Nothing
    // to recover, and the caller has its own fallback.
    return { ok: false };
  }
}

/**
 * The block page's repeat chooser. Same shape as `setGrayscale`, and routed through
 * `handleSave` so the Commitment Lock judges it exactly like a save from the rule editor.
 */
async function setRepeatPause(
  target: string,
  minutes: number | null,
  challengeResponse: string | undefined,
  now: Date,
): Promise<SaveResult> {
  const settings = await loadSettings();
  const { rule } = resolveTarget(settings, target);
  if (rule === null) return { ok: false, reason: 'no-rule' };
  const next = coerceRepeatPauseMinutes(minutes);
  if (rule.repeatPauseMinutes === next) return { ok: true };
  const rules = settings.rules.map((candidate) =>
    candidate.id === rule.id ? { ...candidate, repeatPauseMinutes: next } : candidate,
  );
  return handleSave({ ...settings, rules }, challengeResponse, now);
}

/**
 * Send the SENDER's tab to the dashboard. As with `closeSenderTab`, a page can only ever move
 * itself, and a message from a non-tab context is a refusal rather than an error.
 */
async function leaveToDashboard(sender: chrome.runtime.MessageSender | undefined): Promise<{
  ok: boolean;
}> {
  const tabId = sender?.tab?.id;
  if (tabId === undefined) return { ok: false };
  try {
    await chrome.tabs.update(tabId, { url: chrome.runtime.getURL('dashboard.html') });
    return { ok: true };
  } catch {
    // The tab can already be gone. The caller has its own fallback.
    return { ok: false };
  }
}

/** Dispatch one request. Throwing here would hang the caller, so it never throws. */
export async function handleRequest(
  request: Request,
  now: Date = new Date(),
  sender?: chrome.runtime.MessageSender,
): Promise<unknown> {
  switch (request.type) {
    case 'GET_BLOCK_CONTEXT': {
      const context = await buildBlockContext(request.target, now);
      // The interstitial rendering IS the block event (Android's "Blocked" counter).
      if (context.decision.type === 'BLOCK' && context.domain !== '') {
        await logBlocked(context.domain, now);
      }
      // ALLOW on the block page is evidence the INSTALLED rule set is stale: something
      // redirected this URL that the engine, reading the same settings and usage right now,
      // says nothing applies to. Dynamic rules persist across browser restarts and sleep, so
      // yesterday's "budget spent" redirect is still installed when Chrome restores a tab
      // the next morning, until a recompile catches up. The page is about to bounce back to
      // the site, so recompile FIRST and the bounce goes through the fresh rules instead of
      // the stale redirect (which, twice in a row, is what trips the loop guard's error).
      if (context.decision.type === 'ALLOW' && context.domain !== '') {
        await applyRules(await loadSettings(), now);
      }
      return context;
    }
    case 'COMPLETE_PAUSE':
      return completePause(request.target, now);
    case 'WALKED_AWAY': {
      // Counted against the rule's bucket, like every other stat for this page.
      const { usageKey } = resolveTarget(await loadSettings(), request.target);
      if (usageKey !== '') await logWalkedAway(usageKey, now);
      return { ok: true };
    }
    case 'USE_EMERGENCY_PASS':
      return redeemEmergencyPass(request.target, now);
    case 'GET_POPUP_STATE':
      return buildPopupState(now);
    case 'GET_DASHBOARD_STATE':
      return buildDashboardState(now);
    case 'ADD_SITE':
      return addSite(request.domain, request.mode, request.delaySeconds, now);
    case 'SAVE_SETTINGS':
      return handleSave(request.settings, request.challengeResponse, now);
    case 'GET_SETTINGS':
      return loadSettings();
    case 'GET_SITE_CONFIG': {
      const config = await buildSiteConfig(request.url, now);
      // A youtube.com visit is the cue for the at-most-daily subscription refresh, and for
      // finishing a list left `pending` for want of a youtube.com tab. The visiting tab is
      // asked first to sign the "load more" pages. Fire and forget: the page's config never
      // waits on the network.
      if (config.platform === 'youtube' && config.youtube?.allowSubscriptions === true) {
        void maybeAutoSync({ browse: tabSignedBrowser(sender?.tab?.id) });
      }
      return config;
    }
    case 'ITEM_VIEWED':
      return handleItemViewed(request.url, now);
    case 'SET_GRAYSCALE':
      return setGrayscale(
        request.domain,
        request.grayscale,
        request.challengeResponse,
        now,
      );
    case 'CHANNEL_OBSERVED':
      return handleChannelObserved(
        {
          channelId: request.channelId,
          handle: request.handle,
          displayName: request.displayName,
        },
        now,
      );
    case 'VIDEO_CHANNEL':
      return handleVideoChannel(request.url, request.channelId, request.handle, now);
    case 'CLOSE_TAB':
      return closeSenderTab(sender);
    case 'GET_YT_SUBSCRIPTIONS':
      return subscriptionStatus();
    case 'SYNC_YT_SUBSCRIPTIONS':
      await syncSubscriptions();
      return subscriptionStatus();
    case 'GET_REPEAT_PAUSE':
      return buildRepeatPauseState(request.url, now);
    case 'CHANNEL_PAUSE_DONE':
      return handleChannelPauseDone(request.url, now);
    case 'SET_REPEAT_PAUSE':
      return setRepeatPause(request.target, request.minutes, request.challengeResponse, now);
    case 'PAUSE_SHOWN': {
      const { usageKey } = resolveTarget(await loadSettings(), request.url);
      if (usageKey !== '') await logBlocked(usageKey, now);
      return { ok: true };
    }
    case 'LEAVE_TO_DASHBOARD':
      return leaveToDashboard(sender);
    default:
      return { ok: false, reason: 'unknown-request' };
  }
}

/**
 * Wire the router to chrome.runtime.
 *
 * Returns `true` synchronously so Chrome keeps the message channel open for the async
 * response — omitting this is the classic MV3 "port closed before a response was received"
 * bug. A rejected handler still sends a response so the caller never hangs.
 */
export function registerMessageRouter(): void {
  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    handleRequest(message as Request, new Date(), sender)
      .then(sendResponse)
      .catch((error: unknown) => {
        console.error('[nudge] message handler failed', error);
        sendResponse({ ok: false, reason: 'handler-error' });
      });
    return true;
  });
}
