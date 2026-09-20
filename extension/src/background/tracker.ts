/**
 * Usage tracking — event-driven timestamp accounting.
 *
 * THE MV3 RULE (ext-01 §2): never accumulate elapsed time in a service-worker global. The
 * worker is torn down after ~30s idle, so anything held in memory between two events is
 * lost. Instead every relevant event does one atomic step:
 *
 *    read `since` from storage.session -> add (now - since) to today's rollup -> write
 *    the new `since`.
 *
 * Each step stands alone, so arbitrary teardown between events costs nothing. A heartbeat
 * alarm is the backstop for a tab that simply sits open with no events firing.
 *
 * Time is counted only when Chrome has OS focus AND the machine is not idle/locked —
 * `windows.onFocusChanged` and `chrome.idle` are what make "screen time" mean attention
 * rather than "a window was open".
 *
 * ## v0.2: an interval is credited to TWO buckets
 *
 * A feature gate can carry its own budget ("10 minutes of Shorts a day, YouTube itself
 * unlimited"), so a focused tab's time is attributed to the site bucket (`youtube.com`) AND,
 * when its URL is on a gate surface, to that surface's bucket (`youtube.com#shorts`). Both
 * live in the same day map; `core/surfaceKeys.ts` explains why, and carries the filter every
 * place that LISTS domains has to apply so a phantom "youtube.com#shorts" site never shows
 * up in the stats table.
 *
 * The surface bucket is filled for any known platform, whether or not a gate is configured.
 * Measuring only what is currently budgeted would mean the dashboard's "of which Shorts"
 * line starts at zero the moment a user turns a budget ON, which reads as a bug and makes
 * the number useless for deciding what the budget should be.
 */

import { extractDomain } from '../core/domainMatcher';
import { rulesForDomain, usageKeyForHost } from '../core/ruleResolver';
import { gateForUrl, platformForDomain, type GateId } from '../core/platforms';
import { localDayKey } from '../core/scheduleEvaluator';
import { addActiveSeconds, recordBlocked, recordWalkedAway } from '../core/stats';
import { crossesLimit, tightestLimit } from '../core/budgets';
import { surfaceKey } from '../core/surfaceKeys';
import type { SiteRule } from '../core/settingsSchema';
import {
  loadSettings,
  loadTrackerState,
  saveTrackerState,
  todayUsageMs,
  updateDomainUsage,
} from './storage';
import { applyRules, redirectOpenTabs } from './dnr';
import { revokeTempAllow } from './tempAllow';
import { refreshBadge } from './badge';

/** Idle threshold handed to chrome.idle (seconds). */
export const IDLE_DETECTION_SECONDS = 60;

/**
 * Upper bound on the time a single accounting step may attribute.
 *
 * The heartbeat fires every minute, so a legitimate gap is ~60s. A much larger gap means
 * the machine slept, the clock jumped, or the worker was starved — attributing it would
 * silently inflate "screen time" (and could trip a daily limit the user never used). Cap
 * generously at 3 heartbeats and drop the excess.
 */
export const MAX_ATTRIBUTABLE_MS = 3 * 60_000;

/** What is accruing time right now: the site, and the exact page inside it. */
export interface TrackedTarget {
  domain: string;
  url: string;
}

/** The page that should be accruing time right now, or null if nothing should. */
export async function currentTrackedTarget(): Promise<TrackedTarget | null> {
  try {
    const idleState = await chrome.idle.queryState(IDLE_DETECTION_SECONDS);
    if (idleState !== 'active') return null;

    const lastFocused = await chrome.windows.getLastFocused();
    if (lastFocused.focused === false) return null;

    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (tab?.url === undefined) return null;
    const domain = extractDomain(tab.url);
    return domain === null ? null : { domain, url: tab.url };
  } catch {
    return null;
  }
}

/**
 * Which feature surface of `domain` the URL is on, or null.
 *
 * Resolved from the platform REGISTRY rather than from the user's rule, so the same URL
 * always lands in the same bucket no matter how the rule is configured at the time. A
 * surface whose bucket only exists while a gate happens to be switched on would reset its
 * own history every time the user changed their mind.
 */
export function trackedGate(domain: string, url: string | null): GateId | null {
  if (url === null) return null;
  const platform = platformForDomain(domain);
  return platform === null ? null : gateForUrl(platform.id, url);
}

/**
 * Close out the currently-tracked interval and start a new one on `next`.
 * Safe to call from any event handler; idempotent when nothing changed.
 */
export async function accountAndSwitch(
  next: TrackedTarget | null,
  now: number = Date.now(),
): Promise<void> {
  const state = await loadTrackerState();
  await saveTrackerState({
    domain: next?.domain ?? null,
    url: next?.url ?? null,
    since: now,
  });

  const domain = state.domain;
  const elapsed = now - state.since;
  if (domain === null || elapsed <= 0) return;

  const attributable = Math.min(elapsed, MAX_ATTRIBUTABLE_MS);
  const seconds = Math.floor(attributable / 1000);
  if (seconds <= 0) return;

  const at = new Date(now);
  const dayKey = localDayKey(at);
  const addedMs = seconds * 1000;

  // Attribute the time to the RULE that governs this host, not to the host string.
  // `extractDomain` only strips www./m., so browsing en.wikipedia.org used to fill an
  // `en.wikipedia.org` bucket while every budget check read `wikipedia.org`, the limit
  // could never fire, however long you sat there (live QA 2026-09-20). With no rule
  // covering the host this is the host itself, so un-ruled sites track exactly as before.
  const settings = await loadSettings();
  const usageKey = usageKeyForHost(settings.rules, domain);

  const previousSiteMs = await todayUsageMs(usageKey, at);
  await updateDomainUsage(dayKey, usageKey, (usage) =>
    addActiveSeconds(usage, seconds, at.getHours()),
  );

  // The gate is resolved from the RULE's domain too: platformForDomain('en.wikipedia.org')
  // is null, and a surface bucket keyed off the host would never be read back.
  const gateId = trackedGate(usageKey, state.url);
  let previousSurfaceMs = 0;
  if (gateId !== null) {
    const key = surfaceKey(usageKey, gateId);
    previousSurfaceMs = await todayUsageMs(key, at);
    await updateDomainUsage(dayKey, key, (usage) =>
      addActiveSeconds(usage, seconds, at.getHours()),
    );
  }

  await enforceBudget(
    {
      domain: usageKey,
      gateId,
      previousSiteMs,
      nextSiteMs: previousSiteMs + addedMs,
      previousSurfaceMs,
      nextSurfaceMs: previousSurfaceMs + addedMs,
    },
    at,
  );
}

/** One accounting step's before/after numbers, for both buckets it may have touched. */
interface AccountingStep {
  domain: string;
  gateId: GateId | null;
  previousSiteMs: number;
  nextSiteMs: number;
  previousSurfaceMs: number;
  nextSurfaceMs: number;
}

/** The tightest budget configured for one gate across the rules covering a domain. */
function tightestGateLimit(rules: readonly SiteRule[], gateId: GateId): number | null {
  let min: number | null = null;
  for (const rule of rules) {
    const limit = rule.features?.gates[gateId]?.dailyLimitMinutes ?? null;
    if (limit === null) continue;
    if (min === null || limit < min) min = limit;
  }
  return min;
}

/**
 * Flip a site — or one feature surface of it — to blocked the instant its daily limit is
 * crossed.
 *
 * Runs inside the same accounting step that crossed the threshold (not on a separate poll),
 * so the block is immediate rather than up to a heartbeat late — ext-01 §4. `crossesLimit`
 * is true only on the transition, so the work happens exactly once.
 *
 * Three things have to happen together on a crossing, and the order matters:
 *  1. revoke any in-flight temporary grant — an exhausted budget outranks it (Android
 *     forces HARD_BLOCK);
 *  2. RECOMPILE the DNR rule set — the redirect for a limit-only ALLOW rule (or a gate
 *     whose own budget just ran out) does not exist until now, so without this the user is
 *     bounced off the open tab in step 3 and then walks straight back in;
 *  3. push the open tabs to the block page.
 *
 * A gate crossing redirects only the tabs ON that surface. Spending the Shorts budget must
 * not close the ordinary YouTube page in the next tab: the site's own budget is untouched,
 * and enforcing more than the user configured is how a feature loses their trust.
 */
async function enforceBudget(step: AccountingStep, now: Date): Promise<void> {
  const settings = await loadSettings();
  if (!settings.globalEnabled) return;

  // `step.domain` is already the matching rule's domain (see accountAndSwitch), so this is
  // an exact hit in practice; going through the shared resolver keeps one definition of
  // "which rules cover this" rather than a fifth private copy.
  const rules = rulesForDomain(settings.rules, step.domain);
  if (rules.length === 0) return;

  const siteLimit = tightestLimit(rules);
  const siteCrossed = crossesLimit(siteLimit, step.previousSiteMs, step.nextSiteMs);

  const gateId = step.gateId;
  const gateLimit = gateId === null ? null : tightestGateLimit(rules, gateId);
  const gateCrossed =
    gateId !== null && crossesLimit(gateLimit, step.previousSurfaceMs, step.nextSurfaceMs);

  if (!siteCrossed && !gateCrossed) return;

  await revokeTempAllow(step.domain, now.getTime());
  await applyRules(settings, now);

  if (siteCrossed) {
    // The site redirect already covers every tab on the domain, gate surfaces included.
    await redirectOpenTabs(step.domain);
  } else {
    await redirectOpenTabs(
      step.domain,
      (url) => trackedGate(step.domain, url) === gateId,
    );
  }
  await refreshBadge();
}

/** Re-evaluate what should be tracked right now. The single entry point for all events. */
export async function onActivityEvent(now: number = Date.now()): Promise<void> {
  const next = await currentTrackedTarget();
  await accountAndSwitch(next, now);
  await refreshBadge();
}

/** Record that a block was shown (dashboard "Blocked" counter). */
export async function logBlocked(domain: string, now: Date = new Date()): Promise<void> {
  await updateDomainUsage(localDayKey(now), domain, recordBlocked);
}

/** Record a "I changed my mind" bail (dashboard "Walked Away" counter). */
export async function logWalkedAway(domain: string, now: Date = new Date()): Promise<void> {
  await updateDomainUsage(localDayKey(now), domain, recordWalkedAway);
}
