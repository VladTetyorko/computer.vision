import type { Dataset } from '../../api/models';

/**
 * The `features/labeling/**` surface's dataset list (docs/plans/done/CV-TRAINING-PLAN.md Wave T5;
 * migrated off `TrainingStore` per docs/plans/active/NGRX-MIGRATION-PLAN.md wave N7). Registered
 * app-wide in `provideAppState()`, mirroring the old store's `providedIn: 'root'` posture. **Lazy,
 * not self-initializing** — nothing dispatches `Refresh Requested` on boot; `DatasetsPage`'s own
 * facade calls it once actually reached.
 */
export interface TrainingState {
  readonly datasets: readonly Dataset[];
  readonly loading: boolean;
  /** `false` until the first `refresh()` settles — lets a page tell "still loading" from "genuinely no datasets". */
  readonly loaded: boolean;
  /** `true` once a `refresh()` has confirmed `vision.training.enabled=false` on this deployment (a 404 on `GET /api/datasets`) — see `training.effects.ts` class doc. */
  readonly disabled: boolean;
}

export const initialTrainingState: TrainingState = {
  datasets: [],
  loading: false,
  loaded: false,
  disabled: false,
};
