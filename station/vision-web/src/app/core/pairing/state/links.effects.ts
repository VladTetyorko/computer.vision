import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import {
  EMPTY,
  Observable,
  catchError,
  concat,
  distinctUntilChanged,
  exhaustMap,
  filter,
  from,
  groupBy,
  map,
  merge,
  mergeMap,
  of,
  startWith,
  switchMap,
  takeUntil,
  tap,
} from 'rxjs';
import type { AssetScopedTransport } from '../../live/live-fallback-logic';
import { VisionApi } from '../../api/vision-api';
import { describeHttpError } from '../../api-error';
import { PollScheduler } from '../../poll-scheduler';
import { ToastService } from '../../toast.service';
import { LinksApiActions, LinksLiveActions, LinksPageActions } from './links.actions';

/** How often one asset's link group is re-read while polling is active — matches `LinksStore`'s own cadence (a failover/pin change matters within a few seconds, not `GeoStore`'s sub-2s one). */
const POLL_INTERVAL_MS = 5_000;

/** Wraps `PollScheduler.schedule` as a cold `Observable` tick source — see `geo.effects.ts#ticks$`'s identical helper. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/**
 * Polls `GET /api/assets/{id}/links` for whichever host is currently in `'poll'` transport —
 * structurally identical to `geo.effects.ts#track$` (same three-level `groupBy`/`switchMap`/
 * `switchMap` isolation, same reasons); see that file's own doc comment. The poll-vs-live decision
 * and the Defect-A seed trigger are both the facade's own job (`LinksFacade`'s constructor
 * `effect()`s), never this effect reading `LiveFacade` directly.
 */
export const track$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    actions$.pipe(
      ofType(LinksPageActions.trackRequested),
      groupBy((action) => action.hostId, {
        duration: (group$) =>
          actions$.pipe(ofType(LinksPageActions.hostReleased), filter((released) => released.hostId === group$.key)),
      }),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ hostId, assetId, initialTransport }) =>
            actions$.pipe(
              ofType(LinksLiveActions.transportResolved),
              filter((resolved) => resolved.hostId === hostId),
              map((resolved) => resolved.transport),
              startWith<AssetScopedTransport>(initialTransport),
              distinctUntilChanged(),
              switchMap((transport) =>
                transport === 'live'
                  ? EMPTY
                  : merge(of(undefined), ticks$(scheduler, POLL_INTERVAL_MS)).pipe(
                      exhaustMap(() => pollOnce(api, hostId, assetId)),
                    ),
              ),
              takeUntil(
                actions$.pipe(ofType(LinksPageActions.resetRequested), filter((reset) => reset.hostId === hostId)),
              ),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** The one-shot Defect-A read — `LinksFacade` fires `Seed Requested` whenever the resolved transport
 *  is `'live'` and neither source has data yet; `exhaustMap` per host mirrors the old `seedOnce`'s
 *  own "never re-fired while one is already in flight" posture. */
export const seed$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(LinksPageActions.seedRequested),
      groupBy((action) => action.hostId),
      mergeMap((group$) => group$.pipe(exhaustMap(({ hostId, assetId }) => pollOnce(api, hostId, assetId)))),
    ),
  { functional: true },
);

/** `LinksFacade#refreshNow`'s own forced immediate re-read — independent of {@link track$}'s own ticking. */
export const refreshNow$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(LinksPageActions.refreshNowRequested),
      groupBy((action) => action.hostId),
      mergeMap((group$) => group$.pipe(exhaustMap(({ hostId, assetId }) => pollOnce(api, hostId, assetId)))),
    ),
  { functional: true },
);

export const pin$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(LinksPageActions.pinRequested),
      groupBy((action) => action.hostId),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ hostId, assetId, linkId }) =>
            from(api.pinAssetLink(assetId, linkId)).pipe(
              map((group) => LinksApiActions.pinSucceeded({ hostId, group })),
              catchError((error: unknown) => of(LinksApiActions.pinFailed({ hostId, error: describeHttpError(error) }))),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

export const releasePin$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(LinksPageActions.releasePinRequested),
      groupBy((action) => action.hostId),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ hostId, assetId }) =>
            from(api.releaseAssetLinkPin(assetId)).pipe(
              map((group) => LinksApiActions.releasePinSucceeded({ hostId, group })),
              catchError((error: unknown) =>
                of(LinksApiActions.releasePinFailed({ hostId, error: describeHttpError(error) })),
              ),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** `pin()`/`releasePin()` are operator-initiated writes, unlike the background poll's own silent-degrade — mirrors `LinksStore#pin`/`#releasePin`'s own direct `this.toasts.error(...)` call, moved to the one shared seam (`org.effects.ts#notifyFailure$`'s own established pattern). */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(LinksApiActions.pinFailed, LinksApiActions.releasePinFailed),
      tap((action) => toasts.error(action.error)),
    ),
  { functional: true, dispatch: false },
);

function pollOnce(api: VisionApi, hostId: string, assetId: string) {
  return concat(
    of(LinksApiActions.pollStarted({ hostId })),
    from(api.getAssetLinks(assetId)).pipe(
      map((group) => LinksApiActions.pollSucceeded({ hostId, group })),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 404) {
          return of(LinksApiActions.pollDisabled({ hostId }));
        }
        return of(LinksApiActions.pollFailed({ hostId })); // Silent-degrade otherwise — see class doc.
      }),
    ),
  );
}

export const linksEffects = { track$, seed$, refreshNow$, pin$, releasePin$, notifyFailure$ };
