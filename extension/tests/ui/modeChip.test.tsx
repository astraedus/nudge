// @vitest-environment jsdom
/**
 * The rule-card mode badge. Its whole job is to let someone scan a list of sites and see
 * which ones are hard-blocked without reading a word, which is exactly what it could not
 * do while Hard Block, Delay and Breathing all printed the same red.
 */
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { SITE_MODE_LABELS, type SiteMode } from '../../src/core/types';
import { ModeChip } from '../../src/ui/components';

const ALL_MODES: SiteMode[] = ['ALLOW', 'HARD_BLOCK', 'DELAY', 'BREATHING'];

afterEach(() => {
  cleanup();
});

describe('ModeChip', () => {
  it.each(ALL_MODES)('labels %s with its Android name', (mode) => {
    render(<ModeChip mode={mode} />);
    expect(screen.getByText(SITE_MODE_LABELS[mode])).toBeDefined();
  });

  it('gives every mode its own colour, so two different rules never look like the same rule', () => {
    const { container } = render(
      <>
        {ALL_MODES.map((mode) => (
          <ModeChip key={mode} mode={mode} />
        ))}
      </>,
    );

    const chips = Array.from(container.querySelectorAll<HTMLElement>('[data-mode]'));
    expect(chips).toHaveLength(ALL_MODES.length);

    const fills = chips.map((chip) => chip.style.background);
    const texts = chips.map((chip) => chip.style.color);
    // No empty strings: an unset fill would make "all distinct" trivially false-negative
    // and a chip invisible on the card.
    expect(fills.every((fill) => fill !== '')).toBe(true);
    expect(new Set(fills).size).toBe(ALL_MODES.length);
    expect(new Set(texts).size).toBe(ALL_MODES.length);
  });

  it('never states the mode by colour alone', () => {
    // ~8% of men cannot separate the red and amber ends of this ramp (app-design-reference
    // §1). The label is the signal that always works; the colour is the accelerator.
    for (const mode of ALL_MODES) {
      cleanup();
      const { container } = render(<ModeChip mode={mode} />);
      expect(container.textContent).toBe(SITE_MODE_LABELS[mode]);
    }
  });
});
