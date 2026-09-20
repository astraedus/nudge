/**
 * Usage-bucket keys for feature surfaces. PURE.
 *
 * A gate can carry its OWN daily budget ("10 minutes of Shorts a day, YouTube itself
 * unlimited"), which means the tracker has to attribute a focused tab's time to two places
 * at once: the site bucket (`youtube.com`) and, when the URL is on a gate surface, a
 * surface bucket (`youtube.com#shorts`). Both live in the same `DayUsage` map, because
 * they are the same kind of number measured over the same day and splitting them across
 * two stores would mean two midnight resets, two rollover bugs and two things to migrate.
 *
 * The cost of sharing the map is that a surface key looks like a domain to anything that
 * naively lists the map's keys — a stats table would grow a phantom "youtube.com#shorts"
 * site. `isSurfaceKey` exists so every such place can filter explicitly rather than each
 * one re-deriving the rule from the shape of the string.
 *
 * '#' is the separator because it cannot appear in a base domain (domainMatcher rejects
 * any host with characters outside `[a-z0-9.-]`) and cannot appear in a GateId (they are
 * all lowercase identifiers), so the split is unambiguous in both directions.
 */

import type { GateId } from './platforms';

export const SURFACE_SEPARATOR = '#';

/** The usage-bucket key for one gate surface of one site. */
export function surfaceKey(domain: string, gateId: GateId): string {
  return `${domain}${SURFACE_SEPARATOR}${gateId}`;
}

/** True when `key` names a feature surface rather than a whole site. */
export function isSurfaceKey(key: string): boolean {
  return key.includes(SURFACE_SEPARATOR);
}

/** Split a surface key back into its parts, or null when `key` is a plain domain. */
export function parseSurfaceKey(key: string): { domain: string; gateId: string } | null {
  const index = key.indexOf(SURFACE_SEPARATOR);
  if (index <= 0) return null;
  const gateId = key.slice(index + 1);
  if (gateId === '') return null;
  return { domain: key.slice(0, index), gateId };
}

/** Every plain-domain key in a usage map, surface buckets filtered out. */
export function domainKeysOnly(keys: readonly string[]): string[] {
  return keys.filter((key) => !isSurfaceKey(key));
}
