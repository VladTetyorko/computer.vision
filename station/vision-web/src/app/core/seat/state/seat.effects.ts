import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { Observable, catchError, distinctUntilChanged, exhaustMap, filter, from, groupBy, map, merge, mergeMap, of, switchMap, takeUntil } from 'rxjs';
import type { SeatKind, SeatsResponse } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { PollScheduler } from '../../poll-scheduler';
import { renewalIntervalMs } from '../seat-logic';
import { SeatApiActions, SeatPageActions } from './seat.actions';
import { SEAT_POLL_INTERVAL_MS } from './seat.model';
import { seatFeature } from './seat.reducer';

/** Wraps `PollScheduler.schedule` (an imperative, callback-based API) as a cold `Observable` tick
 *  source, so the rest of this file can stay declarative RxJS — unsubscribing calls the scheduler's
 *  own `stop` function, exactly where a `DestroyRef.onDestroy`/`teardown()` call used to go. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/**
 * Polls `GET /api/assets/{id}/seats` for every distinct tracked `assetId` — an immediate first read
 * (mirrors `track()`'s own `void this.pollOnce(...)`) plus a fixed 3s cadence afterward.
 *
 * `groupBy(assetId)` + outer `mergeMap` is what guarantees two hosts tracking two different assets
 * never affect each other's polling: each asset gets its own independent, concurrently-running
 * group, so asset B's `Tracked` action can never cancel asset A's in-flight ticks (a plain top-level
 * `switchMap` would have let a second host's `track()` silently kill the first host's poll). Inside
 * one asset's group, `switchMap` still restarts cleanly if that same asset is tracked again, and
 * `exhaustMap` reproduces `PollScheduler`'s own in-flight guard for the actual HTTP call.
 */
export const poll$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    actions$.pipe(
      ofType(SeatPageActions.tracked),
      groupBy((action) => action.assetId),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ assetId }) =>
            merge(of(undefined), ticks$(scheduler, SEAT_POLL_INTERVAL_MS)).pipe(
              exhaustMap(() => readSeats(api, assetId)),
              takeUntil(actions$.pipe(ofType(SeatPageActions.reset), filter((r) => r.assetId === assetId))),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** Forces one immediate re-read outside the poll cadence (used after a guarded write 409s) —
 *  independent of {@link poll$}'s own ticking, exactly like `refreshNow()` never disturbed the
 *  running poll/renew tasks. */
export const refreshNow$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(SeatPageActions.refreshNowRequested),
      exhaustMap(({ assetId }) => readSeats(api, assetId)),
    ),
  { functional: true },
);

function readSeats(api: VisionApi, assetId: string) {
  return from(api.getAssetSeats(assetId)).pipe(
    map((response) => SeatApiActions.seatsReceived({ assetId, response })),
    catchError(() => of(SeatApiActions.readFailed({ assetId }))),
  );
}

/**
 * Renews every seat last known to be mine, on a cadence derived from the server-served `ttlMs`
 * (never hard-coded) — re-registers only when that period actually changes, mirroring
 * `applyRenewalCadence`'s own guard. Dispatches manually (`dispatch: false`) because one tick can
 * dispatch zero, one, or two `Seats Received`/`Read Failed` actions in sequence — a chain
 * `createEffect`'s normal "return the next action" shape can't express directly.
 */
export const renew$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi), scheduler = inject(PollScheduler), store = inject(Store)) =>
    actions$.pipe(
      ofType(SeatApiActions.seatsReceived),
      map(({ assetId, response }) => ({ assetId, period: renewalIntervalMs(response.ttlMs) })),
      groupBy((x) => x.assetId),
      mergeMap((group$) =>
        group$.pipe(
          distinctUntilChanged((a, b) => a.period === b.period),
          switchMap(({ assetId, period }) =>
            ticks$(scheduler, period).pipe(
              exhaustMap(() => from(renewMineSeats(api, store, assetId))),
              takeUntil(actions$.pipe(ofType(SeatPageActions.reset), filter((r) => r.assetId === assetId))),
            ),
          ),
        ),
      ),
    ),
  { functional: true, dispatch: false },
);

/**
 * Renews, in sequence, every seat the *last-known* state (read once, at the top — a mid-loop
 * preemption on one kind never re-derives which kinds still count as mine for the rest of this same
 * tick) says is mine. A renewal failure is treated as an authoritative correction, not an error to
 * retry past — mirrors `SeatStore#renewOne`'s own doc comment: re-read `GET .../seats` immediately
 * rather than reconciling the rejection's body into local state.
 */
async function renewMineSeats(api: VisionApi, store: Store, assetId: string): Promise<void> {
  const current: SeatsResponse | undefined = store.selectSignal(seatFeature.selectByAssetId)()[assetId];
  if (current === undefined) {
    return;
  }
  const mineKinds: SeatKind[] = [];
  if (current.flight.mine) {
    mineKinds.push('FLIGHT');
  }
  if (current.camera.mine) {
    mineKinds.push('CAMERA');
  }
  for (const kind of mineKinds) {
    try {
      const renewed = await api.takeAssetSeat(assetId, kind);
      store.dispatch(SeatApiActions.seatsReceived({ assetId, response: renewed }));
    } catch {
      try {
        const response = await api.getAssetSeats(assetId);
        store.dispatch(SeatApiActions.seatsReceived({ assetId, response }));
      } catch {
        store.dispatch(SeatApiActions.readFailed({ assetId }));
      }
    }
  }
}

export const seatEffects = { poll$, refreshNow$, renew$ };
