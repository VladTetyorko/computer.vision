import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { LiveSocketActions } from '../../live/state/live.actions';
import { SystemStatusApiActions, SystemStatusPageActions } from './system-status.actions';
import { initialSystemStatusState, type SystemStatusState } from './system-status.model';

/**
 * `SystemStatusStore`'s own reducer half, ported wholesale (docs/plans/active/NGRX-MIGRATION-PLAN.md
 * wave N4b). **Degrades to stale-but-present data, not a wiped page, on a failed fetch** — `status`
 * is only ever replaced by a *successful* fetch (REST or the always-on `system` SSE topic); a
 * failure updates only {@link SystemStatusState.error} so a caller can show a small "couldn't
 * refresh — showing last-known status" note, exactly like the class this replaces.
 *
 * The `system` SSE topic is folded directly here (mirrors `fleet.reducer.ts`'s identical `devices`
 * handling) rather than through a `LiveFacade` read inside a facade `effect()` — a live arrival is by
 * definition current-and-good, so it also clears {@link SystemStatusState.error} exactly as a
 * successful REST refresh would.
 */
export const systemStatusFeature = createFeature({
  name: 'systemStatus',
  reducer: createReducer(
    initialSystemStatusState,
    on(SystemStatusPageActions.refreshRequested, (state): SystemStatusState => ({ ...state, loading: true })),
    on(SystemStatusApiActions.refreshSucceeded, (state, { status }): SystemStatusState => ({
      ...state,
      status,
      error: undefined,
      loading: false,
    })),
    on(SystemStatusApiActions.refreshFailed, (state, { error }): SystemStatusState => ({ ...state, error, loading: false })),
    on(LiveSocketActions.envelopeReceived, (state, { envelope }): SystemStatusState =>
      envelope.type === 'system' ? { ...state, status: envelope.payload, error: undefined } : state,
    ),
  ),
  extraSelectors: ({ selectStatus }) => ({
    /** `undefined` before the first successful fetch — the shell rollup dot's own §5.2 input. */
    selectOverall: createSelector(selectStatus, (status) => status?.overall),
  }),
});
