/**
 * The one-line "what is actually switched on for this site" string. PURE.
 *
 * Three surfaces show this sentence — the popup's current-site card, the sites-list rule
 * card, and the rule editor's collapsed feature section — and they are built by different
 * layers (the worker resolves the popup's copy; the dashboard renders its own from
 * settings). Left to each of them, the same rule would describe itself differently
 * depending on where you looked, which reads to a user as the extension disagreeing with
 * itself about their own settings.
 *
 * It is also the ONLY place that decides what "nothing active" means. A rule with mode
 * ALLOW, no limit, no grayscale and no feature on is a rule that does nothing at all, and
 * the spec is explicit that the product must say so rather than show an authoritative-
 * looking card for a rule with no effect.
 */

import { MODE_LABELS } from './types';
import { gateDefinition, platformById, type HideId } from './platforms';
import type { SiteRule } from './settingsSchema';

/** Shown on a card whose rule has no effect whatsoever. */
export const NOTHING_ACTIVE = 'Nothing active';

/** "Delay 15s" / "Hard Block" — a gate's mode, with its pause only where a pause exists. */
function gateModeLabel(mode: 'OFF' | keyof typeof MODE_LABELS, delaySeconds: number): string {
  if (mode === 'OFF') return '';
  // A Hard Block has no countdown, so printing a delay next to it would describe a wait
  // that never happens.
  return mode === 'HARD_BLOCK'
    ? MODE_LABELS.HARD_BLOCK
    : `${MODE_LABELS[mode]} ${delaySeconds}s`;
}

/**
 * The parts of a rule that are doing something, in the order a user thinks about them.
 * Empty when the rule has no effect at all.
 */
export function featureSummaryParts(rule: SiteRule): string[] {
  const parts: string[] = [];

  if (rule.dailyLimitMinutes !== null) parts.push(`${rule.dailyLimitMinutes}m/day`);
  if (rule.grayscale) parts.push('Grayscale');

  const features = rule.features;
  if (features !== null) {
    const platform = platformById(features.platform);

    // Iterate the REGISTRY, not the stored object, so the order is stable and a stale key
    // that survived somehow still cannot print.
    for (const gate of platform.gates) {
      const setting = features.gates[gate.id];
      if (setting === undefined) continue;
      const mode = gateModeLabel(setting.mode, setting.delaySeconds);
      if (mode !== '') {
        parts.push(`${gate.label}: ${mode}`);
      } else if (setting.dailyLimitMinutes !== null) {
        // An OFF gate with a budget is still doing something, and it is the shape the
        // feature exists for ("10 minutes of Shorts a day").
        parts.push(`${gate.label}: ${setting.dailyLimitMinutes}m/day`);
      }
    }

    const hidden = platform.hides
      .filter((hide) => features.hides[hide.id as HideId] === true)
      .map((hide) => hide.label);
    if (hidden.length === 1) parts.push(`${hidden[0]} hidden`);
    else if (hidden.length > 1) parts.push(`${hidden.length} elements hidden`);

    const youtube = features.youtube;
    if (youtube !== undefined && youtube.channelMode !== 'OFF' && youtube.channels.length > 0) {
      const noun = youtube.channels.length === 1 ? 'channel' : 'channels';
      parts.push(
        youtube.channelMode === 'WHITELIST'
          ? `Only ${youtube.channels.length} ${noun} allowed`
          : `${youtube.channels.length} ${noun} blocked`,
      );
    }
    if (youtube?.disableAutoplay === true) parts.push('Autoplay off');
  }

  return parts;
}

/**
 * The summary line, or `null` when the rule's own mode is the whole story (a plain Hard
 * Block needs no second sentence) — callers show the mode chip in that case.
 */
export function featureSummary(rule: SiteRule): string | null {
  const parts = featureSummaryParts(rule);
  return parts.length === 0 ? null : parts.join(' · ');
}

/**
 * True when this rule does nothing at all: it allows the site by default and carries no
 * limit, no grayscale and no active feature.
 *
 * Deliberately ignores `enabled` — a disabled rule is a different state with its own
 * label, and conflating "switched off" with "configured to do nothing" would hide the
 * second, which is the one the user needs told about.
 */
export function isNothingActive(rule: SiteRule): boolean {
  return rule.mode === 'ALLOW' && scheduleDoesNothing(rule) && featureSummaryParts(rule).length === 0;
}

function scheduleDoesNothing(rule: SiteRule): boolean {
  const schedule = rule.schedule;
  return schedule === null || !schedule.enabled || schedule.mode === 'ALLOW';
}
