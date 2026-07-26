/**
 * Temporary access grants — the browser analog of Android's "the app opens" after a
 * completed Delay/Breathing pause.
 *
 * Per-ORIGIN for N minutes, not per-tab (ext-08 fixed decision: DNR matches URLs, not tab
 * ids, so per-tab exemption would need a fragile service-worker layer on top; per-origin is
 * also what competitors ship).
 *
 * State lives in storage.session and the DNR allow-rules are SESSION rules, so both halves
 * die together on browser restart — there is no way to end up with a grant recorded but not
 * enforced, or enforced but not recorded.
 *
 * Grants carry a TIER (`PAUSE` / `EMERGENCY`) because Lights Off made the two behave
 * differently: a lockdown supersedes a pause grant but not the daily Escape Hatch. Both still
 * live in ONE map with ONE alarm set, so everything downstream — expiry, revocation on a
 * crossed budget, re-arming after a worker restart — keeps working on both without a second
 * parallel bookkeeping path to drift out of sync.
 */

import { normalizeToBaseDomain } from '../core/domainMatcher';
import { applyTempAllows, type TempAllowGrant } from './dnr';
import {
  loadTempAllow,
  saveTempAllow,
  type GrantTier,
  type TempAllowMap,
} from './storage';

export const TEMP_ALLOW_ALARM_PREFIX = 'nudge:tempallow:';

export function tempAllowAlarmName(domain: string): string {
  return `${TEMP_ALLOW_ALARM_PREFIX}${normalizeToBaseDomain(domain)}`;
}

export function domainFromAlarm(alarmName: string): string | null {
  return alarmName.startsWith(TEMP_ALLOW_ALARM_PREFIX)
    ? alarmName.slice(TEMP_ALLOW_ALARM_PREFIX.length)
    : null;
}

/** Drop expired entries. Pure so it can be unit tested without chrome. */
export function pruneExpired(map: TempAllowMap, now: number): TempAllowMap {
  const next: TempAllowMap = {};
  for (const [domain, entry] of Object.entries(map)) {
    if (entry.until > now) next[domain] = entry;
  }
  return next;
}

/** The live grants as the network layer wants them. Pure. */
export function grantsFromMap(map: TempAllowMap): TempAllowGrant[] {
  return Object.entries(map).map(([domain, entry]) => ({ domain, tier: entry.tier }));
}

/**
 * Re-derive the DNR session allow-rules from the stored grant map, dropping anything that
 * has expired. Every mutation funnels through here so the rules are always a pure function
 * of state — a service-worker restart mid-grant can never leave the two out of step.
 */
async function syncFromMap(map: TempAllowMap, now: number): Promise<TempAllowMap> {
  const live = pruneExpired(map, now);
  await saveTempAllow(live);
  await applyTempAllows(grantsFromMap(live));
  return live;
}

/**
 * Grant `domain` access for `minutes`, and schedule its expiry.
 *
 * `tier` defaults to PAUSE — the weaker grant — so a future caller that forgets to think about
 * it cannot accidentally mint something that punches through a Lights Off lockdown.
 */
export async function grantTempAllow(
  domain: string,
  minutes: number,
  now: number = Date.now(),
  tier: GrantTier = 'PAUSE',
): Promise<number> {
  const base = normalizeToBaseDomain(domain);
  const until = now + Math.max(1, minutes) * 60_000;
  const map = await loadTempAllow();
  map[base] = { until, tier };
  await syncFromMap(map, now);
  // Absolute `when` rather than delayInMinutes: alarms below 1 minute are unreliable in
  // production builds, and an absolute time survives a worker restart intact.
  await chrome.alarms.create(tempAllowAlarmName(base), { when: until });
  return until;
}

/** Revoke a grant immediately (used when a daily budget is exhausted mid-grant). */
export async function revokeTempAllow(
  domain: string,
  now: number = Date.now(),
): Promise<void> {
  const base = normalizeToBaseDomain(domain);
  const map = await loadTempAllow();
  delete map[base];
  await syncFromMap(map, now);
  await chrome.alarms.clear(tempAllowAlarmName(base));
}

/**
 * Handle an expiry alarm. The allow-rule is removed so the NEXT navigation re-blocks; the
 * page currently open is deliberately left alone (no yanking a page out from under the
 * user mid-read — ext-08 flow 2).
 */
export async function handleTempAllowExpiry(
  domain: string,
  now: number = Date.now(),
): Promise<void> {
  const map = await loadTempAllow();
  delete map[normalizeToBaseDomain(domain)];
  await syncFromMap(map, now);
}

export async function isTempAllowed(
  domain: string,
  now: number = Date.now(),
): Promise<boolean> {
  const map = await loadTempAllow();
  const entry = map[normalizeToBaseDomain(domain)];
  return entry !== undefined && entry.until > now;
}

/**
 * Re-derive rules and re-arm alarms on every service-worker startup.
 *
 * Chrome's own guidance: alarms are not guaranteed to survive a restart, so important ones
 * must be re-verified when the worker starts (ext-01 §4).
 */
export async function rearmTempAllows(now: number = Date.now()): Promise<void> {
  const live = await syncFromMap(await loadTempAllow(), now);
  await Promise.all(
    Object.entries(live).map(([domain, entry]) =>
      chrome.alarms.create(tempAllowAlarmName(domain), { when: entry.until }),
    ),
  );
}
