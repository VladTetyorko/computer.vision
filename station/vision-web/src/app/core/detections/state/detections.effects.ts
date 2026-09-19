import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { EMPTY, Observable, catchError, concat, distinctUntilChanged, exhaustMap, filter, from, groupBy, map, merge, mergeMap, of, switchMap, takeUntil } from 'rxjs';
import { VisionApi } from '../../api/vision-api';
import { LivePageActions, LiveSocketActions } from '../../live/state/live.actions';
import { PollScheduler } from '../../poll-scheduler';
import { DetectionsApiActions, DetectionsPageActions } from './detections.actions';
import { DETECTIONS_LIMIT, DETECTIONS_POLL_INTERVAL_MS, TRACKS_POLL_INTERVAL_MS } from './detections.model';
import { detectionsFeature, feedTransportSelectorFor, tracksTransportSelectorFor } from './detections.reducer';

/** Wraps `PollScheduler.schedule` as a cold `Observable` tick source — duplicated per-file rather
 *  than shared, per NGRX-MIGRATION-PLAN.md §8's own "keeps wave file scopes disjoint" precedent
 *  (`seat.effects.ts`/`telemetry.effects.ts` carry the identical helper). */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/**
 * The detections-feed live half of one `track()` session, as an RxJS resource: subscribing
 * dispatches both `detectionsTracked` and `worldObjectsTracked` (ref-counted; `DetectionsStore#track`
 * opts into both together), unsubscribing dispatches both matching untracks. Dispatching actions
 * here — never injecting `LiveFacade` — keeps this file clear of the `EffectsRootModule` boot-order
 * hazard NGRX-MIGRATION-PLAN.md §9 documents (wave N3): see `telemetry.effects.ts#liveTelemetrySession`'s
 * identical doc comment for the full reasoning.
 */
function liveDetectionsSession(store: Store, assetId: string | undefined): Observable<never> {
  if (assetId === undefined) {
    return EMPTY;
  }
  return new Observable<never>(() => {
    store.dispatch(LivePageActions.detectionsTracked({ assetId }));
    store.dispatch(LivePageActions.worldObjectsTracked({ assetId }));
    return () => {
      store.dispatch(LivePageActions.detectionsUntracked({ assetId }));
      store.dispatch(LivePageActions.worldObjectsUntracked({ assetId }));
    };
  });
}

function pollDetections(api: VisionApi, streamId: string): Observable<Action> {
  return from(api.streamDetections(streamId, DETECTIONS_LIMIT)).pipe(
    map((results) => DetectionsApiActions.pollReceived({ streamId, results })),
    catchError(() => of(DetectionsApiActions.pollFailed({ streamId }))),
  );
}

/**
 * The feed's transport-reactive half: dispatches `Transport Entered` on every distinct decision
 * (including the very first, right after `Tracked` — unlike `telemetry.effects.ts`'s poll gate,
 * there is no backfill step to sequence after here, since `DetectionsStore#track` never awaited
 * anything before its own first `applyTransport` call), then — only while `'poll'` — polls
 * immediately and on a fixed cadence. Always-immediate, no "skip first" (unlike telemetry): mirrors
 * `DetectionsStore#applyTransport`'s own unconditional `void this.pollOnce(...)` on every entry into
 * poll, including the very first `track()` call.
 */
function detectionsFeedGate(store: Store, api: VisionApi, scheduler: PollScheduler, streamId: string): Observable<Action> {
  return store.select(feedTransportSelectorFor(streamId)).pipe(
    distinctUntilChanged(),
    switchMap((transport) => {
      const entered = of(DetectionsApiActions.transportEntered({ streamId, transport }));
      if (transport === 'live') {
        return entered;
      }
      return concat(
        entered,
        merge(of(undefined), ticks$(scheduler, DETECTIONS_POLL_INTERVAL_MS)).pipe(exhaustMap(() => pollDetections(api, streamId))),
      );
    }),
  );
}

/**
 * One `track()` session end to end, grouped by `streamId` so two streams' feeds never affect each
 * other. No async lookup to resolve first (unlike `telemetry.effects.ts#session$`) — the reducer has
 * already applied `Tracked` (setting `assetId`) by the time this effect observes the same action
 * (NgRx runs every reducer synchronously before any effect sees it — `dispatch-bridge.ts`'s own
 * documented guarantee), so the transport selector's very first read already sees it.
 */
export const feedSession$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    actions$.pipe(
      ofType(DetectionsPageActions.tracked),
      groupBy((action) => action.streamId),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ streamId, assetId }) => merge(liveDetectionsSession(store, assetId), detectionsFeedGate(store, api, scheduler, streamId))),
          takeUntil(actions$.pipe(ofType(DetectionsPageActions.reset), filter((r) => r.streamId === group$.key))),
        ),
      ),
    ),
  { functional: true },
);

/**
 * Accumulates every live `detections:<assetId>` arrival onto whichever stream(s) currently have an
 * active feed for that asset — `DetectionsStore`'s own constructor effect did this unconditionally,
 * "regardless of `transportSignal`'s current value", so a later poll→live flip has continuity
 * immediately rather than starting from empty (`detections.model.ts#DetectionsSessionState
 * .liveResults`'s own doc comment). Reacts to the `live` slice's own action directly — a slice
 * reacting to a foreign slice's *action* (its public contract) is the ordinary NgRx idiom for an
 * independent slice observing a shared event, distinct from the boot-order hazard convention 9
 * guards against (injecting a cross-slice *facade* into an effect factory).
 */
export const liveResultAccumulator$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(LiveSocketActions.envelopeReceived),
      filter((a): a is ReturnType<typeof LiveSocketActions.envelopeReceived> & { envelope: { type: 'detections' } } => a.envelope.type === 'detections'),
      concatLatestFrom(() => store.select(detectionsFeature.selectByStreamId)),
      mergeMap(([{ envelope }, byStreamId]) => {
        const actions = Object.entries(byStreamId)
          .filter(([, entry]) => entry?.feedActive && entry.assetId === envelope.assetId)
          .map(([streamId]) => DetectionsApiActions.liveResultReceived({ streamId, result: envelope.payload }));
        return from(actions);
      }),
    ),
  { functional: true },
);

function pollTracks(api: VisionApi, streamId: string): Observable<Action> {
  return from(api.getStreamTracks(streamId)).pipe(
    map((response) => DetectionsApiActions.tracksPollReceived({ streamId, response })),
    catchError(() => of(DetectionsApiActions.tracksPollFailed({ streamId }))),
  );
}

/**
 * The tracks lifecycle's poll fallback only — **never** touches the live subscription itself, which
 * is ref-counted entirely by the detections-feed lifecycle above (`DetectionsStore#applyTracksTransport`'s
 * own doc comment: "Never touches the live subscription itself"). Always-immediate on entering poll,
 * matching `applyTracksTransport`'s own unconditional `void this.pollTracksOnce(...)`.
 */
export const tracksSession$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    actions$.pipe(
      ofType(DetectionsPageActions.tracksTracked),
      groupBy((action) => action.streamId),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ streamId }) =>
            store.select(tracksTransportSelectorFor(streamId)).pipe(
              distinctUntilChanged(),
              switchMap((transport) => {
                if (transport === 'live') {
                  return EMPTY;
                }
                return merge(of(undefined), ticks$(scheduler, TRACKS_POLL_INTERVAL_MS)).pipe(exhaustMap(() => pollTracks(api, streamId)));
              }),
            ),
          ),
          takeUntil(actions$.pipe(ofType(DetectionsPageActions.tracksUntracked), filter((r) => r.streamId === group$.key))),
        ),
      ),
    ),
  { functional: true },
);

export const detectionsEffects = { feedSession$, liveResultAccumulator$, tracksSession$ };
