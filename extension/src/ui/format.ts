/** Presentation-layer formatting helpers. PURE — safe to unit test. */

/** "2h 14m" / "14m" / "42s". Used for screen-time readouts. */
export function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  if (total < 60) return `${total}s`;
  const minutes = Math.floor(total / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  return remainder === 0 ? `${hours}h` : `${hours}h ${remainder}m`;
}

/**
 * Badge text for remaining budget. The badge fits only ~4 characters (ext-01 §5),
 * so hours collapse to "1h" / "1h2" rather than "1h 20m".
 */
export function formatBadge(remainingMs: number): string {
  const minutes = Math.max(0, Math.floor(remainingMs / 60_000));
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const rem = Math.floor((minutes % 60) / 10);
  if (hours >= 10) return `${hours}h`;
  return rem === 0 ? `${hours}h` : `${hours}h${rem}`;
}

/**
 * Badge color by fraction of budget remaining — Android parity:
 * green >50%, orange 25–50%, red <25%.
 */
export function badgeColor(remainingMs: number, limitMs: number): string {
  if (limitMs <= 0) return '#1b6b5a';
  const fraction = remainingMs / limitMs;
  if (fraction > 0.5) return '#1b6b5a';
  if (fraction >= 0.25) return '#b26a00';
  return '#ba1a1a';
}

/** "next in 5h" for the spent-pass hint. Rounds UP so it never reads "next in 0h". */
export function formatNextPass(ms: number): string {
  const hours = Math.ceil(Math.max(0, ms) / 3_600_000);
  return `${hours}h`;
}

/** Minutes-from-midnight -> "09:30". */
export function formatMinuteOfDay(minute: number): string {
  const m = Math.max(0, Math.min(1439, Math.round(minute)));
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}
