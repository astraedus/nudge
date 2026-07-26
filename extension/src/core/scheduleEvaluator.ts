/**
 * Schedule evaluation. PURE.
 *
 * Direct port of Android's `domain/engine/ScheduleEvaluator.kt`, including its exact
 * boundary semantics — the ported Kotlin tests are mirrored in tests/core/scheduleEvaluator.test.ts.
 *
 * Day numbering: ISO 8601, 1=Monday .. 7=Sunday.
 * Time: minutes from local midnight, 0..1439.
 * Overnight spans (end < start, e.g. 23:00–06:00) are supported.
 */

export interface ScheduleWindow {
  /** ISO day numbers. null or empty = every day. */
  days: number[] | null;
  /** Minutes from local midnight. Both null = all day (on the chosen days). */
  startMinute: number | null;
  endMinute: number | null;
}

/** Convert a JS `Date.getDay()` (0=Sun..6=Sat) to ISO (1=Mon..7=Sun). */
export function isoDayOfWeek(date: Date): number {
  const day = date.getDay();
  return day === 0 ? 7 : day;
}

/** Minutes elapsed since local midnight for `date`. */
export function minutesSinceMidnight(date: Date): number {
  return date.getHours() * 60 + date.getMinutes();
}

/**
 * True when the schedule window covers `now` (local time).
 *
 * With no schedule fields set at all the window is "always" — that is what makes an
 * unscheduled rule permanently active.
 */
export function isScheduleActiveAt(window: ScheduleWindow, now: Date): boolean {
  const { days, startMinute, endMinute } = window;

  // Nothing configured -> always active.
  if ((days === null || days.length === 0) && startMinute === null && endMinute === null) {
    return true;
  }

  if (days !== null && days.length > 0 && !days.includes(isoDayOfWeek(now))) {
    return false;
  }

  if (startMinute !== null && endMinute !== null) {
    const current = minutesSinceMidnight(now);
    if (startMinute <= endMinute) {
      // Normal range, end EXCLUSIVE: 09:00–17:00 is active at 09:00, not at 17:00.
      // (start === end therefore yields an empty window — never active — matching Android.)
      return current >= startMinute && current < endMinute;
    }
    // Overnight range: 23:00–06:00.
    return current >= startMinute || current < endMinute;
  }

  // Only days set -> active all day on those days.
  return true;
}

/**
 * Milliseconds from `now` until the next local midnight.
 *
 * Computed by constructing the next calendar day at 00:00 LOCAL rather than adding a
 * fixed 24h, so a DST transition shortens/lengthens the interval correctly instead of
 * drifting the daily reset off midnight (ext-01 §4).
 */
export function msUntilNextLocalMidnight(now: Date): number {
  const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 0, 0);
  return next.getTime() - now.getTime();
}

/** Local calendar day key, `yyyy-mm-dd`. The key usage rollups are stored under. */
export function localDayKey(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
