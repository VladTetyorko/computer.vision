import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { groundedBannerText, groundingBlocker } from '../../../core/readiness/readiness-logic';
import { GroundingApiActions, GroundingPageActions } from './grounding.actions';
import { initialGroundingState } from './grounding.model';

/**
 * The `grounding` slice (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N8) — page-scoped, registered
 * by `features/fly/fly.page-routes.ts`, never in `core/state/app-state.ts`.
 *
 * Both derived values are `extraSelectors` rather than facade-level `computed()`s so the parsing
 * rule lives once, beside the state it reads: `readiness-logic.ts#groundingBlocker` owns the
 * `MAINTENANCE_GROUNDED:<kind>:<summary>` contract (S1's frozen wire format) and
 * `#groundedBannerText` owns the wording — the same two pure functions
 * `features/readiness/readiness-facade.ts` calls, which is what keeps the cockpit banner and the
 * per-asset readiness page from ever drifting apart.
 */
export const groundingFeature = createFeature({
  name: 'grounding',
  reducer: createReducer(
    initialGroundingState,
    on(GroundingPageActions.trackRequested, (_state, { assetId }) => ({ assetId, report: undefined })),
    on(GroundingPageActions.resetRequested, () => initialGroundingState),
    // Both API results are applied only while they still name the tracked asset. `read$` already
    // cancels a superseded request with `switchMap`, so this is the belt to that braces: a response
    // that resolved in the same tick as the switch can still arrive.
    on(GroundingApiActions.readSucceeded, (state, { assetId, report }) =>
      state.assetId === assetId ? { ...state, report } : state,
    ),
    on(GroundingApiActions.readFailed, (state, { assetId }) =>
      state.assetId === assetId ? { ...state, report: undefined } : state,
    ),
  ),
  extraSelectors: ({ selectReport }) => {
    const selectGroundedBlocker = createSelector(selectReport, (report) =>
      report ? groundingBlocker(report.blockers) : undefined,
    );
    const selectGroundedReason = createSelector(selectGroundedBlocker, (blocker) =>
      blocker ? groundedBannerText(blocker) : undefined,
    );
    return { selectGroundedBlocker, selectGroundedReason };
  },
});
