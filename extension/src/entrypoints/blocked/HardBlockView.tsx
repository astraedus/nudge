/**
 * Hard Block — the rotating hardBlockMessage, a "Go Back" button, no way through.
 * `decision.limitReached` additionally shows "Daily limit reached" (Android parity);
 * the "(limit reached)" suffix already lives inside `decision.ruleName` (shown by the
 * RuleFooter in BlockPage) and must NOT be added again here.
 *
 * The Escape Hatch is NOT rendered here — `BlockPage` owns it, so that it always comes
 * after the allowed-channels list rather than before it. See the comment there.
 */

import type { BlockContext } from '../../core/protocol';
import { Button } from '../../ui/components';

function goBack() {
  if (window.history.length > 1) {
    window.history.back();
  } else {
    window.location.replace('about:blank');
  }
}

function BlockGlyph() {
  return (
    <svg width={56} height={56} viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="10" fill="none" stroke="var(--nudge-error)" strokeWidth="2" />
      <line x1="6" y1="6" x2="18" y2="18" stroke="var(--nudge-error)" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

export function HardBlockView({ context }: { context: BlockContext }) {
  const decision = context.decision;
  if (decision.type !== 'BLOCK') return null;

  /**
   * WHOSE budget ran out.
   *
   * A gate carries its own daily budget, so "Daily limit reached" on a page reached via
   * `/shorts/` is a statement about the user's own settings — and the unqualified version
   * is the WRONG statement: it says YouTube is out of time when only the Shorts budget is
   * spent, sending someone to look for a site limit they never set. `gateLabel` is
   * non-null exactly when the acting rule is that gate rather than the whole site (see
   * `BlockContext`), so it is also the right thing to name here.
   */
  const limitLabel =
    context.gateLabel === null
      ? 'Daily limit reached'
      : `Daily ${context.gateLabel} limit reached`;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 20, width: '100%' }}>
      <BlockGlyph />
      <p
        style={{
          margin: 0,
          fontSize: 19,
          fontWeight: 600,
          lineHeight: 1.45,
          color: 'var(--nudge-on-surface)',
        }}
      >
        {context.hardBlockMessage}
      </p>
      {decision.limitReached && (
        <p style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--nudge-danger)' }}>
          {limitLabel}
        </p>
      )}
      <Button variant="primary" onClick={goBack} style={{ marginTop: 8 }}>
        Go Back
      </Button>
    </div>
  );
}
