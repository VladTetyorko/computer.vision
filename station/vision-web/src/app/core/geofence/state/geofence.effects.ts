import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { EMPTY, Observable, catchError, combineLatest, concat, distinctUntilChanged, exhaustMap, from, map, of, switchMap, tap } from 'rxjs';
import { describeHttpError } from '../../api-error';
import type { GeofenceZone, GeofenceZoneRequest } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { UndoToastService } from '../../../shared/ui/undo-toast.service';
import { ToastService } from '../../toast.service';
import { GeofenceApiActions, GeofencePageActions } from './geofence.actions';
import { ZONES_POLL_INTERVAL_MS } from './geofence.model';
import { geofenceFeature } from './geofence.reducer';

/** Wraps `PollScheduler.schedule` as a cold tick `Observable` — copied per effects file, matching
 *  `seat.effects.ts#ticks$`'s own established precedent. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

type TransportMode = 'idle' | 'live' | 'poll';

function fetchZones(api: VisionApi) {
  return from(api.listGeofences()).pipe(
    map((zones) => GeofenceApiActions.zonesLoaded({ zones })),
    catchError(() => of(GeofenceApiActions.loadFailed())),
  );
}

/**
 * The demand/live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1,
 * ALWAYS-ON-FLOW-PLAN.md §4 Wave C3) — same continuous mode derivation as `tracks.effects.ts#gate$`
 * (see that file's own doc comment for why this replaces the old `liveGated`/`stopPollFn`
 * bookkeeping outright); `activeConsumers` comes from this slice, `liveAvailable` from the `live`
 * slice's own selector, never from an injected `LiveFacade` (NGRX-MIGRATION-PLAN.md §9).
 */
export const gate$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) => {
    const hasDemand$ = store.select(geofenceFeature.selectActiveConsumers).pipe(
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
          return fetchZones(api);
        }
        return concat(fetchZones(api), ticks$(scheduler, ZONES_POLL_INTERVAL_MS).pipe(exhaustMap(() => fetchZones(api))));
      }),
    );
  },
  { functional: true },
);

export const create$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(GeofencePageActions.createRequested),
      switchMap(({ request }) =>
        from(api.createGeofence(request)).pipe(
          map((zone) => GeofenceApiActions.createSucceeded({ zone })),
          catchError((error: unknown) => of(GeofenceApiActions.createFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Resends the zone's full current body with only `edit`'s fields overridden — `GeofenceStore
 *  .replace`'s own "no partial-patch geofence endpoint" doc comment, ported verbatim. */
function buildReplaceRequest(zone: GeofenceZone, edit: Partial<GeofenceZoneRequest>): GeofenceZoneRequest {
  return {
    name: edit.name ?? zone.name,
    kind: edit.kind ?? zone.kind,
    polygon: edit.polygon ?? zone.polygon,
    maxAltitudeMeters: edit.maxAltitudeMeters ?? zone.maxAltitudeMeters,
    enabled: edit.enabled ?? zone.enabled,
  };
}

export const replace$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(GeofencePageActions.replaceRequested),
      switchMap(({ zone, edit }) =>
        from(api.updateGeofence(zone.id, buildReplaceRequest(zone, edit))).pipe(
          map((updated) => GeofenceApiActions.replaceSucceeded({ zone: updated })),
          catchError((error: unknown) => of(GeofenceApiActions.replaceFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const remove$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(GeofencePageActions.removeRequested),
      switchMap(({ zone }) =>
        from(api.deleteGeofence(zone.id)).pipe(
          map(() => GeofenceApiActions.removeSucceeded({ zone })),
          catchError((error: unknown) => of(GeofenceApiActions.removeFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/**
 * The 10s undo-via-recreate offer (docs/plans/done/OPS-CORE-PLAN.md §G-c) — `GeofenceStore.remove`'s
 * own `undoToast.showUndo(...)` call, moved here since `UndoToastService` is a side effect, not
 * state. Dispatches manually (`dispatch: false`): the undo callback itself fires an ordinary
 * `Create Requested` (re-creating gets a new id — see the old class doc's own "nothing in this app's
 * UI is keyed on a zone id surviving a delete/undo round trip"), only if the operator actually clicks it.
 */
export const undoOnRemove$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store), undoToast = inject(UndoToastService)) =>
    actions$.pipe(
      ofType(GeofenceApiActions.removeSucceeded),
      tap(({ zone }) => {
        undoToast.showUndo(`Deleted zone "${zone.name}".`, () => {
          store.dispatch(
            GeofencePageActions.createRequested({
              request: {
                name: zone.name,
                kind: zone.kind,
                polygon: zone.polygon,
                maxAltitudeMeters: zone.maxAltitudeMeters,
                enabled: zone.enabled,
              },
            }),
          );
        });
      }),
    ),
  { functional: true, dispatch: false },
);

/** One explained toast per CRUD failure — `Load Failed` is deliberately excluded, mirroring
 *  `GeofenceStore.refresh`'s own silent background-poll degrade (no toast on a GET failure, ever). */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(GeofenceApiActions.createFailed, GeofenceApiActions.replaceFailed, GeofenceApiActions.removeFailed),
      tap((action) => toasts.error(action.error)),
    ),
  { functional: true, dispatch: false },
);

export const geofenceEffects = { gate$, create$, replace$, remove$, undoOnRemove$, notifyFailure$ };
