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

import { appliedBlockMode, gateAppliesNow, siteRuleAppliesNow } from '../core/applies';
import { ruleForHost, rulesForDomain, usageKeyForHost } from '../core/ruleResolver';
import { evaluate } from '../core/blockEngine';
import { remainingMs, tightestLimit } from '../core/budgets';
import { extractDomain, normalizeUserInput } from '../core/domainMatcher';
import * as pass from '../core/emergencyPass';
// Shared with the dashboard's rule card and the rule editor. One implementation on purpose:
// the worker resolves the popup's copy while the dashboard renders its own from settings,
// so a second string builder here would eventually describe the same rule two ways.
import { featureSummary } from '../core/featureSummary';
import {
  DEFAULT_DELAY_SUBTITLES,
  DEFAULT_DELAY_TITLES,
  DEFAULT_HARD_BLOCK_MESSAGES,
  pickRandom,
  resolvePool,
} from '../core/messages';
import {
  gateForUrl,
  platformById,
  platformForDomain,
  type GateDefinition,
  type GateId,
} from '../core/platforms';
import type {
  BlockContext,
  DashboardState,
  GrantResult,
  PopupState,
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
  type ChannelEntry,
  type GateSetting,
  type NudgeSettings,
  type SiteRule,
} from '../core/settingsSchema';
import { allTimeTotals, lastNDayKeys, totalActiveSeconds } from '../core/stats';
import * as strict from '../core/strictMode';
import { surfaceKey } from '../core/surfaceKeys';
import type { ActiveRule, BlockDecision, SiteMode } from '../core/types';
import { ensureScheduleAlarm } from './alarmsHub';
import { applyRules } from './dnr';
import {
  loadAllUsage,
  loadDay,
  loadPassLedger,
  loadSettings,
  saveSettings,
  savePassLedger,
  todayUsageMs,
} from './storage';
import { grantTempAllow } from './tempAllow';
import { logBlocked, logWalkedAway, onActivityEvent } from './tracker';

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

/**
 * The enabled rule covering `domain`, or null.
 *
 * Subdomain-aware via `ruleForHost`, because DNR always was: a rule on `wikipedia.org`
 * governs `en.wikipedia.org`, and an exact string compare here is what made the popup
 * report "Not blocked" on a page the network layer was already enforcing.
 */
function ruleForDomain(settings: NudgeSettings, domain: string): SiteRule | null {
  if (domain === '') return null;
  return ruleForHost(settings.rules, domain);
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

async function buildBlockContext(target: string, now: Date): Promise<BlockContext> {
  const settings = await loadSettings();
  const domain = extractDomain(target) ?? '';
  const usedMs = domain === '' ? 0 : await todayUsageMs(domain, now);
  const rule = ruleForDomain(settings, domain);

  let decision: BlockDecision = { type: 'ALLOW' };
  let gateId: GateId | null = null;
  let gateLabel: string | null = null;

  if (settings.globalEnabled) {
    // The gate is asked FIRST because the gate redirect outranks the site redirect at the
    // network layer (see dnr.ts priorities). If the block page resolved the site instead,
    // a Shorts gate on an otherwise-ALLOWed YouTube would render as "no rule applies" and
    // send the user straight back into the redirect.
    const gate = rule === null ? null : gateForPage(rule, target);
    if (gate !== null) {
      const surfaceMs = await todayUsageMs(surfaceKey(domain, gate.definition.id), now);
      const verdict = gateAppliesNow(gate.setting, surfaceMs);
      const mode = appliedBlockMode(verdict);
      if (mode !== null && rule !== null) {
        gateId = gate.definition.id;
        gateLabel = gate.definition.label;
        decision = evaluate(
          [gateAsActiveRule(rule, gate, verdict.delaySeconds, mode)],
          surfaceMs,
          now,
        );
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
    allowedChannels: allowedChannelsFor(rule),
  };
}

/**
 * The channels a YouTube whitelist still lets through, for the block page to link to.
 *
 * Without these links "block YouTube except these channels" is technically correct and
 * practically useless: the home feed, search and subscriptions are all redirected, so the
 * only way into an allowed channel is typing a URL from memory. The block page has to BE
 * the way in.
 */
function allowedChannelsFor(rule: SiteRule | null): ChannelEntry[] {
  const youtube = rule?.features?.youtube;
  if (youtube === undefined || youtube.channelMode !== 'WHITELIST') return [];
  return youtube.channels;
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

  const until = await grantTempAllow(
    context.domain,
    context.tempAllowMinutes,
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

  const domain = extractDomain(target);
  if (domain === null) return { ok: false, until: 0, reason: 'unknown-site' };

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
    currentFeatureSummary: currentRule === null ? null : featureSummary(currentRule),
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
  const domain = extractDomain(url) ?? '';
  const rule = ruleForDomain(settings, domain);
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
      const surfaceMs = await todayUsageMs(surfaceKey(domain, definition.id), now);
      const gateVerdict = gateAppliesNow(setting, surfaceMs);
      gates.push({
        id: definition.id,
        mode: appliedBlockMode(gateVerdict) ?? 'ALLOW',
        delaySeconds: gateVerdict.delaySeconds,
        limitReached: gateVerdict.reason === 'limit-exhausted',
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
    grayscale: rule.grayscale,
    gates,
    hides: features?.hides ?? {},
    youtube: features?.youtube ?? null,
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
  const normalized = migrateSettings(next);

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
  return { ok: true };
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

/** Dispatch one request. Throwing here would hang the caller, so it never throws. */
export async function handleRequest(request: Request, now: Date = new Date()): Promise<unknown> {
  switch (request.type) {
    case 'GET_BLOCK_CONTEXT': {
      const context = await buildBlockContext(request.target, now);
      // The interstitial rendering IS the block event (Android's "Blocked" counter).
      if (context.decision.type === 'BLOCK' && context.domain !== '') {
        await logBlocked(context.domain, now);
      }
      return context;
    }
    case 'COMPLETE_PAUSE':
      return completePause(request.target, now);
    case 'WALKED_AWAY': {
      const domain = extractDomain(request.target);
      if (domain !== null) await logWalkedAway(domain, now);
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
    case 'GET_SITE_CONFIG':
      return buildSiteConfig(request.url, now);
    case 'SET_GRAYSCALE':
      return setGrayscale(
        request.domain,
        request.grayscale,
        request.challengeResponse,
        now,
      );
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
  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    handleRequest(message as Request)
      .then(sendResponse)
      .catch((error: unknown) => {
        console.error('[nudge] message handler failed', error);
        sendResponse({ ok: false, reason: 'handler-error' });
      });
    return true;
  });
}
