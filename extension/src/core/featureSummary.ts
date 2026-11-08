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

import { isScheduleInForce } from './applies';
import { formatMinuteOfDay } from './scheduleEvaluator';
import { isPauseMode, MODE_LABELS } from './types';
import { platformById, type HideId } from './platforms';
import type { ChannelListMode, SiteRule } from './settingsSchema';

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

function canPause(rule: SiteRule): boolean {
  return isPauseMode(rule.mode) || (rule.schedule?.enabled === true && isPauseMode(rule.schedule.mode));
}

/** "Only 3 channels allowed" / "2 channels blocked", or null for a list that does nothing. */
function channelListLabel(mode: ChannelListMode, count: number): string | null {
  if (mode === 'OFF' || count === 0) return null;
  const noun = count === 1 ? 'channel' : 'channels';
  return mode === 'WHITELIST' ? `Only ${count} ${noun} allowed` : `${count} ${noun} blocked`;
}

/**
 * The schedule window's own channel list (v0.8), or null when the window has none ("same as
 * default") or the schedule is off. Always said, even for a list that is empty or off, because
 * inside the window it REPLACES the default one: "no channel list" there lifts a blacklist.
 */
function windowChannelPart(rule: SiteRule, now?: Date): string | null {
  const schedule = rule.schedule;
  if (schedule === null || !schedule.enabled || schedule.channels === null) return null;
  if (rule.features?.youtube === undefined) return null;
  const { channelMode, channels, allowSubscriptions } = schedule.channels;
  const label =
    channelListLabel(channelMode, channels.length)?.toLowerCase() ??
    (channelMode === 'WHITELIST'
      ? allowSubscriptions
        ? 'only subscriptions allowed'
        : 'no channels allowed'
      : 'no channel list');
  // Said as live only when the popup asks about this moment; a configuration (no `now`)
  // or a window not yet open reads "During schedule".
  if (now !== undefined && isScheduleInForce(rule, now)) {
    return schedule.endMinute === null
      ? `Now: ${label}`
      : `Now, until ${formatMinuteOfDay(schedule.endMinute)}: ${label}`;
  }
  return `During schedule: ${label}`;
}

/**
 * The parts of a rule that are doing something, in the order a user thinks about them.
 * Empty when the rule has no effect at all.
 *
 * Without `now` (the dashboard) it describes the configuration: the default channel list and,
 * when the schedule window has its own, that one too. With `now` (the popup, about this
 * moment) a window list that is in force replaces the default one, so the popup names the
 * list actually being enforced.
 */
export function featureSummaryParts(rule: SiteRule, now?: Date): string[] {
  const parts: string[] = [];

  if (rule.dailyLimitMinutes !== null) parts.push(`${rule.dailyLimitMinutes}m/day`);
  // Stored but inert under Allow or Hard Block, where it would describe a pause that never comes.
  if (rule.repeatPauseMinutes !== null && canPause(rule)) {
    parts.push(`Pauses every ${rule.repeatPauseMinutes}m`);
  }
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
        continue;
      }
      // An OFF gate with a budget is still doing something, and it is the shape the
      // feature exists for ("10 minutes of Shorts a day", "20 Shorts a day"). BOTH axes
      // are listed: a count-only gate that printed nothing here would leave the popup and
      // the rule card calling a rule "no effect" while it is the only thing standing
      // between the user and an afternoon of Shorts.
      const budgets: string[] = [];
      if (setting.dailyLimitMinutes !== null) budgets.push(`${setting.dailyLimitMinutes}m/day`);
      if (setting.dailyLimitCount !== null) {
        const noun = gate.itemNoun;
        const label =
          noun === undefined
            ? `${setting.dailyLimitCount}/day`
            : `${setting.dailyLimitCount} ${setting.dailyLimitCount === 1 ? noun.singular : noun.plural}/day`;
        budgets.push(label);
      }
      if (budgets.length > 0) parts.push(`${gate.label}: ${budgets.join(', ')}`);
    }

    const hidden = platform.hides
      .filter((hide) => features.hides[hide.id as HideId] === true)
      .map((hide) => hide.label);
    if (hidden.length === 1) parts.push(`${hidden[0]} hidden`);
    else if (hidden.length > 1) parts.push(`${hidden.length} elements hidden`);

    const youtube = features.youtube;
    const windowPart = windowChannelPart(rule, now);
    const windowInForce = windowPart !== null && now !== undefined && isScheduleInForce(rule, now);
    const defaultPart =
      youtube === undefined ? null : channelListLabel(youtube.channelMode, youtube.channels.length);
    if (defaultPart !== null && !windowInForce) parts.push(defaultPart);
    if (windowPart !== null) parts.push(windowPart);
    if (youtube?.disableAutoplay === true) parts.push('Autoplay off');
  }

  return parts;
}

/**
 * The summary line, or `null` when the rule's own mode is the whole story (a plain Hard
 * Block needs no second sentence) — callers show the mode chip in that case.
 */
export function featureSummary(rule: SiteRule, now?: Date): string | null {
  const parts = featureSummaryParts(rule, now);
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
