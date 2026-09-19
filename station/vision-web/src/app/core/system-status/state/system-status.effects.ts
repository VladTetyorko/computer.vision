import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { EMPTY, Observable, catchError, concat, distinctUntilChanged, exhaustMap, from, map, of, switchMap } from 'rxjs';
import { describeHttpError } from '../../api-error';
import { VisionApi } from '../../api/vision-api';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { SystemStatusApiActions, SystemStatusPageActions } from './system-status.actions';
import { SYSTEM_STATUS_POLL_INTERVAL_MS } from './system-status.model';

/** Wraps `PollScheduler.schedule` as a cold tick `Observable` — copied per effects file, matching
 *  `fleet.effects.ts#ticks$`/`layers.effects.ts#ticks$`'s own established precedent. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/** The one shared `GET /api/system/status` round trip — `SystemStatusStore.refresh()`'s own
 *  try/catch, reused by every trigger (poll tick, live→poll reconcile, and the explicit
 *  `Refresh Requested` a page's own mount can still issue). Never toasted — see
 *  `system-status.actions.ts`'s own doc comment. */
function refreshStatus$(api: VisionApi): Observable<Action> {
  return from(api.systemStatus()).pipe(
    map((status) => SystemStatusApiActions.refreshSucceeded({ status })),
    catchError((error: unknown) => of(SystemStatusApiActions.refreshFailed({ error: describeHttpError(error) }))),
  );
}

/**
 * The status poll-vs-live gate — **live axis only, no `activeConsumers` term** (docs/plans/active/
 * LIVE-POLL-RETIREMENT-PLAN.md §3 D1/§4.2, wave L5): `shared/ui/app-sidebar/app-sidebar.ts`'s shell
 * rollup dot needs `overall` on every page, not just while `/manage/system` happens to be open, so
 * there is no "nobody needs this" state to ref-count — identical posture, and identical reasoning,
 * to `fleet.effects.ts#gate$` (that file's own doc comment cross-references this one).
 *
 * Subscribing to `store.select(liveFeature.selectConnectionState)` replays the current value
 * immediately, which is what gives this effect its one-`GET`-at-boot behaviour for free — see
 * `fleet.effects.ts#gate$`'s own doc comment for why that supersedes literally replaying
 * `SystemStatusStore`'s old constructor order. Unlike `fleet`'s gate, there is no `quiet` distinction
 * to track (this store has never toasted, ever), so no `firstEntry` bookkeeping is needed either.
 */
export const gate$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    store.select(liveFeature.selectConnectionState).pipe(
      map(isLiveAvailable),
      distinctUntilChanged(),
      switchMap((liveAvailable): Observable<Action> => {
        if (liveAvailable) {
          // No reconcile fetch here — the `system` topic (folded directly by
          // `system-status.reducer.ts`) already delivers a fresh sample on connect.
          return EMPTY;
        }
        return concat(refreshStatus$(api), ticks$(scheduler, SYSTEM_STATUS_POLL_INTERVAL_MS).pipe(exhaustMap(() => refreshStatus$(api))));
      }),
    ),
  { functional: true },
);

/** The explicit, non-automatic path — `features/system-status/system-status-facade.ts`'s own
 *  constructor call and its `refresh()` passthrough. */
export const manualRefresh$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(SystemStatusPageActions.refreshRequested),
      switchMap(() => refreshStatus$(api)),
    ),
  { functional: true },
);

export const systemStatusEffects = {
  gate$,
  manualRefresh$,
};
