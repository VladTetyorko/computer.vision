import { Injectable, inject } from '@angular/core';
import { Actions, ofType } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { firstValueFrom } from 'rxjs';
import { take } from 'rxjs/operators';
import type { CreateDatasetRequest, Dataset } from '../api/models';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { TrainingApiActions, TrainingPageActions } from './state/training.actions';
import { trainingFeature } from './state/training.reducer';

/**
 * The training slice's read/dispatch boundary (docs/plans/done/NGRX-MIGRATION-PLAN.md §2),
 * replacing `TrainingStore`. Every method keeps that class's exact name/signature/return contract so
 * `DatasetsPage`/`DatasetDetailFacade`/`ModelsFacade`/`ReplayFacade` change only `inject(TrainingStore)`
 * → `inject(TrainingFacade)`.
 *
 * **Page-provided, not `providedIn: 'root'`** (wave N-split, NGRX-MIGRATION-PLAN.md §9). The two
 * classes that inject it — `DatasetsFacade` and `ReplayFacade` — are themselves provided by
 * `DatasetsPage` and `ReplayPage`, so this now shares their lifetime and the `training` slice is
 * registered on `manage/training` and `assets/:assetId/replay/:usageId` rather than at the root.
 * **Behaviour change this carries:** the dataset list is no longer cached across those two pages, so
 * each re-reads it on entry instead of inheriting the other's copy.
 */
@Injectable()
export class TrainingFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  readonly datasets = this.store.selectSignal(trainingFeature.selectDatasets);
  readonly loading = this.store.selectSignal(trainingFeature.selectLoading);
  readonly loaded = this.store.selectSignal(trainingFeature.selectLoaded);
  readonly disabled = this.store.selectSignal(trainingFeature.selectDisabled);

  /**
   * Re-reads the dataset list. Toasts once on a genuine failure unless `quiet`; a 404 sets
   * {@link disabled} instead of toasting. Three possible outcomes (`Succeeded`/`NotFound`/`Failed`),
   * so this waits on all three directly rather than through {@link dispatchAndAwait} (built for
   * exactly two).
   */
  async refresh(options: { quiet?: boolean } = {}): Promise<void> {
    const settled = firstValueFrom(
      this.actions$.pipe(
        ofType(TrainingApiActions.refreshSucceeded, TrainingApiActions.refreshNotFound, TrainingApiActions.refreshFailed),
        take(1),
      ),
    );
    this.store.dispatch(TrainingPageActions.refreshRequested({ quiet: options.quiet ?? false }));
    await settled;
  }

  async createDataset(request: CreateDatasetRequest): Promise<Dataset | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      TrainingPageActions.createRequested({ request }),
      TrainingApiActions.createSucceeded,
      TrainingApiActions.createFailed,
      (action) => action.dataset,
      () => null,
    );
  }

  async deleteDataset(id: string, name: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      TrainingPageActions.deleteRequested({ id, name }),
      TrainingApiActions.deleteSucceeded,
      TrainingApiActions.deleteFailed,
      () => true,
      () => false,
    );
  }
}
