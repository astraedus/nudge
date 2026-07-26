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
 */

import { extractDomain } from '../core/domainMatcher';
import { localDayKey } from '../core/scheduleEvaluator';
import { addActiveSeconds, recordBlocked, recordWalkedAway } from '../core/stats';
import { crossesLimit, tightestLimit } from '../core/budgets';
import { loadSettings } from './storage';
import {
  loadTrackerState,
  saveTrackerState,
  todayUsageMs,
  updateDomainUsage,
} from './storage';
import { redirectOpenTabs } from './dnr';
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

/** The domain that should be accruing time right now, or null if nothing should. */
export async function currentTrackedDomain(): Promise<string | null> {
  try {
    const idleState = await chrome.idle.queryState(IDLE_DETECTION_SECONDS);
    if (idleState !== 'active') return null;

    const lastFocused = await chrome.windows.getLastFocused();
    if (lastFocused.focused === false) return null;

    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (tab?.url === undefined) return null;
    return extractDomain(tab.url);
  } catch {
    return null;
  }
}

/**
 * Close out the currently-tracked interval and start a new one on `nextDomain`.
 * Safe to call from any event handler; idempotent when nothing changed.
 */
export async function accountAndSwitch(
  nextDomain: string | null,
  now: number = Date.now(),
): Promise<void> {
  const state = await loadTrackerState();
  await saveTrackerState({ domain: nextDomain, since: now });

  const elapsed = now - state.since;
  if (state.domain === null || elapsed <= 0) return;

  const attributable = Math.min(elapsed, MAX_ATTRIBUTABLE_MS);
  const seconds = Math.floor(attributable / 1000);
  if (seconds <= 0) return;

  const at = new Date(now);
  const previousMs = await todayUsageMs(state.domain, at);
  await updateDomainUsage(localDayKey(at), state.domain, (usage) =>
    addActiveSeconds(usage, seconds, at.getHours()),
  );
  await enforceBudget(state.domain, previousMs, previousMs + seconds * 1000, at);
}

/**
 * Flip a site to blocked the instant its daily limit is crossed.
 *
 * Runs inside the same accounting step that crossed the threshold (not on a separate poll),
 * so the block is immediate rather than up to a heartbeat late — ext-01 §4. `crossesLimit`
 * is true only on the transition, so open tabs are redirected exactly once.
 */
async function enforceBudget(
  domain: string,
  previousMs: number,
  nextMs: number,
  now: Date,
): Promise<void> {
  const settings = await loadSettings();
  if (!settings.globalEnabled) return;

  const rules = settings.rules.filter((rule) => rule.enabled && rule.domain === domain);
  if (rules.length === 0) return;

  const limit = tightestLimit(rules);
  if (limit === null) return;

  if (crossesLimit(limit, previousMs, nextMs)) {
    // An exhausted budget outranks an in-flight temporary grant (Android forces HARD_BLOCK).
    await revokeTempAllow(domain, now.getTime());
    await redirectOpenTabs(domain);
  }
}

/** Re-evaluate what should be tracked right now. The single entry point for all events. */
export async function onActivityEvent(now: number = Date.now()): Promise<void> {
  const next = await currentTrackedDomain();
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
