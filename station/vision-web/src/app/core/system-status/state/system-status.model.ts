import type { SystemStatus } from '../../api/models';

/**
 * `SystemStatusStore`'s own state shape, ported wholesale (docs/plans/done/NGRX-MIGRATION-PLAN.md
 * wave N4b). `status` is `undefined` only before the first fetch ever succeeds; a later failure
 * leaves it exactly as it was and only sets {@link SystemStatusState.error} — see
 * `system-status.reducer.ts`'s own doc comment for the "stale-but-present, never wiped" contract
 * this shape exists to hold.
 */
export interface SystemStatusState {
  readonly status: SystemStatus | undefined;
  readonly loading: boolean;
  /** Set on the most recent failed fetch, cleared on the next successful one (poll *or* live). */
  readonly error: string | undefined;
}

export const initialSystemStatusState: SystemStatusState = {
  status: undefined,
  loading: false,
  error: undefined,
};

/** `GET /api/system/status`'s own re-poll cadence while live is unavailable — a diagnostics-page
 *  reading, not a cockpit one, hence slower than `fleet.model.ts`'s 5s (see `system-status.effects.ts`
 *  `gate$`'s own doc comment). */
export const SYSTEM_STATUS_POLL_INTERVAL_MS = 15_000;
