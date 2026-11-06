/**
 * The chooser's save, and how long the end of a pause waits for it.
 *
 * The grant's length is read from the rule when the pause completes, so a choice made in the
 * last second has to reach the worker first or that pause gets the old length. It must not be
 * able to hold the pause hostage either: a worker that is slow or refusing costs the choice,
 * never the site. Hence a wait with a ceiling.
 */

import { send } from '../../ui/rpc';

const SAVE_WAIT_MS = 1500;

let pending: Promise<boolean> | null = null;

/** Resolves to whether the worker saved it. Never rejects. */
export function saveRepeatPause(target: string, minutes: number | null): Promise<boolean> {
  const saved = send({ type: 'SET_REPEAT_PAUSE', target, minutes })
    .then((result) => result.ok)
    .catch(() => false)
    .finally(() => {
      if (pending === saved) pending = null;
    });
  pending = saved;
  return saved;
}

/** Resolves when no save is out, or after the ceiling. Never rejects. */
export function repeatPauseSaveSettled(): Promise<unknown> {
  if (pending === null) return Promise.resolve();
  let timer: ReturnType<typeof setTimeout>;
  const ceiling = new Promise((resolve) => {
    timer = setTimeout(resolve, SAVE_WAIT_MS);
  });
  return Promise.race([pending, ceiling]).finally(() => clearTimeout(timer));
}
