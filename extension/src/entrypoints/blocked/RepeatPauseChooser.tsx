/**
 * "Pause again every": the repeating pause, offered at the moment it is wanted, during the
 * pause itself. Only on a site-level pause (`repeatPauseOffered`). A feature gate's overlay has
 * no repeat, so its block page must not suggest one.
 *
 * Keeps its own state, so a save never re-renders the countdown around it.
 */

import { useState } from 'react';
import type { BlockContext } from '../../core/protocol';
import { repeatPauseChoices, repeatPauseSentence } from '../../core/repeatPause';
import { saveRepeatPause } from './repeatPauseSave';

const OFF = 'off';

const TEXT_STYLE = { margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' };

export function RepeatPauseChooser({ context, target }: { context: BlockContext; target: string }) {
  const [saved, setSaved] = useState(context.repeatPauseMinutes);
  const [selected, setSelected] = useState(context.repeatPauseMinutes);
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);

  if (!context.repeatPauseOffered) return null;

  const choices = repeatPauseChoices(saved, context.strictModeEnabled, context.tempAllowMinutes);

  if (choices.length <= 1) {
    return saved === null ? null : <p style={TEXT_STYLE}>{repeatPauseSentence(saved)}</p>;
  }

  const handleChange = (raw: string) => {
    const minutes = raw === OFF ? null : Number(raw);
    setSelected(minutes);
    setSaving(true);
    setFailed(false);
    void saveRepeatPause(target, minutes).then((ok) => {
      setSaving(false);
      if (ok) {
        setSaved(minutes);
        return;
      }
      // Back to what is really stored, or the select would claim a setting that does not exist.
      setSelected(saved);
      setFailed(true);
    });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
      <label style={{ ...TEXT_STYLE, display: 'flex', alignItems: 'center', gap: 8 }}>
        Pause again every
        <select
          value={selected ?? OFF}
          disabled={saving}
          onChange={(e) => handleChange(e.target.value)}
          style={{
            padding: '4px 8px',
            borderRadius: 8,
            border: '1px solid var(--nudge-outline)',
            background: 'var(--nudge-background)',
            color: 'var(--nudge-on-surface)',
            fontSize: 13,
          }}
        >
          {choices.map((choice) => (
            <option key={choice ?? OFF} value={choice ?? OFF}>
              {choice === null ? 'Off' : `${choice} min`}
            </option>
          ))}
        </select>
      </label>
      {failed && (
        <p style={{ margin: 0, fontSize: 12, color: 'var(--nudge-danger)' }}>Couldn&apos;t save.</p>
      )}
    </div>
  );
}
