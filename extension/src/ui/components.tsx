/**
 * Shared UI primitives on the Nudge design tokens (src/ui/tokens.css).
 * Small and hand-rolled deliberately — no UI kit, keep the bundle lean (ext-08 Stack).
 *
 * Iconography rule: NEVER emoji-as-icon. Use inline SVG or text.
 */

import type { CSSProperties, ReactNode } from 'react';

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
        marginTop: 32,
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
