/**
 * The design tokens are the only place a colour is decided, so this is the only place a
 * contrast regression can be caught before a user squints at it.
 *
 * `app-design-reference.md` §1: every real text colour clears 4.5:1 against what it sits
 * on, in BOTH themes. The mode chips in particular are a graded severity ramp (Hard Block
 * > Delay > Breathing), which is easy to "improve" into an unreadable pastel later.
 *
 * Reads the stylesheet as text rather than through a browser on purpose: jsdom does not
 * resolve `var()`, and the question here is what the FILE declares.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const TOKENS_CSS = readFileSync(
  fileURLToPath(new URL('../../src/ui/tokens.css', import.meta.url)),
  'utf-8',
);

/** The declarations inside each `:root { ... }` block, in file order: [light, dark]. */
function rootBlocks(css: string): string[] {
  const blocks: string[] = [];
  let from = 0;
  for (;;) {
    const start = css.indexOf(':root {', from);
    if (start === -1) break;
    const end = css.indexOf('}', start);
    blocks.push(css.slice(start, end));
    from = end;
  }
  return blocks;
}

const BLOCKS = rootBlocks(TOKENS_CSS);
const THEMES: { name: string; declarations: string }[] = [
  { name: 'light', declarations: BLOCKS[0] ?? '' },
  { name: 'dark', declarations: BLOCKS[1] ?? '' },
];

/** Throws rather than returning a default: a renamed token must fail loudly, not silently
 * make this whole suite vacuous. */
function token(declarations: string, name: string): string {
  const match = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})\\s*;`).exec(declarations);
  if (match === null) throw new Error(`token --${name} not found (or not a 6-digit hex)`);
  return match[1]!.toLowerCase();
}

function relativeLuminance(hex: string): number {
  const channel = (offset: number) => {
    const value = parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}

function contrastRatio(a: string, b: string): number {
  const [la, lb] = [relativeLuminance(a), relativeLuminance(b)];
  const [lighter, darker] = la > lb ? [la, lb] : [lb, la];
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * Hue in degrees, rebased so red sits near 0 instead of wrapping to 360. Without the
 * rebase a dark-theme red (#93000a, 356 degrees) would sort ABOVE a yellow.
 */
function hueDegrees(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16) / 255) as [
    number,
    number,
    number,
  ];
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const delta = max - min;
  if (delta === 0) return 0;
  const raw =
    max === r
      ? 60 * (((g - b) / delta + 6) % 6)
      : max === g
        ? 60 * ((b - r) / delta + 2)
        : 60 * ((r - g) / delta + 4);
  return raw > 330 ? raw - 360 : raw;
}

/** Every mode that gets a chip. Mirrors `MODE_CHIP_TONES` in src/ui/components.tsx. */
const MODE_TOKEN_NAMES = ['allow', 'hard', 'delay', 'breathing'] as const;

describe('contrastRatio (the checker itself)', () => {
  // A guard built by composing the thing under test can pass vacuously, plant the defect.
  it('scores black on white at the known maximum', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 1);
  });

  it('rejects a pair that genuinely fails AA', () => {
    expect(contrastRatio('#777777', '#888888')).toBeLessThan(4.5);
  });

  it('throws for a token that does not exist, so a rename cannot silence this file', () => {
    expect(() => token(THEMES[0]!.declarations, 'nudge-not-a-real-token')).toThrow();
  });

  it('found both themes', () => {
    expect(BLOCKS).toHaveLength(2);
  });
});

describe.each(THEMES)('$name theme', ({ declarations }) => {
  it.each(MODE_TOKEN_NAMES)('mode chip "%s" has readable text on its own background', (mode) => {
    const fg = token(declarations, `nudge-mode-${mode}-fg`);
    const bg = token(declarations, `nudge-mode-${mode}-bg`);
    expect(contrastRatio(fg, bg)).toBeGreaterThanOrEqual(4.5);
  });

  it.each(MODE_TOKEN_NAMES)('mode accent "%s" is readable as text on the surface', (mode) => {
    // The popup shows the mode as a LINE, not a chip. The chip foregrounds are near-black
    // by design, so the accents are their own tokens and need their own contrast guard
    // against the surface they actually sit on.
    const accent = token(declarations, `nudge-mode-${mode}-accent`);
    const surface = token(declarations, 'nudge-surface');
    expect(contrastRatio(accent, surface)).toBeGreaterThanOrEqual(4.5);
  });

  it('gives each mode a distinct accent, so the popup cannot paint them all one colour', () => {
    // This is the exact defect: every block mode rendered in one flat --nudge-danger.
    const accents = MODE_TOKEN_NAMES.map((mode) => token(declarations, `nudge-mode-${mode}-accent`));
    expect(new Set(accents).size).toBe(MODE_TOKEN_NAMES.length);
  });

  it('gives each mode a distinct background, so Hard Block and Delay never read alike', () => {
    const backgrounds = MODE_TOKEN_NAMES.map((mode) => token(declarations, `nudge-mode-${mode}-bg`));
    expect(new Set(backgrounds).size).toBe(MODE_TOKEN_NAMES.length);
  });

  it('grades the block modes red to amber to sand, so severity is legible at a glance', () => {
    // Severity here is HUE, not brightness: #ffdad6 (red) and #ffdcbe (amber) sit at almost
    // identical luminance, so any "how loud is it" proxy scores them the same and would
    // pass on the very bug this exists to stop, three chips in one flat red. Hue ordering
    // is the thing a user actually reads, and the thing a palette tweak breaks first.
    const hues = (['hard', 'delay', 'breathing'] as const).map((mode) =>
      hueDegrees(token(declarations, `nudge-mode-${mode}-bg`)),
    );
    const [hard, delay, breathing] = hues as [number, number, number];

    expect(hard).toBeLessThan(delay);
    expect(delay).toBeLessThan(breathing);
    // All three stay in the warm red-to-yellow band; a "severity" colour that drifts blue
    // or green has stopped meaning anything.
    for (const hue of hues) {
      expect(hue).toBeGreaterThanOrEqual(-30);
      expect(hue).toBeLessThanOrEqual(70);
    }
  });

  it('keeps the allowed-channel link readable on the block card and on its own fill', () => {
    const link = token(declarations, 'nudge-link');
    expect(contrastRatio(link, token(declarations, 'nudge-surface-raised'))).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(link, token(declarations, 'nudge-link-bg'))).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(link, token(declarations, 'nudge-link-bg-hover'))).toBeGreaterThanOrEqual(4.5);
  });
});
