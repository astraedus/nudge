/**
 * "Daily 2-minute pass" — rendered below the primary action on ALL THREE block views
 * (Android parity: EmergencyPassAction). Three states: available, spent (visibly
 * disabled, still rendered), or hidden entirely (Strict Mode / pass disabled — a
 * commitment lock must not have a one-tap bypass).
 */

import { useState } from 'react';
import type { BlockContext } from '../../core/protocol';
import { Button } from '../../ui/components';
import { formatNextPass } from '../../ui/format';
import { send } from '../../ui/rpc';
import { isNavigableTarget } from './BlockPage';

export function EscapeHatch({ context, target }: { context: BlockContext; target: string }) {
  const [pending, setPending] = useState(false);

  if (!context.passEnabled || context.strictModeEnabled) {
    return null;
  }

  if (!context.passAvailable) {
    return (
      <Button variant="muted" disabled style={{ marginTop: 16 }}>
        Daily pass used · next in {formatNextPass(context.passNextAvailableMs)}
      </Button>
    );
  }

  const handleUsePass = async () => {
    if (pending) return;
    setPending(true);
    try {
      const result = await send({ type: 'USE_EMERGENCY_PASS', target });
      if (result.ok && isNavigableTarget(target)) {
        window.location.replace(target);
        return;
      }
    } catch {
      // Service worker asleep/restarting — fall through and re-enable the button below
      // so the user can try again rather than being stuck on a dead tap.
    }
    setPending(false);
  };

  return (
    <Button variant="muted" onClick={handleUsePass} disabled={pending} style={{ marginTop: 16 }}>
      Use for 2 minutes · once a day
    </Button>
  );
}
