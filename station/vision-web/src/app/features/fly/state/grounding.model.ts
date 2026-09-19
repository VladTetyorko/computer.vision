import type { ReadinessReport } from '../../../core/api/models';

/**
 * One asset's custody-grounding state for the Fly cockpit (docs/plans/active/ASSET-FLOWS-PLAN.md §2
 * "S1 gate semantics", wave WB1; NgRx since docs/plans/active/NGRX-MIGRATION-PLAN.md wave N8,
 * replacing `GroundingStore`).
 *
 * **Deliberately not keyed by host**, unlike `core/geo/state/geo.model.ts`'s own `byHostId` record.
 * Only `CockpitPage` provides `GroundingFacade`, and the slice itself is registered on the `/fly`
 * route's pathless parent — the picker and the cockpit share one registration, and two cockpits
 * never coexist. {@link GroundingState.assetId} is what guards against a *stale* answer rather than
 * a *concurrent* one: a response is applied only while it still names the asset being tracked,
 * exactly the `lastTrackedAssetId === assetId` check `GroundingStore#load` made inline.
 */
export interface GroundingState {
  /** The asset currently tracked, or `undefined` while idle (before the first `track()`, or after `reset()`). */
  readonly assetId: string | undefined;
  /**
   * The latest `GET /api/assets/{id}/readiness` answer for {@link assetId}. `undefined` covers three
   * distinct-but-equivalent cases on purpose — not yet fetched, fetch in flight, and fetch failed —
   * because all three mean the same thing to the banner: nothing was *confirmed*, so say nothing.
   * A failed read must never render as a grounded vehicle (CLAUDE.md rule 7), and must never block
   * Arm over a read failure that has nothing to do with grounding.
   */
  readonly report: ReadinessReport | undefined;
}

export const initialGroundingState: GroundingState = {
  assetId: undefined,
  report: undefined,
};
