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
