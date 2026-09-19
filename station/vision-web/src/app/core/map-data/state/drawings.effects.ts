import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import { ROUTER_NAVIGATED, type RouterNavigatedAction } from '@ngrx/router-store';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { EMPTY, Observable, catchError, combineLatest, concat, distinctUntilChanged, exhaustMap, from, map, of, switchMap, tap } from 'rxjs';
import { describeHttpError } from '../../api-error';
import { VisionApi } from '../../api/vision-api';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { ToastService } from '../../toast.service';
import { createDrawingRequest } from '../drawings-logic';
import { layersFeature } from './layers.reducer';
import { DrawingsApiActions, DrawingsPageActions } from './drawings.actions';
import { DRAWINGS_POLL_INTERVAL_MS } from './drawings.model';
import { drawingsFeature } from './drawings.reducer';

/** Wraps `PollScheduler.schedule` as a cold tick `Observable` — copied per effects file, matching
 *  `seat.effects.ts#ticks$`'s own established precedent. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/** No query/hash — `DrawingsStore#resetOnRouteChange`'s own "path" concept, ported verbatim. */
function pathOf(url: string): string {
  return url.split('?')[0].split('#')[0];
}

function fetchDrawings(api: VisionApi) {
  return from(api.listMapDrawings()).pipe(
    map((drawings) => DrawingsApiActions.loaded({ drawings })),
    catchError(() => of(DrawingsApiActions.loadFailed())),
  );
}

type TransportMode = 'idle' | 'live' | 'poll';

/**
 * The demand/live gate — same continuous mode derivation as `marks.effects.ts#gate$`/
 * `layers.effects.ts#gate$`/`tracks.effects.ts#gate$`/`geofence.effects.ts#gate$`.
 */
export const gate$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) => {
    const hasDemand$ = store.select(drawingsFeature.selectActiveConsumers).pipe(
      map((count) => count > 0),
      distinctUntilChanged(),
    );
    const liveAvailable$ = store.select(liveFeature.selectConnectionState).pipe(map(isLiveAvailable), distinctUntilChanged());
    const mode$ = combineLatest([hasDemand$, liveAvailable$]).pipe(
      map(([hasDemand, liveAvailable]): TransportMode => (!hasDemand ? 'idle' : liveAvailable ? 'live' : 'poll')),
      distinctUntilChanged(),
    );
    return mode$.pipe(
      switchMap((mode): Observable<Action> => {
        if (mode === 'idle') {
          return EMPTY;
        }
        if (mode === 'live') {
          return fetchDrawings(api);
        }
        return concat(fetchDrawings(api), ticks$(scheduler, DRAWINGS_POLL_INTERVAL_MS).pipe(exhaustMap(() => fetchDrawings(api))));
      }),
    );
  },
  { functional: true },
);

/** BUG 3's translation half — see `drawings.reducer.ts#routeChanged`'s own doc comment for the
 *  decision half, and `marks.effects.ts#resetOnRouteChange$`'s identical doc comment for the
 *  test-isolation rationale behind listening on `ROUTER_NAVIGATED` rather than injecting `Router`. */
export const resetOnRouteChange$ = createEffect(
  (actions$ = inject(Actions)) =>
    actions$.pipe(
      ofType(ROUTER_NAVIGATED),
      map((action) => DrawingsPageActions.routeChanged({ path: pathOf((action as RouterNavigatedAction).payload.event.urlAfterRedirects) })),
    ),
  { functional: true },
);

/** `(drawingCompleted)`'s handler — reads the current default layer (`layers` slice) and colour
 *  (this slice) at request time, exactly like `MarksStore.confirmDraft` reads the current palette. */
export const completeDraft$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(DrawingsPageActions.completeDraftRequested),
      concatLatestFrom(() => [store.select(layersFeature.selectDefaultLayerId), store.select(drawingsFeature.selectColorToken)]),
      switchMap(([{ draft, label, colorToken }, targetLayerId, currentColorToken]) =>
        from(api.createMapDrawing(createDrawingRequest(draft, { layerId: targetLayerId, label, colorToken: colorToken ?? currentColorToken }))).pipe(
          map((drawing) => DrawingsApiActions.completeDraftSucceeded({ drawing })),
          catchError((error: unknown) => of(DrawingsApiActions.completeDraftFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Backs both `setDetails()` (label/colour) and `setGeometry()` (points) — one PATCH endpoint
 *  underneath both, same unification as `marks.effects.ts#patch$`. */
export const patch$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(DrawingsPageActions.patchRequested),
      switchMap(({ id, edit }) =>
        from(api.patchMapDrawing(id, edit)).pipe(
          map((drawing) => DrawingsApiActions.patchSucceeded({ drawing })),
          catchError((error: unknown) => of(DrawingsApiActions.patchFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const remove$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(DrawingsPageActions.removeRequested),
      switchMap(({ id }) =>
        from(api.deleteMapDrawing(id)).pipe(
          map(() => DrawingsApiActions.removeSucceeded({ id })),
          catchError((error: unknown) => of(DrawingsApiActions.removeFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** No toast on success — `DrawingsStore` never celebrated a create/patch, only errors. `Load Failed`
 *  is excluded — the silent background-poll degrade every slice in this wave shares. */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(DrawingsApiActions.completeDraftFailed, DrawingsApiActions.patchFailed, DrawingsApiActions.removeFailed),
      tap((action) => toasts.error(action.error)),
    ),
  { functional: true, dispatch: false },
);

export const drawingsEffects = { gate$, resetOnRouteChange$, completeDraft$, patch$, remove$, notifyFailure$ };
