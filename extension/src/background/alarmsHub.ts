/**
 * Named alarms.
 *
 * Four standing alarms plus one per live temp-allow grant:
 *  - heartbeat:       backstop so a tab left open with no events still accrues time, AND the
 *                     self-healing reconcile for Lights Off (see below).
 *  - midnight:        the daily reset boundary, SELF-RESCHEDULING on an absolute `when`.
 *  - lightsOffStart:  the next moment a Lights Off window opens.
 *  - lightsOffEnd:    the next moment one closes (schedule end, or a manual lockdown lapsing).
 *
 * Why not `periodInMinutes: 1440` for midnight? A fixed 24h period drifts away from true
 * local midnight across a DST transition. Recomputing the next local midnight inside the
 * handler keeps it exact (ext-01 §4). The Lights Off boundaries are absolute `when` alarms for
 * the same reason.
 *
 * Chrome's guidance is to re-verify important alarms on every worker startup, since alarms
 * without `persistAcrossSessions` can be cleared on browser restart or extension reload.
 *
 * ── Why Lights Off needs BOTH an alarm and the heartbeat
 *
 * Every other dynamic DNR rule is a pure function of settings, so recompiling on a settings
 * change was sufficient. Lights Off is the first feature whose rule PRESENCE depends on the
 * wall clock, and an alarm alone cannot promise the boundary is honoured: a laptop asleep
 * through 22:00 wakes with a lockdown that never started, and Chrome makes no guarantee about
 * when a missed alarm fires. So the 1-minute heartbeat idempotently re-derives "should Lights
 * Off be on right now" and rewrites DNR only when reality disagrees (`reconcileLightsOff`).
 * The alarms make the boundary punctual; the heartbeat makes it eventually correct. Precision
 * without self-healing is how a lockdown silently fails to start overnight.
 */

import {
  nextLightsOffEndAt,
  nextLightsOffStartAt,
} from '../core/lightsOff';
import { msUntilNextLocalMidnight } from '../core/scheduleEvaluator';
import type { NudgeSettings } from '../core/settingsSchema';
import { refreshBadge } from './badge';
import { reconcileLightsOff } from './dnr';
import { loadSettings } from './storage';
import { domainFromAlarm, handleTempAllowExpiry, rearmTempAllows } from './tempAllow';
import { onActivityEvent } from './tracker';

export const HEARTBEAT_ALARM = 'nudge:heartbeat';
export const MIDNIGHT_ALARM = 'nudge:midnight';
export const LIGHTS_OFF_START_ALARM = 'nudge:lightsOffStart';
export const LIGHTS_OFF_END_ALARM = 'nudge:lightsOffEnd';

/** Production floor is 30s; 1 minute keeps us clear of it while staying responsive. */
const HEARTBEAT_PERIOD_MINUTES = 1;

export async function scheduleMidnight(now: Date = new Date()): Promise<void> {
  await chrome.alarms.create(MIDNIGHT_ALARM, {
    when: now.getTime() + msUntilNextLocalMidnight(now),
  });
}

/**
 * (Re)arm the two Lights Off boundary alarms from current settings.
 *
 * Always overwrites rather than only creating when absent — unlike the standing alarms, these
 * are derived from settings, so editing a schedule MUST move them. `chrome.alarms.create`
 * with an existing name replaces it, which is exactly the semantics we want.
 *
 * Clears an alarm when there is no corresponding boundary (nothing enabled, or the master
 * toggle is off), so a stale wake-up can't outlive the setting that asked for it.
 */
export async function scheduleLightsOffBoundaries(
  settings: NudgeSettings,
  now: Date = new Date(),
): Promise<void> {
  const boundaries: [name: string, at: number | null][] = [
    [LIGHTS_OFF_START_ALARM, nextLightsOffStartAt(settings, now)],
    [LIGHTS_OFF_END_ALARM, nextLightsOffEndAt(settings, now)],
  ];
  for (const [name, at] of boundaries) {
    if (at === null) {
      await chrome.alarms.clear(name);
    } else {
      await chrome.alarms.create(name, { when: at });
    }
  }
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
  await scheduleLightsOffBoundaries(settings, now);
  await rearmTempAllows(now.getTime());
}

export async function handleAlarm(alarm: chrome.alarms.Alarm): Promise<void> {
  if (alarm.name === HEARTBEAT_ALARM) {
    await onActivityEvent();
    // The Lights Off self-heal. A no-op unless DNR disagrees with the clock, so this is cheap
    // enough to run every minute — see the note at the top of this file.
    const settings = await loadSettings();
    if (await reconcileLightsOff(settings, new Date())) await refreshBadge();
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

  if (alarm.name === LIGHTS_OFF_START_ALARM || alarm.name === LIGHTS_OFF_END_ALARM) {
    // Both boundaries do the same thing: re-derive and re-arm. Deriving the state rather than
    // trusting which alarm fired is what makes an early or duplicated wake harmless — and the
    // start alarm deliberately over-fires (it ignores day filters, see nextLightsOffStartAt).
    const now = new Date();
    const settings = await loadSettings();
    await reconcileLightsOff(settings, now);
    await scheduleLightsOffBoundaries(settings, now);
    await refreshBadge();
    return;
  }

  const domain = domainFromAlarm(alarm.name);
  if (domain !== null) {
    await handleTempAllowExpiry(domain);
  }
}
