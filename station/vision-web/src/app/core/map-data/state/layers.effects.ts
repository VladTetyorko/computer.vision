import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import {
  EMPTY,
  Observable,
  catchError,
  combineLatest,
  concat,
  debounceTime,
  distinctUntilChanged,
  exhaustMap,
  filter,
  from,
  map,
  of,
  switchMap,
  tap,
} from 'rxjs';
import { describeHttpError } from '../../api-error';
import { VisionApi } from '../../api/vision-api';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { ToastService } from '../../toast.service';
import { LayersApiActions, LayersPageActions } from './layers.actions';
import { GRANTS_RECONCILE_DEBOUNCE_MS, LAYERS_POLL_INTERVAL_MS } from './layers.model';
import { layersFeature } from './layers.reducer';

/** Wraps `PollScheduler.schedule` as a cold tick `Observable` — copied per effects file, matching
 *  `seat.effects.ts#ticks$`'s own established precedent. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

type TransportMode = 'idle' | 'live' | 'poll';

function fetchLayers(api: VisionApi) {
  return from(api.listMapLayers()).pipe(
    map((layers) => LayersApiActions.loaded({ layers })),
    catchError(() => of(LayersApiActions.loadFailed())),
  );
}

/**
 * The demand/live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1,
 * ALWAYS-ON-FLOW-PLAN.md §4 Wave C3) — same continuous mode derivation as
 * `tracks.effects.ts#gate$`/`geofence.effects.ts#gate$` (see either file's own doc comment for why
 * this replaces the old `liveGated`/`stopPollFn` bookkeeping outright).
 */
export const gate$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) => {
    const hasDemand$ = store.select(layersFeature.selectActiveConsumers).pipe(
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
          return fetchLayers(api);
        }
        return concat(fetchLayers(api), ticks$(scheduler, LAYERS_POLL_INTERVAL_MS).pipe(exhaustMap(() => fetchLayers(api))));
      }),
    );
  },
  { functional: true },
);

/**
 * The L1c fix (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L1) — `LayersStore
 * .scheduleGrantsReconcile`'s own doc comment, ported verbatim: a layer arriving over SSE never
 * carries `grants` (MAP-REWORK-PLAN.md §4.3), so a revocation can only ever be observed through a
 * debounced re-`GET`. Deliberately independent of `activeConsumers` — other slices
 * (`marks`/`drawings`) read this slice's access decisions even on a page that never itself calls
 * `LayersFacade.activate()`.
 */
export const grantsReconcile$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(LiveSocketActions.envelopeReceived),
      filter(({ envelope }) => envelope.type === 'map' && envelope.payload.entity === 'layer'),
      debounceTime(GRANTS_RECONCILE_DEBOUNCE_MS),
      switchMap(() => fetchLayers(api)),
    ),
  { functional: true },
);

export const create$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(LayersPageActions.createRequested),
      switchMap(({ request }) =>
        from(api.createMapLayer(request)).pipe(
          map((layer) => LayersApiActions.createSucceeded({ layer })),
          catchError((error: unknown) => of(LayersApiActions.createFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const rename$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(LayersPageActions.renameRequested),
      switchMap(({ layerId, name }) =>
        from(api.renameMapLayer(layerId, { name })).pipe(
          map((layer) => LayersApiActions.renameSucceeded({ layer })),
          catchError((error: unknown) => of(LayersApiActions.renameFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** No undo offered on success — `layers.reducer.ts#removeSucceeded`'s own doc comment explains why. */
export const remove$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(LayersPageActions.removeRequested),
      switchMap(({ layerId }) =>
        from(api.deleteMapLayer(layerId)).pipe(
          map(() => LayersApiActions.removeSucceeded({ layerId })),
          catchError((error: unknown) => of(LayersApiActions.removeFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const setGrants$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(LayersPageActions.setGrantsRequested),
      switchMap(({ layerId, grants }) =>
        from(api.setMapLayerGrants(layerId, { grants })).pipe(
          map((layer) => LayersApiActions.setGrantsSucceeded({ layer })),
          catchError((error: unknown) => of(LayersApiActions.setGrantsFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const notifyCreateSuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(LayersApiActions.createSucceeded),
      tap(({ layer }) => toasts.ok(`Created layer "${layer.name}".`)),
    ),
  { functional: true, dispatch: false },
);

export const notifyGrantsSuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(LayersApiActions.setGrantsSucceeded),
      tap(({ layer }) => toasts.ok(`Updated access for "${layer.name}".`)),
    ),
  { functional: true, dispatch: false },
);

/** `Load Failed` is deliberately excluded, mirroring `LayersStore.refresh`'s own silent
 *  background-poll degrade (no toast on a GET failure, ever). */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(LayersApiActions.createFailed, LayersApiActions.renameFailed, LayersApiActions.removeFailed, LayersApiActions.setGrantsFailed),
      tap((action) => toasts.error(action.error)),
    ),
  { functional: true, dispatch: false },
);

export const layersEffects = {
  gate$,
  grantsReconcile$,
  create$,
  rename$,
  remove$,
  setGrants$,
  notifyCreateSuccess$,
  notifyGrantsSuccess$,
  notifyFailure$,
};
