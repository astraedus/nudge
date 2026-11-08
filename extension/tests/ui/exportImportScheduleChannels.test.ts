import { describe, expect, it } from 'vitest';

import { structuredCloneSettings, DEFAULT_SETTINGS } from '../../src/core/settingsSchema';
import type { ChannelEntry } from '../../src/core/settingsSchema';
import { buildExport, parseImport } from '../../src/ui/exportImport';
import { featuresWith, scheduleOverride, siteRule } from '../helpers/rules';

const STUDY: ChannelEntry = {
  channelId: 'UCstudy0000000000000001',
  handle: 'study',
  displayName: 'Study',
  addedAt: 7,
};

function exportable() {
  const settings = structuredCloneSettings(DEFAULT_SETTINGS);
  settings.rules = [
    siteRule({
      domain: 'youtube.com',
      mode: 'ALLOW',
      features: featuresWith('youtube'),
      schedule: scheduleOverride({
        days: [1, 2, 3, 4, 5],
        startMinute: 540,
        endMinute: 1020,
        mode: 'HARD_BLOCK',
        channels: { channelMode: 'WHITELIST', channels: [STUDY], allowSubscriptions: true },
      }),
    }),
  ];
  return settings;
}

describe('export and import of a window channel list', () => {
  it('round-trips schedule.channels through JSON and parseImport', () => {
    const result = parseImport(JSON.stringify(buildExport(exportable())));

    expect(result.ok).toBe(true);
    expect(result.rules?.[0]?.schedule?.channels).toEqual({
      channelMode: 'WHITELIST',
      channels: [STUDY],
      allowSubscriptions: true,
    });
  });

  it('deep-clones the window list so editing the export never touches settings', () => {
    const settings = exportable();
    const exported = buildExport(settings);
    const channels = exported.rules[0]!.schedule!.channels!;

    channels.channels[0]!.handle = 'changed';
    channels.channels.push({ ...STUDY, handle: 'extra' });
    channels.channelMode = 'OFF';

    const original = settings.rules[0]!.schedule!.channels!;
    expect(original.channels).toEqual([STUDY]);
    expect(original.channelMode).toBe('WHITELIST');
  });

  it('exports "same as default" as null', () => {
    const settings = exportable();
    settings.rules[0]!.schedule!.channels = null;
    expect(buildExport(settings).rules[0]?.schedule?.channels).toBeNull();
  });

  it('sanitises a hand-edited file: malformed entries dropped, bad mode read as OFF', () => {
    const exported = buildExport(exportable());
    const json = JSON.stringify(exported).replace('"WHITELIST"', '"EVERYTHING"');
    const tampered = JSON.parse(json) as { rules: { schedule: { channels: { channels: unknown[] } } }[] };
    tampered.rules[0]!.schedule.channels.channels.push('junk', null);

    const result = parseImport(JSON.stringify(tampered));

    expect(result.rules?.[0]?.schedule?.channels?.channelMode).toBe('OFF');
    expect(result.rules?.[0]?.schedule?.channels?.channels).toEqual([STUDY]);
  });
});
