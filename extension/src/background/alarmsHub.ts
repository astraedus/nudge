/**
 * Named alarms.
 *
 * Two standing alarms plus one per live temp-allow grant:
 *  - heartbeat: backstop so a tab left open with no events still accrues time.
 *  - midnight:  the daily reset boundary, SELF-RESCHEDULING on an absolute `when`.
 *
 * Why not `periodInMinutes: 1440` for midnight? A fixed 24h period drifts away from true
 * local midnight across a DST transition. Recomputing the next local midnight inside the
 * handler keeps it exact (ext-01 §4).
 *
 * Chrome's guidance is to re-verify important alarms on every worker startup, since alarms
 * without `persistAcrossSessions` can be cleared on browser restart or extension reload.
 */

import { msUntilNextLocalMidnight } from '../core/scheduleEvaluator';
import { refreshBadge } from './badge';
import { domainFromAlarm, handleTempAllowExpiry, rearmTempAllows } from './tempAllow';
import { onActivityEvent } from './tracker';

export const HEARTBEAT_ALARM = 'nudge:heartbeat';
export const MIDNIGHT_ALARM = 'nudge:midnight';

/** Production floor is 30s; 1 minute keeps us clear of it while staying responsive. */
const HEARTBEAT_PERIOD_MINUTES = 1;

export async function scheduleMidnight(now: Date = new Date()): Promise<void> {
  await chrome.alarms.create(MIDNIGHT_ALARM, {
    when: now.getTime() + msUntilNextLocalMidnight(now),
  });
}

/** Create any standing alarm that is missing. Safe to call repeatedly. */
export async function ensureAlarms(now: Date = new Date()): Promise<void> {
  if ((await chrome.alarms.get(HEARTBEAT_ALARM)) === undefined) {
    await chrome.alarms.create(HEARTBEAT_ALARM, {
      periodInMinutes: HEARTBEAT_PERIOD_MINUTES,
    });
  }
  if ((await chrome.alarms.get(MIDNIGHT_ALARM)) === undefined) {
    await scheduleMidnight(now);
  }
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
    await scheduleMidnight(new Date());
    await refreshBadge();
    return;
  }

  const domain = domainFromAlarm(alarm.name);
  if (domain !== null) {
    await handleTempAllowExpiry(domain);
  }
}
