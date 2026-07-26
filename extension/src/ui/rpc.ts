/**
 * Typed wrapper over chrome.runtime.sendMessage for the UI surfaces.
 * The response type is derived from the request type via the protocol's ResponseFor map,
 * so a mismatched handler is a compile error.
 */

import type { Request, ResponseFor } from '../core/protocol';

export async function send<T extends Request>(
  request: T,
): Promise<ResponseFor<T['type']>> {
  return (await chrome.runtime.sendMessage(request)) as ResponseFor<T['type']>;
}
