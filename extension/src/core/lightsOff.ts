/**
 * Lights Off — the scheduled GLOBAL lockdown. PURE.
 *
 * Nudge's per-site rules are allow-by-default: you name the bad things and block them, so
 * anything you haven't named yet is wide open. Lights Off INVERTS that for a window: while it
 * is on, every website is off except a small allow-list. The inversion IS the feature.
 *
 * This module answers three questions and nothing else, so both the network layer
 * (background/dnr.ts) and the block page (background/messagesRouter.ts) derive their answer
 * from the SAME function rather than each re-deriving "is it on right now":
 *
 *   1. is a window active at `now`      -> `resolveLightsOff`
 *   2. is this host on the allow-list   -> `isAllowedDomain`
 *   3. when does the next boundary fall -> `nextLightsOffStartAt` / `nextLightsOffEndAt`
 *
 * That single source of truth matters more here than anywhere else in the codebase: DNR
 * decides whether to redirect, the engine decides what to render, and if the two ever
 * disagree the user is bounced between the site and the block page forever (see the ENGINE
 * INVARIANT lesson in extension/CLAUDE.md).
 *
 * NO clock reads of its own — `now` is always a parameter, exactly like the block engine.
 */

import { isScheduleActiveAt, minutesSinceMidnight } from './scheduleEvaluator';
import type {
  LightsOffProfile,
  LightsOffStrictness,
  NudgeSettings,
} from './settingsSchema';

/** Shown in the "Rule: X" footer, and the Android overlay's `ruleName`. Parity matters. */
export const LIGHTS_OFF_RULE_NAME = 'Lights Off';

export interface LightsOffState {
  active: boolean;
  /** Hosts still reachable. Subdomains of an entry count too. Empty when inactive. */
  allowedDomains: string[];
  /** The STRICTEST strictness among the windows currently contributing. */
  strictness: LightsOffStrictness;
  /** Epoch ms the lockdown lifts; null when inactive. */
  endsAt: number | null;
  /**
   * Minute-of-day the lockdown lifts (0..1439), for display. null when inactive.
   * Kept numeric so `core/` never has to reach into the presentation layer to format it.
   */
  untilMinute: number | null;
}

export const LIGHTS_OFF_INACTIVE: LightsOffState = {
  active: false,
  allowedDomains: [],
  strictness: 'BASIC',
  endsAt: null,
  untilMinute: null,
};

/**
 * Epoch ms of the next time local wall-clock hits `minuteOfDay`.
 *
 * Built from local calendar fields rather than by adding a fixed 24h so a DST transition
 * shortens/lengthens the interval instead of drifting the boundary off the wall clock —
 * the same reasoning as `msUntilNextLocalMidnight`.
 */
export function nextOccurrenceAt(now: Date, minuteOfDay: number): number {
  const at = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate(),
    Math.floor(minuteOfDay / 60),
    minuteOfDay % 60,
    0,
    0,
  );
  if (at.getTime() <= now.getTime()) at.setDate(at.getDate() + 1);
  return at.getTime();
}

/** True when this profile's window covers `now`. A disabled profile is never active. */
export function isProfileActiveAt(profile: LightsOffProfile, now: Date): boolean {
  if (!profile.enabled) return false;
  return isScheduleActiveAt(
    {
      days: profile.days,
      startMinute: profile.startMinute,
      endMinute: profile.endMinute,
    },
    now,
  );
}

/** True while a manual "start now until X" lockdown is still running. */
export function isManualActiveAt(settings: NudgeSettings, now: Date): boolean {
  const until = settings.lightsOff.manualUntil;
  return until !== null && until > now.getTime();
}

/**
 * The profile a manual lockdown borrows its allow-list and strictness from.
 *
 * v1 has exactly one profile, and coercion guarantees the list is never empty, so this is
 * always `profiles[0]`. It exists as a named function so the multi-profile follow-up has one
 * place to change ("which profile does the Start-now button mean?") rather than an index
 * scattered across the worker.
 */
export function manualProfile(settings: NudgeSettings): LightsOffProfile | null {
  return settings.lightsOff.profiles[0] ?? null;
}

/**
 * Resolve everything about Lights Off at `now`.
 *
 * The master toggle is checked HERE, not by each caller: `globalEnabled === false` means
 * "behave as if uninstalled", and one gate inside the shared resolver is what stops a future
 * caller from forgetting it (the Android v1.9.2 lesson — a second enforcement path that
 * skipped the master gate kept kicking users after they turned Nudge off).
 *
 * Composition when several windows overlap: allow-lists UNION (a window saying "these are
 * fine" should not be overridden by another window's shorter list), strictness takes the
 * STRICTEST, and the lockdown lifts at the LATEST end — i.e. it stays on until every
 * contributing window is done.
 */
export function resolveLightsOff(settings: NudgeSettings, now: Date): LightsOffState {
  if (!settings.globalEnabled) return LIGHTS_OFF_INACTIVE;

  const contributing: LightsOffProfile[] = [];
  const ends: number[] = [];

  for (const profile of settings.lightsOff.profiles) {
    if (!isProfileActiveAt(profile, now)) continue;
    contributing.push(profile);
    ends.push(nextOccurrenceAt(now, profile.endMinute));
  }

  const manualUntil = settings.lightsOff.manualUntil;
  if (manualUntil !== null && manualUntil > now.getTime()) {
    const borrowed = manualProfile(settings);
    // A manual lockdown runs even when the profile's own schedule toggle is off, so its
    // allow-list still applies: "start now" must not mean "and lose your allowed sites".
    if (borrowed !== null && !contributing.includes(borrowed)) contributing.push(borrowed);
    ends.push(manualUntil);
  }

  if (ends.length === 0) return LIGHTS_OFF_INACTIVE;

  const endsAt = ends.reduce((latest, candidate) => Math.max(latest, candidate));
  const allowedDomains = [
    ...new Set(contributing.flatMap((profile) => profile.allowedDomains)),
  ].sort();

  return {
    active: true,
    allowedDomains,
    strictness: contributing.some((profile) => profile.strictness === 'STRICT')
      ? 'STRICT'
      : 'BASIC',
    endsAt,
    untilMinute: minutesSinceMidnight(new Date(endsAt)),
  };
}

/**
 * True when `host` is on the allow-list, INCLUDING as a subdomain of an entry.
 *
 * The subdomain rung is not a nicety — it mirrors `domainRegexFilter`'s
 * `([^/:@?#]*\.)?domain` wildcard exactly. If this were a plain equality check the DNR layer
 * would let `docs.google.com` through on a `google.com` entry while the block page insisted
 * it was blocked, and the two layers disagreeing about one host is the redirect-loop class
 * of bug all over again.
 */
export function isAllowedDomain(host: string, allowedDomains: readonly string[]): boolean {
  const lower = host.trim().toLowerCase();
  if (lower === '') return false;
  return allowedDomains.some((entry) => lower === entry || lower.endsWith(`.${entry}`));
}

/** True when `now` falls inside a window and `host` is not spared. The one blocking test. */
export function blocksHost(settings: NudgeSettings, host: string, now: Date): boolean {
  const state = resolveLightsOff(settings, now);
  return state.active && !isAllowedDomain(host, state.allowedDomains);
}

function earliest(candidates: readonly number[]): number | null {
  if (candidates.length === 0) return null;
  return candidates.reduce((soonest, candidate) => Math.min(soonest, candidate));
}

/**
 * When the next window OPENS. Drives the `LIGHTS_OFF_START` alarm.
 *
 * Deliberately ignores each profile's day filter: the next occurrence of its start TIME is
 * always at or before the next occurrence of its start time on a selected day, so this
 * over-fires (at most once per profile per day) rather than under-firing. An early wake is
 * free — the handler re-derives the state and does nothing if it already agrees — whereas a
 * missed wake means a lockdown that silently never starts.
 */
export function nextLightsOffStartAt(settings: NudgeSettings, now: Date): number | null {
  if (!settings.globalEnabled) return null;
  return earliest(
    settings.lightsOff.profiles
      .filter((profile) => profile.enabled)
      .map((profile) => nextOccurrenceAt(now, profile.startMinute)),
  );
}

/** When the next window CLOSES (schedule end, or a manual lockdown lapsing). */
export function nextLightsOffEndAt(settings: NudgeSettings, now: Date): number | null {
  if (!settings.globalEnabled) return null;
  const candidates = settings.lightsOff.profiles
    .filter((profile) => profile.enabled)
    .map((profile) => nextOccurrenceAt(now, profile.endMinute));
  const manual = settings.lightsOff.manualUntil;
  if (manual !== null && manual > now.getTime()) candidates.push(manual);
  return earliest(candidates);
}
