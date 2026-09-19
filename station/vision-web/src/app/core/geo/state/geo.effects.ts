import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { EMPTY, Observable, catchError, distinctUntilChanged, exhaustMap, filter, from, groupBy, map, merge, mergeMap, of, startWith, switchMap, takeUntil } from 'rxjs';
import type { AssetScopedTransport } from '../../live/live-fallback-logic';
import { VisionApi } from '../../api/vision-api';
import { PollScheduler } from '../../poll-scheduler';
import { isVisualGeoDisabledError } from '../geo-logic';
import { GeoApiActions, GeoLiveActions, GeoPageActions } from './geo.actions';

/** How often the tracked asset's latest correction is re-read while polling (the fallback) is active — matches `GeoStore`'s own cadence. */
const POLL_INTERVAL_MS = 2_000;

/** Wraps `PollScheduler.schedule` as a cold `Observable` tick source — see `seat.effects.ts#ticks$`'s identical helper (this file can't import that one: it's private to its own module, and the two schedulers must stay independent per host anyway). */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/**
 * Polls `GET /api/geo/corrections/live` for whichever host is currently in `'poll'` transport, and
 * does nothing at all while a host is `'live'` — the poll-vs-live decision itself is **state**
 * (`GeoState.byHostId[hostId].transport`, written by `GeoLiveActions.transportResolved`), read here
 * purely through the action stream, never by injecting `LiveFacade` — the demand-gated-store rule
 * this wave was warned about (`GeoFacade`'s own constructor `effect()` is what watches
 * `LiveFacade.connectionState()` and dispatches `transportResolved`; this effect only ever reacts to
 * *this slice's own* actions).
 *
 * Three nested levels, each solving a distinct isolation problem:
 * - `groupBy(hostId)` + outer `mergeMap` — two hosts (today only `CockpitPage`, but see
 *   `geo.model.ts`'s own doc comment) can never cancel each other's session, mirroring
 *   `weather.effects.ts#track$`.
 * - `switchMap` over `Track Requested` — a *new* `track()` call for the same host (a different
 *   asset) cleanly supersedes the previous session, replacing `GeoStore`'s own manual `generation`
 *   counter: RxJS already guarantees the old session's poll/transport-watch is torn down before the
 *   new one starts.
 * - The innermost `switchMap` over the resolved transport — flips between the poll pipeline and
 *   `EMPTY` (no-op) as `transportResolved` arrives, so a mid-session live→poll→live flip starts and
 *   stops the actual timer without restarting the outer session or losing the D9 `disabled` latch
 *   (that lives in state, untouched by a transport flip).
 *
 * `takeUntil(Reset Requested)` is scoped to *this one* `Track Requested` emission's own pipeline
 * (not the whole group) — mirrors `seat.effects.ts#poll$`'s identical placement — so a later
 * `track()` on the same host after a `reset()` still flows through the still-open group.
 */
export const track$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    actions$.pipe(
      ofType(GeoPageActions.trackRequested),
      groupBy((action) => action.hostId, {
        duration: (group$) =>
          actions$.pipe(ofType(GeoPageActions.hostReleased), filter((released) => released.hostId === group$.key)),
      }),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ hostId, assetId, initialTransport }) =>
            actions$.pipe(
              ofType(GeoLiveActions.transportResolved),
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
                actions$.pipe(ofType(GeoPageActions.resetRequested), filter((reset) => reset.hostId === hostId)),
              ),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** Fetches the fleet-wide "latest per asset" list and filters it down to `assetId` client-side — `GeoStore`'s own §3.3 workaround (no single-asset "latest" route). */
function pollOnce(api: VisionApi, hostId: string, assetId: string) {
  return from(api.liveGeoCorrections()).pipe(
    map((response) =>
      GeoApiActions.pollSucceeded({ hostId, correction: response.corrections.find((c) => c.assetId === assetId) }),
    ),
    catchError((error: unknown) => {
      if (isVisualGeoDisabledError(error)) {
        return of(GeoApiActions.pollDisabled({ hostId }));
      }
      return EMPTY; // Silent-degrade otherwise: a missed poll just leaves state (and the chip) as it was.
    }),
  );
}

export const geoEffects = { track$ };
