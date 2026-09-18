import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { EMPTY, Observable, catchError, exhaustMap, filter, from, groupBy, map, merge, mergeMap, of, switchMap, takeUntil } from 'rxjs';
import type { CvTrace } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LivePageActions, LiveSocketActions } from '../../live/state/live.actions';
import { PollScheduler } from '../../poll-scheduler';
import { CvTraceApiActions, CvTracePageActions } from './cv-trace.actions';
import { CV_TRACE_POLL_INTERVAL_MS } from './cv-trace.model';
import { cvTraceFeature } from './cv-trace.reducer';

/** Wraps `PollScheduler.schedule` as a cold `Observable` tick source — duplicated per-file, per
 *  NGRX-MIGRATION-PLAN.md §8's own "keeps wave file scopes disjoint" precedent (`seat.effects.ts`/
 *  `telemetry.effects.ts`/`detections.effects.ts` carry the identical helper). */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/**
 * The live half of one `track()` session, as an RxJS resource — see `telemetry.effects.ts
 * #liveTelemetrySession`'s identical doc comment for why dispatching here (never injecting
 * `LiveFacade`) keeps this file clear of the `EffectsRootModule` boot-order hazard.
 *
 * **`EMPTY` when no `assetId` is given** — mirrors `CvTraceStore.track`'s own "stays poll-only when
 * no assetId is given" branch (that class's own doc comment): the poll below is trace demand's only
 * source in that case.
 */
function liveCvTraceSession(store: Store, assetId: string | undefined): Observable<never> {
  if (assetId === undefined) {
    return EMPTY;
  }
  return new Observable<never>(() => {
    store.dispatch(LivePageActions.cvTraceTracked({ assetId }));
    return () => store.dispatch(LivePageActions.cvTraceUntracked({ assetId }));
  });
}

function pollCvTrace(api: VisionApi, streamId: string, last: number): Observable<Action> {
  return from(api.getCvTrace(streamId, last)).pipe(
    map((trace: CvTrace) => CvTraceApiActions.pollReceived({ streamId, trace })),
    catchError(() => of(CvTraceApiActions.pollFailed({ streamId }))),
  );
}

/**
 * The poll half: **always runs, immediately and on a fixed cadence, for the whole session** — no
 * poll-vs-live transport gating at all (`cv-trace.model.ts`'s own class doc: `gate`/`world` have no
 * live topic, so this poll is their only freshness source regardless of whether `frame` is also
 * arriving live between ticks). Unlike `telemetry.effects.ts`/`detections.effects.ts`, there is no
 * `feedTransportSelectorFor`/`AssetScopedTransport` for this slice — this is the plan's own
 * poll-vs-live convention (NGRX-MIGRATION-PLAN.md §3) not applying here, called out explicitly rather
 * than forced to fit.
 */
export const session$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    actions$.pipe(
      ofType(CvTracePageActions.tracked),
      groupBy((action) => action.streamId),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ streamId, assetId, last }) =>
            merge(
              liveCvTraceSession(store, assetId),
              merge(of(undefined), ticks$(scheduler, CV_TRACE_POLL_INTERVAL_MS)).pipe(exhaustMap(() => pollCvTrace(api, streamId, last))),
            ),
          ),
          takeUntil(actions$.pipe(ofType(CvTracePageActions.reset), filter((r) => r.streamId === group$.key))),
        ),
      ),
    ),
  { functional: true },
);

/**
 * Merges every live `cv-trace:<assetId>` arrival into whichever stream(s) currently track that asset
 * — the low-latency half of `cv-trace.model.ts#CvTraceSessionState.frame`'s own doc comment. Reacts
 * to the live slice's own action directly, same idiom as `detections.effects.ts
 * #liveResultAccumulator$`'s own doc comment explains.
 */
export const liveFrameAccumulator$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(LiveSocketActions.envelopeReceived),
      filter((a): a is ReturnType<typeof LiveSocketActions.envelopeReceived> & { envelope: { type: 'cv-trace' } } => a.envelope.type === 'cv-trace'),
      concatLatestFrom(() => store.select(cvTraceFeature.selectByStreamId)),
      mergeMap(([{ envelope }, byStreamId]) => {
        const actions = Object.entries(byStreamId)
          .filter(([, entry]) => entry?.assetId === envelope.assetId)
          .map(([streamId]) => CvTraceApiActions.liveFrameReceived({ streamId, frame: envelope.payload }));
        return from(actions);
      }),
    ),
  { functional: true },
);

export const cvTraceEffects = { session$, liveFrameAccumulator$ };
