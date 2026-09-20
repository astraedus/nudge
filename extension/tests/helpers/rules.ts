/**
 * Shared fixture builders for tests.
 *
 * Every test file used to carry its own `siteRule()` literal, so adding a field to
 * `SiteRule` meant editing the same object in four places and a test that was skipped
 * silently kept an old shape. One builder means one edit — and a new field automatically
 * gets its default in every existing test rather than being invisible there.
 */

import {
  DEFAULT_SETTINGS,
  defaultFeatures,
  defaultGateSetting,
  type GateSetting,
  type NudgeSettings,
  type ScheduleOverride,
  type SiteFeatures,
  type SiteRule,
} from '../../src/core/settingsSchema';
import type { GateId, HideId, Platform } from '../../src/core/platforms';

export function siteRule(overrides: Partial<SiteRule> = {}): SiteRule {
  const domain = overrides.domain ?? 'youtube.com';
  return {
    id: 'rule-1',
    domain,
    mode: 'HARD_BLOCK',
    delaySeconds: 15,
    dailyLimitMinutes: null,
    enabled: true,
    createdAt: 0,
    showTimeRemaining: false,
    schedule: null,
    grayscale: false,
    features: null,
    ...overrides,
  };
}

export function scheduleOverride(
  overrides: Partial<ScheduleOverride> = {},
): ScheduleOverride {
  return {
    enabled: true,
    days: null,
    startMinute: null,
    endMinute: null,
    mode: 'BREATHING',
    delaySeconds: 20,
    ...overrides,
  };
}

export function gateSetting(overrides: Partial<GateSetting> = {}): GateSetting {
  return { ...defaultGateSetting(), ...overrides };
}

/** A features block for `platform` with the named gates/hides turned on. */
export function featuresWith(
  platform: Platform,
  overrides: {
    gates?: Partial<Record<GateId, Partial<GateSetting>>>;
    hides?: Partial<Record<HideId, boolean>>;
    youtube?: Partial<NonNullable<SiteFeatures['youtube']>>;
  } = {},
): SiteFeatures {
  const features = defaultFeatures(platform);
  for (const [gateId, gate] of Object.entries(overrides.gates ?? {})) {
    features.gates[gateId as GateId] = gateSetting(gate);
  }
  for (const [hideId, hidden] of Object.entries(overrides.hides ?? {})) {
    features.hides[hideId as HideId] = hidden === true;
  }
  if (features.youtube !== undefined && overrides.youtube !== undefined) {
    features.youtube = { ...features.youtube, ...overrides.youtube };
  }
  return features;
}

export function settings(overrides: Partial<NudgeSettings> = {}): NudgeSettings {
  return {
    ...(JSON.parse(JSON.stringify(DEFAULT_SETTINGS)) as NudgeSettings),
    ...overrides,
  };
}
