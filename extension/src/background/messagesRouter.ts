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
 */

import { evaluate } from '../core/blockEngine';
import { remainingMs, tightestLimit } from '../core/budgets';
import { extractDomain, normalizeUserInput } from '../core/domainMatcher';
import * as pass from '../core/emergencyPass';
import {
  isAllowedDomain,
  LIGHTS_OFF_RULE_NAME,
  resolveLightsOff,
} from '../core/lightsOff';
import {
  DEFAULT_DELAY_SUBTITLES,
  DEFAULT_DELAY_TITLES,
  DEFAULT_HARD_BLOCK_MESSAGES,
  pickRandom,
  resolvePool,
} from '../core/messages';
import type {
  BlockContext,
  DashboardState,
  GrantResult,
  LightsOffBlockInfo,
  PopupState,
  Request,
  SaveResult,
  YoutubeConfig,
} from '../core/protocol';
import { resolveActiveRules } from '../core/ruleResolver';
import { localDayKey } from '../core/scheduleEvaluator';
import {
  DEFAULT_DELAY_SECONDS,
  migrateSettings,
  type NudgeSettings,
  type SiteRule,
} from '../core/settingsSchema';
import { allTimeTotals, lastNDayKeys, totalActiveSeconds } from '../core/stats';
import * as strict from '../core/strictMode';
import { block, type BlockDecision, type BlockMode } from '../core/types';
import { formatMinuteOfDay } from '../ui/format';
import { scheduleLightsOffBoundaries } from './alarmsHub';
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
 * Lights Off, evaluated BEFORE the engine — the redirect-loop guard.
 *
 * THE ENGINE INVARIANT (extension/CLAUDE.md): ALLOW means "no rule applies here", and DNR has
 * already redirected by the time anything in this file runs. Lights Off blocks almost the
 * whole internet, and almost the whole internet is RULELESS — so handing a lockdown-blocked
 * navigation to `evaluate()` would return ALLOW, the block page would obediently send the user
 * back to the site, DNR would redirect again, forever, hammering the service worker. This repo
 * has already shipped that bug once (Hard Block + Daily Limit; `e2e/redirectLoop.spec.ts`).
 *
 * So the answer is computed here, from the same `resolveLightsOff` the DNR compiler uses, and
 * it SHORT-CIRCUITS: a lockdown-blocked navigation never reaches the engine at all. A
 * whitelisted host, or an inactive window, falls through to the unchanged per-site path.
 *
 * It resolves to a HARD_BLOCK-shaped decision rather than a new `BlockDecision.type` so every
 * existing consumer keeps working — `completePause` already refuses to grant access to a
 * HARD_BLOCK, which is what makes a lockdown uncompletable for free.
 */
function resolveLightsOffBlock(
  settings: NudgeSettings,
  domain: string,
  now: Date,
): { decision: BlockDecision; info: LightsOffBlockInfo } | null {
  const state = resolveLightsOff(settings, now);
  if (!state.active) return null;
  // An unreadable host (no dot, so `extractDomain` gave up — e.g. `http://localhost:3000`)
  // cannot be matched against the allow-list, and DNR's catch-all has already redirected it.
  // Treat it as blocked: returning ALLOW here is precisely the infinite loop above. Users who
  // need such a host can add it to the allow-list, which `normalizeAllowedDomain` accepts.
  if (isAllowedDomain(domain, state.allowedDomains)) return null;

  return {
    decision: block({ mode: 'HARD_BLOCK', ruleName: LIGHTS_OFF_RULE_NAME }),
    info: {
      untilLabel: formatMinuteOfDay(state.untilMinute ?? 0),
      allowedDomains: state.allowedDomains,
    },
  };
}

async function buildBlockContext(target: string, now: Date): Promise<BlockContext> {
  const settings = await loadSettings();
  const domain = extractDomain(target) ?? '';
  const usedMs = domain === '' ? 0 : await todayUsageMs(domain, now);

  // Lights Off first, and never both: a lockdown OVERRIDES per-site rules, so its verdict is
  // not merged with the engine's, it replaces it.
  const lights = settings.globalEnabled ? resolveLightsOffBlock(settings, domain, now) : null;
  const decision =
    lights !== null
      ? lights.decision
      : settings.globalEnabled
        ? evaluate(resolveActiveRules(settings.rules, domain, now), usedMs, now)
        : { type: 'ALLOW' as const };

  const ledger = pass.parse(await loadPassLedger());
  const passAvailable = pass.canUseGlobal(ledger, now.getTime(), pass.LOCKOUT_MS);

  return {
    target,
    domain,
    decision,
    lightsOff: lights?.info ?? null,
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

  const until = await grantTempAllow(
    context.domain,
    context.tempAllowMinutes,
    now.getTime(),
  );
  return { ok: true, until };
}

/**
 * The Escape Hatch: one 2-minute window per rolling 24h, globally.
 *
 * Usable DURING a Lights Off lockdown (Anti's locked decision — a rationed valve beats a
 * catastrophic lock, and the Commitment Lock is how you give it up). That is why the grant is
 * minted at the EMERGENCY tier: a PAUSE-tier session rule sits below the lockdown's catch-all
 * and would have made this button render, click, and silently do nothing.
 */
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
    'EMERGENCY',
  );
  return { ok: true, until };
}

async function buildPopupState(now: Date): Promise<PopupState> {
  const settings = await loadSettings();
  const day = await loadDay(localDayKey(now));

  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  const currentDomain = tab?.url === undefined ? null : extractDomain(tab.url);

  const rules =
    currentDomain === null
      ? []
      : settings.rules.filter((rule) => rule.domain === currentDomain);
  const limit = tightestLimit(rules.filter((rule) => rule.enabled));
  const usedMs = currentDomain === null ? 0 : (day[currentDomain]?.activeSec ?? 0) * 1000;

  return {
    globalEnabled: settings.globalEnabled,
    todayTotalSeconds: totalActiveSeconds(day),
    currentDomain,
    currentRule: rules[0] ?? null,
    currentRemainingMs: remainingMs(limit, usedMs),
    currentUsageSeconds: Math.floor(usedMs / 1000),
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
  // A Lights Off schedule edit moves its boundary alarms. The storage listener in
  // background.ts re-arms them too, but this save path already writes DNR directly, so it
  // re-arms directly as well rather than depending on an event to land — `create` replaces an
  // existing alarm by name, so doing it twice is free.
  await scheduleLightsOffBoundaries(normalized, now);
  await onActivityEvent(now.getTime());
  return { ok: true };
}

async function addSite(
  domain: string,
  mode: BlockMode,
  delaySeconds: number,
  now: Date,
): Promise<{ ok: boolean; reason?: string }> {
  const normalizedDomain = normalizeUserInput(domain);
  if (normalizedDomain === null) return { ok: false, reason: 'invalid-domain' };

  const settings = await loadSettings();
  if (settings.rules.some((rule) => rule.domain === normalizedDomain)) {
    return { ok: false, reason: 'already-blocked' };
  }

  const rule: SiteRule = {
    id: `rule-${normalizedDomain}-${now.getTime()}`,
    domain: normalizedDomain,
    mode,
    delaySeconds: delaySeconds > 0 ? delaySeconds : DEFAULT_DELAY_SECONDS,
    dailyLimitMinutes: null,
    enabled: true,
    createdAt: now.getTime(),
    showTimeRemaining: false,
    schedule: null,
  };

  // Adding a rule STRENGTHENS protection, so it is never gated by Strict Mode.
  const updated = migrateSettings({ ...settings, rules: [...settings.rules, rule] });
  await saveSettings(updated);
  await applyRules(updated, now);
  return { ok: true };
}

/**
 * The v1.1 YouTube feature fields, passed through verbatim.
 *
 * Kept as one helper so the "globally disabled" branch and the live branch can never drift
 * apart — a field added to one and forgotten in the other is exactly how a toggle ends up
 * silently dead on one path.
 */
function youtubeFeatureFields(
  settings: NudgeSettings,
): Omit<YoutubeConfig, 'enabled' | 'hideShortsShelf' | 'shortsMode' | 'shortsDelaySeconds'> {
  const yt = settings.youtube;
  return {
    channelMode: yt.channelMode,
    channels: yt.channels,
    channelBlockMode: yt.channelBlockMode,
    channelDelaySeconds: yt.channelDelaySeconds,
    grayScreen: yt.grayScreen,
    hideHomeFeed: yt.hideHomeFeed,
    hideSidebarRecs: yt.hideSidebarRecs,
    hideEndScreen: yt.hideEndScreen,
    hideComments: yt.hideComments,
    disableAutoplay: yt.disableAutoplay,
  };
}

async function buildYoutubeConfig(now: Date): Promise<YoutubeConfig> {
  const settings = await loadSettings();
  if (!settings.globalEnabled) {
    // The master toggle means "behave as if uninstalled": every feature off, not just
    // blocking. Otherwise a disabled Nudge would still be greying YouTube out.
    return {
      enabled: false,
      hideShortsShelf: false,
      shortsMode: 'ALLOW',
      shortsDelaySeconds: settings.youtube.shortsDelaySeconds,
      ...youtubeFeatureFields(settings),
      channelMode: 'OFF',
      grayScreen: false,
      hideHomeFeed: false,
      hideSidebarRecs: false,
      hideEndScreen: false,
      hideComments: false,
      disableAutoplay: false,
    };
  }

  let shortsMode: BlockMode | 'ALLOW';
  if (settings.youtube.shortsMode === 'INHERIT') {
    // Defer to whatever the site rule for youtube.com decides right now.
    const usedMs = await todayUsageMs('youtube.com', now);
    const decision = evaluate(
      resolveActiveRules(settings.rules, 'youtube.com', now),
      usedMs,
      now,
    );
    shortsMode = decision.type === 'ALLOW' ? 'ALLOW' : decision.mode;
  } else {
    shortsMode = settings.youtube.shortsMode;
  }

  return {
    enabled: true,
    hideShortsShelf: settings.youtube.hideShortsShelf,
    shortsMode,
    shortsDelaySeconds: settings.youtube.shortsDelaySeconds,
    ...youtubeFeatureFields(settings),
  };
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
    case 'GET_YOUTUBE_CONFIG':
      return buildYoutubeConfig(now);
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
