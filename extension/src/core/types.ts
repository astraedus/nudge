/**
 * Engine-level types. PURE — no chrome.* imports anywhere in src/core/.
 *
 * Ported from the Android domain layer (domain/model/{BlockMode,ActiveRule,BlockDecision}.kt),
 * which is likewise pure (zero Android imports) and unit-tested on the JVM.
 */

/** Block modes. Labels shown to the user are "Hard Block" / "Delay" / "Breathing". */
export type BlockMode = 'HARD_BLOCK' | 'DELAY' | 'BREATHING';

/** User-facing label for a mode — Android naming parity. */
export const MODE_LABELS: Record<BlockMode, string> = {
  HARD_BLOCK: 'Hard Block',
  DELAY: 'Delay',
  BREATHING: 'Breathing',
};

/**
 * What a site rule's "Default behaviour" can be.
 *
 * 'ALLOW' is NOT a block mode and never reaches the engine: it means "this site opens
 * normally — the rule exists only to carry a daily limit, grayscale, or feature gates".
 * `core/applies.ts` is what decides whether such a rule is currently in force, and the
 * engine only ever sees rules that are. See the ENGINE INVARIANT note in blockEngine.ts:
 * an ALLOW verdict from the engine while DNR still redirects the domain is an infinite
 * loop, not a no-op, which is exactly why ALLOW lives out here instead of in there.
 */
export type SiteMode = 'ALLOW' | BlockMode;

export const SITE_MODE_LABELS: Record<SiteMode, string> = {
  ALLOW: 'Allow',
  ...MODE_LABELS,
};

/** Helper narrowing a `SiteMode` to the three real block modes. */
export function isBlockMode(mode: SiteMode): mode is BlockMode {
  return mode !== 'ALLOW';
}

/**
 * A rule resolved for "right now" — schedule already applied — and handed to the
 * BlockEngine. Mirrors Android's `ActiveRule`.
 */
export interface ActiveRule {
  mode: BlockMode;
  delaySeconds: number;
  dailyLimitMinutes: number | null;
  enabled: boolean;
  /** ISO day numbers, 1=Mon .. 7=Sun. null/empty = every day. */
  scheduleDays: number[] | null;
  /** Minutes from local midnight, 0..1439. */
  scheduleStartMinute: number | null;
  scheduleEndMinute: number | null;
  ruleName: string | null;
}

export type BlockDecision =
  | { type: 'ALLOW' }
  | {
      type: 'BLOCK';
      mode: BlockMode;
      delaySeconds: number;
      ruleName: string | null;
      dailyTimeRemainingMs: number | null;
      dailyLimitMinutes: number | null;
      /** True when this block was forced by an exhausted daily limit. */
      limitReached: boolean;
    };

export const ALLOW: BlockDecision = { type: 'ALLOW' };

/** Convenience constructor keeping BLOCK decisions total (every field explicit). */
export function block(params: {
  mode: BlockMode;
  delaySeconds?: number;
  ruleName?: string | null;
  dailyTimeRemainingMs?: number | null;
  dailyLimitMinutes?: number | null;
  limitReached?: boolean;
}): BlockDecision {
  return {
    type: 'BLOCK',
    mode: params.mode,
    delaySeconds: params.delaySeconds ?? 0,
    ruleName: params.ruleName ?? null,
    dailyTimeRemainingMs: params.dailyTimeRemainingMs ?? null,
    dailyLimitMinutes: params.dailyLimitMinutes ?? null,
    limitReached: params.limitReached ?? false,
  };
}
