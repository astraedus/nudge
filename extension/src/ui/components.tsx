/**
 * Shared UI primitives on the Nudge design tokens (src/ui/tokens.css).
 * Small and hand-rolled deliberately — no UI kit, keep the bundle lean (ext-08 Stack).
 *
 * Iconography rule: NEVER emoji-as-icon. Use inline SVG or text.
 */

import type { CSSProperties, ReactNode } from 'react';
import { SITE_MODE_LABELS, type SiteMode } from '../core/types';

export function Button({
  children,
  onClick,
  variant = 'primary',
  disabled = false,
  style,
  ...rest
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: 'primary' | 'secondary' | 'muted' | 'danger';
  disabled?: boolean;
  style?: CSSProperties;
} & Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'style' | 'onClick'>) {
  const base: CSSProperties = {
    border: 'none',
    borderRadius: 999,
    padding: '12px 24px',
    fontSize: 15,
    fontWeight: 600,
    transition: 'opacity 120ms ease',
    opacity: disabled ? 0.45 : 1,
    cursor: disabled ? 'not-allowed' : 'pointer',
  };
  const variants: Record<string, CSSProperties> = {
    primary: {
      background: 'var(--nudge-primary)',
      color: 'var(--nudge-on-primary)',
    },
    secondary: {
      background: 'var(--nudge-primary-container)',
      color: 'var(--nudge-on-primary-container)',
    },
    muted: {
      background: 'transparent',
      color: 'var(--nudge-on-surface-variant)',
      fontWeight: 500,
    },
    danger: {
      background: 'var(--nudge-error)',
      color: 'var(--nudge-on-error)',
    },
  };
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      style={{ ...base, ...variants[variant], ...style }}
      {...rest}
    >
      {children}
    </button>
  );
}

export function Card({
  children,
  title,
  style,
}: {
  children: ReactNode;
  title?: string;
  style?: CSSProperties;
}) {
  return (
    <section
      style={{
        background: 'var(--nudge-surface)',
        border: '1px solid var(--nudge-surface-variant)',
        borderRadius: 'var(--nudge-radius)',
        padding: 20,
        ...style,
      }}
    >
      {title !== undefined && (
        <h2
          style={{
            margin: '0 0 14px',
            fontSize: 15,
            fontWeight: 600,
            color: 'var(--nudge-on-surface-variant)',
            letterSpacing: 0.2,
          }}
        >
          {title}
        </h2>
      )}
      {children}
    </section>
  );
}

/** The "Rule: X" transparency footer shown on every block surface (Android parity). */
export function RuleFooter({ ruleName }: { ruleName: string | null }) {
  if (ruleName === null || ruleName === '') return null;
  return (
    <p
      style={{
        // The block card owns the spacing above this line (its flex `gap`); a second
        // margin here on top of that pushed the footer into its own no-man's-land.
        margin: '4px 0 0',
        fontSize: 13,
        color: 'var(--nudge-on-surface-variant)',
        opacity: 0.8,
      }}
    >
      Rule: {ruleName}
    </p>
  );
}

export function Toggle({
  checked,
  onChange,
  label,
  disabled = false,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  disabled?: boolean;
}) {
  return (
    <label
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
      }}
    >
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        style={{ width: 18, height: 18, accentColor: 'var(--nudge-primary)' }}
      />
      <span style={{ fontSize: 15 }}>{label}</span>
    </label>
  );
}

/**
 * A small pill-shaped selectable chip. Shared by the sites list (quick-add platforms),
 * RuleEditor (mode/delay presets) and ChannelListEditor — one visual pattern instead of
 * three near-identical local copies.
 */
export function Chip({
  label,
  active,
  onClick,
  style,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
  style?: CSSProperties;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      style={{
        padding: '6px 14px',
        borderRadius: 999,
        border: '1px solid var(--nudge-outline)',
        background: active ? 'var(--nudge-primary)' : 'transparent',
        color: active ? 'var(--nudge-on-primary)' : 'var(--nudge-on-surface)',
        fontSize: 13,
        cursor: 'pointer',
        ...style,
      }}
    >
      {label}
    </button>
  );
}


/**
 * The per-mode tone of a rule card's chip, graded by severity.
 *
 * Hard Block, Delay and Breathing all used to print the SAME red, which reads as "these
 * three are the same thing" precisely where the user is scanning for the difference. The
 * ramp runs red (hardest) to amber to sand (gentlest), with Allow in the teal family, and
 * every pair is >= 7:1 in both themes. The LABEL still carries the meaning, so the colour
 * is a second signal and never the only one.
 *
 * Typed `Record<SiteMode, ...>` on purpose: a new mode is then a compile error here rather
 * than a chip that silently falls back to someone else's colour.
 */
const MODE_CHIP_TONES: Record<SiteMode, { bg: string; fg: string; border: string }> = {
  ALLOW: {
    bg: 'var(--nudge-mode-allow-bg)',
    fg: 'var(--nudge-mode-allow-fg)',
    border: 'var(--nudge-mode-allow-border)',
  },
  HARD_BLOCK: {
    bg: 'var(--nudge-mode-hard-bg)',
    fg: 'var(--nudge-mode-hard-fg)',
    border: 'var(--nudge-mode-hard-border)',
  },
  DELAY: {
    bg: 'var(--nudge-mode-delay-bg)',
    fg: 'var(--nudge-mode-delay-fg)',
    border: 'var(--nudge-mode-delay-border)',
  },
  BREATHING: {
    bg: 'var(--nudge-mode-breathing-bg)',
    fg: 'var(--nudge-mode-breathing-fg)',
    border: 'var(--nudge-mode-breathing-border)',
  },
};

/** The "Allow / Hard Block / Delay / Breathing" badge on a rule card. */
/**
 * The same severity ramp as a chip, for a caller that shows the mode as TEXT on a surface
 * (the popup's status line). The chip foregrounds are near-black by design, so reusing them
 * here would paint every mode the same plain colour, which is the bug this ramp exists to
 * fix, one layer down.
 */
export function modeAccent(mode: SiteMode): string {
  switch (mode) {
    case 'HARD_BLOCK':
      return 'var(--nudge-mode-hard-accent)';
    case 'DELAY':
      return 'var(--nudge-mode-delay-accent)';
    case 'BREATHING':
      return 'var(--nudge-mode-breathing-accent)';
    default:
      return 'var(--nudge-mode-allow-accent)';
  }
}

export function ModeChip({ mode }: { mode: SiteMode }) {
  const tone = MODE_CHIP_TONES[mode];
  return (
    <span
      data-mode={mode}
      style={{
        flexShrink: 0,
        fontSize: 11,
        fontWeight: 700,
        letterSpacing: 0.2,
        padding: '2px 8px',
        borderRadius: 999,
        background: tone.bg,
        color: tone.fg,
        border: `1px solid ${tone.border}`,
      }}
    >
      {SITE_MODE_LABELS[mode]}
    </span>
  );
}

/** Nudge wordmark: the two-bar "pause" glyph from the Android launcher icon. */
export function NudgeMark({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <rect width="24" height="24" rx="6" fill="var(--nudge-primary)" />
      <rect x="8" y="6" width="3" height="12" rx="1.5" fill="var(--nudge-on-primary)" />
      <rect x="13" y="6" width="3" height="12" rx="1.5" fill="var(--nudge-on-primary)" />
    </svg>
  );
}
