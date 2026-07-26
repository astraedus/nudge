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
