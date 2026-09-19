import { inject } from '@angular/core';
import { createEffect } from '@ngrx/effects';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { EMPTY, Observable, catchError, combineLatest, concat, distinctUntilChanged, exhaustMap, from, map, of, switchMap } from 'rxjs';
import { VisionApi } from '../../api/vision-api';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { TracksApiActions } from './tracks.actions';
import { TRACKS_POLL_INTERVAL_MS } from './tracks.model';
import { tracksFeature } from './tracks.reducer';

/** Wraps `PollScheduler.schedule` as a cold tick `Observable` — copied per effects file, matching
 *  `seat.effects.ts#ticks$`'s own established precedent (near-identical helpers stay disjoint per
 *  file rather than shared, so concurrent waves never collide on one utility module). */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/** `activeConsumers === 0` runs nothing; `>0` with live open reconciles once and stops there; `>0`
 *  with live closed reconciles once, then polls every {@link TRACKS_POLL_INTERVAL_MS}. */
type TransportMode = 'idle' | 'live' | 'poll';

/**
 * The demand/live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1,
 * ALWAYS-ON-FLOW-PLAN.md §4 Wave C3), re-expressed as a continuous mode derivation instead of the
 * old `TracksStore#applyTransport`'s hand-rolled `liveGated`/`stopPollFn` bookkeeping. Runs from
 * `ROOT_EFFECTS_INIT` (no `actions$` gate) and reacts purely to two selected values — the
 * `activeConsumers` ref-count `tracks.reducer.ts` maintains, and `LiveFacade`'s connection state via
 * the `live` slice directly (NGRX-MIGRATION-PLAN.md §9 — read `live` through its selectors, never by
 * injecting `LiveFacade` into an effect).
 *
 * The frozen D1 table falls out of `distinctUntilChanged` + the outer `switchMap` alone:
 *  - a transition **into** `'live'` or `'poll'` always fires exactly one reconcile GET (the mode
 *    changed, so `switchMap` unsubscribes whatever ran before and (re)enters);
 *  - staying in the same mode — a second concurrent `activate()` (the count changes but stays `>0`),
 *    or `connectionState` re-firing an equal value — never re-triggers, since `distinctUntilChanged`
 *    suppresses it before `switchMap` ever sees a "new" mode;
 *  - `'poll'`'s own ticks are never disturbed by such a repeat, since the whole `concat(...)`
 *    pipeline for that mode stays subscribed;
 *  - dropping to zero consumers moves to `'idle'` (`EMPTY`, stopping whatever ran), and there is
 *    nothing to separately "forget" — the *next* transition out of `'idle'` is unconditionally a
 *    fresh entry, which is exactly what re-fetching on reactivation requires.
 */
export const gate$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) => {
    const hasDemand$ = store.select(tracksFeature.selectActiveConsumers).pipe(
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
          return fetchTracks(api);
        }
        return concat(fetchTracks(api), ticks$(scheduler, TRACKS_POLL_INTERVAL_MS).pipe(exhaustMap(() => fetchTracks(api))));
      }),
    );
  },
  { functional: true },
);

function fetchTracks(api: VisionApi) {
  return from(api.listMapTracks()).pipe(
    map((response) => TracksApiActions.loaded({ tracks: response.tracks })),
    catchError(() => of(TracksApiActions.loadFailed())),
  );
}

export const tracksEffects = { gate$ };
