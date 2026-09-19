import { inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, from, map, of, switchMap, tap } from 'rxjs';
import { describeHttpError } from '../../api-error';
import { VisionApi } from '../../api/vision-api';
import { ToastService } from '../../toast.service';
import { TrainingApiActions, TrainingPageActions } from './training.actions';

/** Stable per-file console tag, mirroring the old `TrainingStore`'s own `[training]` prefix. */
const LOG_PREFIX = '[training]';

/**
 * Feature gating, done honestly, with no dedicated "is training enabled" endpoint:
 * `vision.training.enabled=false` removes `DatasetController` entirely, so `GET /api/datasets` is
 * the one call in this whole feature whose 404 can **only** mean "the controller isn't here" (every
 * other endpoint's 404 can legitimately mean "unknown id" instead) — see `TrainingStore`'s own
 * (deleted) class doc, preserved here.
 */
export const refresh$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(TrainingPageActions.refreshRequested),
      switchMap(({ quiet }) =>
        from(api.listDatasets()).pipe(
          map((response) => TrainingApiActions.refreshSucceeded({ datasets: response.datasets })),
          catchError((error: unknown) => {
            if (error instanceof HttpErrorResponse && error.status === 404) {
              return of(TrainingApiActions.refreshNotFound());
            }
            return of(TrainingApiActions.refreshFailed({ error: describeHttpError(error), quiet }));
          }),
        ),
      ),
    ),
  { functional: true },
);

export const create$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(TrainingPageActions.createRequested),
      switchMap(({ request }) =>
        from(
          (async () => {
            const dataset = await api.createDataset(request);
            const response = await api.listDatasets();
            return TrainingApiActions.createSucceeded({
              dataset,
              datasets: response.datasets,
              message: `Created dataset "${dataset.name}".`,
            });
          })(),
        ).pipe(catchError((error: unknown) => of(TrainingApiActions.createFailed({ error: describeHttpError(error) })))),
      ),
    ),
  { functional: true },
);

export const deleteDataset$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(TrainingPageActions.deleteRequested),
      switchMap(({ id, name }) =>
        from(
          (async () => {
            await api.deleteDataset(id);
            const response = await api.listDatasets();
            return TrainingApiActions.deleteSucceeded({ datasets: response.datasets, message: `Deleted "${name}".` });
          })(),
        ).pipe(catchError((error: unknown) => of(TrainingApiActions.deleteFailed({ error: describeHttpError(error) })))),
      ),
    ),
  { functional: true },
);

export const notifySuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(TrainingApiActions.createSucceeded, TrainingApiActions.deleteSucceeded),
      tap((action) => toasts.ok(action.message)),
    ),
  { functional: true, dispatch: false },
);

/** One explained toast per failure — a quiet `refreshFailed` (an internal re-read after another
 *  action already reported its own outcome) is the one deliberate exception, mirroring `OrgStore`'s
 *  identical `notifyFailure$`. `refreshNotFound` never toasts at all — see class doc. */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(TrainingApiActions.refreshFailed, TrainingApiActions.createFailed, TrainingApiActions.deleteFailed),
      tap((action) => {
        console.warn(`${LOG_PREFIX} ${action.type}`, { error: action.error });
        if (action.type === TrainingApiActions.refreshFailed.type && action.quiet) {
          return;
        }
        toasts.error(action.error);
      }),
    ),
  { functional: true, dispatch: false },
);

export const trainingEffects = { refresh$, create$, deleteDataset$, notifySuccess$, notifyFailure$ };
