import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { CreateDatasetRequest, Dataset } from '../../api/models';

/** Every command `DatasetsFacade`/`DatasetDetailFacade` can issue. */
export const TrainingPageActions = createActionGroup({
  source: 'Training Page',
  events: {
    'Refresh Requested': props<{ quiet: boolean }>(),
    'Create Requested': props<{ request: CreateDatasetRequest }>(),
    'Delete Requested': props<{ id: string; name: string }>(),
  },
});

/**
 * Every outcome. `Refresh Not Found` is the one 404 that can only mean "the controller isn't here"
 * (see `training.effects.ts` class doc) — a first-class outcome, not an error; every other refresh
 * failure carries the caller's own `quiet` flag through, mirroring `OrgApiActions.refreshFailed`.
 * A mutation's own success carries the *whole* refreshed `datasets` list (the effect re-reads after
 * mutating, exactly like `TrainingStore`'s own `await this.refresh({ quiet: true })`) plus a
 * pre-built toast `message`.
 */
export const TrainingApiActions = createActionGroup({
  source: 'Training API',
  events: {
    'Refresh Succeeded': props<{ datasets: readonly Dataset[] }>(),
    'Refresh Not Found': emptyProps(),
    'Refresh Failed': props<{ error: string; quiet: boolean }>(),
    'Create Succeeded': props<{ dataset: Dataset; datasets: readonly Dataset[]; message: string }>(),
    'Create Failed': props<{ error: string }>(),
    'Delete Succeeded': props<{ datasets: readonly Dataset[]; message: string }>(),
    'Delete Failed': props<{ error: string }>(),
  },
});
