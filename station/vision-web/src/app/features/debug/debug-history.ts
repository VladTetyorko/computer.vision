/** One row of the Debug tab's in-memory request log. */
export interface DebugHistoryEntry {
  readonly method: string;
  readonly path: string;
  readonly status: number;
  readonly statusText: string;
  readonly ms: number;
  /** Carried along so clicking a history row can restore the body too, not just method/path. */
  readonly body?: string;
}

/** "last ~20 requests" per docs/plans/done/WEB-PLAN.md W5. */
export const DEBUG_HISTORY_LIMIT = 20;

/** Newest first, capped — this is a recency log for re-filling the form, not an archive. */
export function pushHistoryEntry(
  history: readonly DebugHistoryEntry[],
  entry: DebugHistoryEntry,
  limit: number = DEBUG_HISTORY_LIMIT,
): readonly DebugHistoryEntry[] {
  return [entry, ...history].slice(0, limit);
}

/**
 * A history row's status badge (docs/plans/active/fly-debug-redesign/PLAN.md §1.1): a colored dot +
 * `.mono` code, three-way by status family — 2xx `ok`, 4xx `warn`, 5xx or an unreachable backend
 * `danger`. Deliberately not `isSuccessStatus`'s binary ok/danger (`debug-response.ts`) — that stays
 * the response pane's own chip semantics, unchanged; the rail is a list, not the one canonical chip.
 * `status === 0` is `DebugApiService`'s own convention for "never reached the server at all".
 */
export type StatusFamily = 'ok' | 'warn' | 'danger';

export function statusFamily(status: number): StatusFamily {
  if (status === 0 || status >= 500) {
    return 'danger';
  }
  if (status >= 400) {
    return 'warn';
  }
  return 'ok';
}
