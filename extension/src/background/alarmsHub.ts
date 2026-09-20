/**
 * Named alarms.
 *
 * Three standing alarms plus one per live temp-allow grant:
 *  - heartbeat: backstop so a tab left open with no events still accrues time.
 *  - midnight:  the daily reset boundary, SELF-RESCHEDULING on an absolute `when`.
 *  - schedule:  the next Scheduled Override edge, re-derived from settings every time.
 *
 * Why not `periodInMinutes: 1440` for midnight? A fixed 24h period drifts away from true
 * local midnight across a DST transition. Recomputing the next local midnight inside the
 * handler keeps it exact (ext-01 §4).
 *
 * Chrome's guidance is to re-verify important alarms on every worker startup, since alarms
 * without `persistAcrossSessions` can be cleared on browser restart or extension reload.
 *
 * ## Why a schedule alarm exists at all (new in v0.2)
 *
 * In v0.1 a schedule could only make a rule stricter, and the rule already redirected the
 * domain at all times — so a schedule edge changed nothing the NETWORK layer could see, and
 * the block page (which runs the engine live) resolved the rest. v0.2 makes a schedule able
 * to flip a rule in both directions: "Allow all day, Hard Block 9–5" means the redirect has
 * to come into existence at 9 and stop existing at 5, with nobody navigating in between.
 * Nothing else in the worker wakes up for that, so an alarm at the next edge is the whole
 * mechanism. It is re-derived on every wake rather than trusted to persist, for the same
 * reason the other two are.
 */

import { msUntilNextLocalMidnight } from '../core/scheduleEvaluator';
import type { NudgeSettings } from '../core/settingsSchema';
import { refreshBadge } from './badge';
import { applyRules } from './dnr';
import { loadSettings } from './storage';
import { domainFromAlarm, handleTempAllowExpiry, rearmTempAllows } from './tempAllow';
import { onActivityEvent } from './tracker';

export const HEARTBEAT_ALARM = 'nudge:heartbeat';
export const MIDNIGHT_ALARM = 'nudge:midnight';
export const SCHEDULE_ALARM = 'nudge:schedule';

/** Production floor is 30s; 1 minute keeps us clear of it while staying responsive. */
const HEARTBEAT_PERIOD_MINUTES = 1;

export async function scheduleMidnight(now: Date = new Date()): Promise<void> {
  await chrome.alarms.create(MIDNIGHT_ALARM, {
    when: now.getTime() + msUntilNextLocalMidnight(now),
  });
}

/**
 * When the next Scheduled Override edge falls, or null when no enabled rule has one. PURE.
 *
 * Candidates are every schedule's start and end minute on today and tomorrow, and the
 * answer is the earliest one still in the future. Deliberately NOT filtered by the
 * schedule's `days`: an edge on a day the schedule does not cover wakes the worker for a
 * recompile that produces the identical rule set, which costs nothing, whereas getting the
 * day arithmetic wrong around an overnight window (23:00–06:00 spans two days, and its
 * `days` describe the START day) silently misses a real edge. The day logic already lives
 * once in `scheduleEvaluator`, and duplicating it here to save a no-op wake would be
 * trading a correctness risk for nothing.
 *
 * Both boundaries are built with the `Date(y, m, d, 0, minute)` constructor rather than by
 * adding milliseconds, so they stay pinned to the local wall clock across a DST change.
 */
export function nextScheduleBoundary(settings: NudgeSettings, now: Date): number | null {
  const minutes = new Set<number>();
  for (const rule of settings.rules) {
    const schedule = rule.schedule;
    if (!rule.enabled || schedule === null || !schedule.enabled) continue;
    if (schedule.startMinute !== null) minutes.add(schedule.startMinute);
    if (schedule.endMinute !== null) minutes.add(schedule.endMinute);
  }
  if (minutes.size === 0) return null;

  let best: number | null = null;
  for (const minute of minutes) {
    for (const dayOffset of [0, 1]) {
      const when = new Date(
        now.getFullYear(),
        now.getMonth(),
        now.getDate() + dayOffset,
        0,
        minute,
      ).getTime();
      if (when > now.getTime() && (best === null || when < best)) best = when;
    }
  }
  return best;
}

/**
 * Arm (or clear) the schedule-edge alarm for the current settings.
 *
 * Creating an alarm with an existing name REPLACES it, which is what makes this safe to
 * call on every wake and every save without first clearing anything.
 */
export async function ensureScheduleAlarm(
  settings: NudgeSettings,
  now: Date = new Date(),
): Promise<void> {
  const when = nextScheduleBoundary(settings, now);
  if (when === null) {
    await chrome.alarms.clear(SCHEDULE_ALARM);
    return;
  }
  await chrome.alarms.create(SCHEDULE_ALARM, { when });
}

/** Create any standing alarm that is missing. Safe to call repeatedly. */
export async function ensureAlarms(
  settings: NudgeSettings,
  now: Date = new Date(),
): Promise<void> {
  if ((await chrome.alarms.get(HEARTBEAT_ALARM)) === undefined) {
    await chrome.alarms.create(HEARTBEAT_ALARM, {
      periodInMinutes: HEARTBEAT_PERIOD_MINUTES,
    });
  }
  if ((await chrome.alarms.get(MIDNIGHT_ALARM)) === undefined) {
    await scheduleMidnight(now);
  }
  // Unconditionally, unlike the other two: the correct time MOVES with the settings, so
  // "already exists" is not evidence that it is armed for the right moment.
  await ensureScheduleAlarm(settings, now);
  await rearmTempAllows(now.getTime());
}

export async function handleAlarm(alarm: chrome.alarms.Alarm): Promise<void> {
  if (alarm.name === HEARTBEAT_ALARM) {
    await onActivityEvent();
    return;
  }

  if (alarm.name === MIDNIGHT_ALARM) {
    // Close out the interval that spanned midnight so its time lands on the correct day,
    // then re-arm for the NEXT local midnight.
    await onActivityEvent();
    const now = new Date();
    const settings = await loadSettings();
    // Every daily budget is back to zero, so every rule that was in force ONLY because its
    // limit was spent has to stop redirecting. Nothing else recompiles at midnight.
    await applyRules(settings, now);
    await scheduleMidnight(now);
    await ensureScheduleAlarm(settings, now);
    await refreshBadge();
    return;
  }

  if (alarm.name === SCHEDULE_ALARM) {
    const now = new Date();
    const settings = await loadSettings();
    await applyRules(settings, now);
    await ensureScheduleAlarm(settings, now);
    await refreshBadge();
    return;
  }

  const domain = domainFromAlarm(alarm.name);
  if (domain !== null) {
    await handleTempAllowExpiry(domain);
  }
}
