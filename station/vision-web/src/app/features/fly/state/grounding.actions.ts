import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { ReadinessReport } from '../../../core/api/models';

/**
 * Sourced from `CockpitFacade`'s own `activeAssetId`-keyed `effect()` — not from a component
 * template — exactly as it drove `GroundingStore#track`/`#reset` before wave N8. "Page" is still
 * the right source: it is a decision made in response to the operator picking an asset.
 */
export const GroundingPageActions = createActionGroup({
  source: 'Grounding Page',
  events: {
    /**
     * `GroundingFacade#track` filters an *unchanged* `assetId` out synchronously and never dispatches
     * this — so every occurrence genuinely starts a new session, and the reducer can clear the
     * previous report unconditionally. That guard lives in the facade rather than the reducer
     * because an effect sees the action either way; deduping in the reducer alone would still refetch.
     */
    'Track Requested': props<{ assetId: string }>(),
    'Reset Requested': emptyProps(),
  },
});

export const GroundingApiActions = createActionGroup({
  source: 'Grounding API',
  events: {
    'Read Succeeded': props<{ assetId: string; report: ReadinessReport }>(),
    /**
     * Carries the asset id only. The thrown error is deliberately **not** on the action: it would
     * trip `strictActionSerializability`, and nothing downstream needs it — a failed read degrades
     * to "nothing confirmed" (see `GroundingState.report`), and the diagnostic itself is logged
     * where it is caught, in `grounding.effects.ts#read$`.
     */
    'Read Failed': props<{ assetId: string }>(),
  },
});
