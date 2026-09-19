import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, filter, from, groupBy, map, mergeMap, of, switchMap } from 'rxjs';
import type { GeoPosition } from '../../api/models';
import { openMeteoForecastUrl, parseOpenMeteoReading } from '../weather-logic';
import { WeatherApiActions, WeatherPageActions } from './weather.actions';

/** Console prefix mirroring `[player]`/`[fleet]`/`[fly]`/`[live]` — a stable per-file tag, no shared logging service. */
const LOG_PREFIX = '[weather]';

/**
 * `groupBy(hostId)` + outer `mergeMap` is what guarantees two hosts (Command's fleet-centroid chip,
 * Fly's flown-asset chip) never affect each other's fetch — exactly `seat.effects.ts#poll$`'s own
 * reasoning: a plain top-level `switchMap` would let host B's `track()` call cancel host A's
 * in-flight Open-Meteo request, the exact "one shared reading" collapse this wave was warned
 * against. Inside one host's own group, `switchMap` still cleanly supersedes an earlier in-flight
 * request for that same host (defense in depth — `WeatherFacade#track`'s own `inFlight` pre-check
 * already keeps two overlapping requests for one host from being dispatched in the first place).
 * `groupBy`'s own `duration` selector — not a downstream `takeUntil` — closes a host's group on a
 * matching `Host Released`: that's what actually releases `groupBy`'s internal per-key bookkeeping
 * (a `takeUntil` chained after `mergeMap` only stops *our* subscription from reacting further; it
 * never tells `groupBy` the key is free, so a released-then-reused `hostId` would silently stop
 * producing output — exactly the RxJS behaviour a real `WeatherFacade` never triggers, since its
 * `hostId` comes from a monotonically increasing counter that's never reused, but `duration` is the
 * textbook-correct primitive for "close this group when X happens" and is the only form under which a
 * host id genuinely can be reopened later).
 */
export const track$ = createEffect(
  (actions$ = inject(Actions)) =>
    actions$.pipe(
      ofType(WeatherPageActions.trackRequested),
      groupBy((action) => action.hostId, {
        duration: (group$) =>
          actions$.pipe(ofType(WeatherPageActions.hostReleased), filter((released) => released.hostId === group$.key)),
      }),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ position }) =>
            from(fetchReading(position)).pipe(
              map((reading) => WeatherApiActions.fetchSucceeded({ hostId: group$.key, reading })),
              catchError((error: unknown) => {
                console.warn(`${LOG_PREFIX} fetch failed — hiding the chip`, { error });
                return of(WeatherApiActions.fetchFailed({ hostId: group$.key }));
              }),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** Plain `fetch()`, not `HttpClient` — Open-Meteo is a third-party, no-key, CORS-open public API,
 *  not this app's own backend (see `weather.model.ts`'s own doc comment for the fuller rationale
 *  the old `WeatherStore` class doc carried). */
async function fetchReading(position: GeoPosition) {
  const response = await fetch(openMeteoForecastUrl(position));
  if (!response.ok) {
    throw new Error(`Open-Meteo responded ${response.status}`);
  }
  const payload = await response.json();
  const reading = parseOpenMeteoReading(payload, Date.now());
  if (!reading) {
    console.warn(`${LOG_PREFIX} unparseable forecast payload — hiding the chip`);
  }
  return reading;
}

export const weatherEffects = { track$ };
