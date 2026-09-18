import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { EMPTY, type Observable, catchError, concat, concatMap, from, map, merge, of, switchMap, timer } from 'rxjs';
import { VisionApi } from '../../api/vision-api';
import { SSE_RETRY_INTERVAL_MS, buildTopicsParam } from '../live-fallback-logic';
import { LiveGateway, type LiveGatewayEvent } from '../live-gateway';
import { LiveApiActions, LivePageActions, LiveSocketActions } from './live.actions';
import { liveFeature, topicFor } from './live.reducer';
import type { LiveTopicFamily } from './live.model';

/** Console prefix mirroring the old `LiveStore`'s own `[live]` tag. */
const LOG_PREFIX = '[live]';

function toAction(event: LiveGatewayEvent): Action {
  switch (event.kind) {
    case 'open':
      return LiveSocketActions.opened();
    case 'connected':
      return LiveSocketActions.handshakeReceived({ connectionId: event.connectionId, topics: event.topics });
    case 'message':
      return LiveSocketActions.envelopeReceived({ envelope: event.envelope });
    case 'retrying':
      return LiveSocketActions.retrying();
    case 'fatal':
      return LiveSocketActions.closed();
  }
}

/**
 * One full attempt at `GET /api/live`, rebuilding the `topics` query parameter fresh from the
 * store's *current* ref-counts every time it runs — mirrors the old `LiveStore#connect`'s own
 * "Reconnect topic restoration" doc comment verbatim: a brand-new connection carries no memory of
 * what the previous one was subscribed to, so this always re-derives the full list from
 * `topicRefs`, not from whatever the last attempt happened to open with.
 *
 * Recurses through `LiveGateway`'s own `'fatal'` → complete signal via {@link SSE_RETRY_INTERVAL_MS}
 * — exactly the old class's manual-retry cadence for a connection the browser itself gave up on.
 * The recursion is asynchronous at every step (gated behind `timer`), so this never grows an actual
 * call stack — each retry is a fresh subscription, not a synchronous loop.
 *
 * **Known, accepted gap** (unchanged from `LiveStore`, not fixed here): a topic tracked/untracked
 * during the few seconds of a *native* browser auto-retry (a `'retrying'` event, not `'fatal'`) is
 * not reflected until the *next* full reconnect — this function only ever rebuilds the topic list
 * when it (re)subscribes to `gateway.open(...)`, and a native auto-retry reuses the same
 * `EventSource`/URL without this function running again. A topic change while genuinely `'closed'`
 * (this function's own retry path) *is* always picked up, since every retry calls this function
 * fresh.
 */
function connectWithRetry(gateway: LiveGateway, store: Store): Observable<Action> {
  const topics = buildTopicsParam(Object.keys(store.selectSignal(liveFeature.selectTopicRefs)()));
  return gateway.open(topics).pipe(
    map(toAction),
    concatMap((action) =>
      action.type === LiveSocketActions.closed.type
        ? concat(
            of(action),
            timer(SSE_RETRY_INTERVAL_MS).pipe(switchMap(() => connectWithRetry(gateway, store))),
          )
        : of(action),
    ),
  );
}

/**
 * The connection lifecycle (docs/plans/active/NGRX-MIGRATION-PLAN.md §8) — every `LiveGateway` event
 * translated to a plain action. `switchMap` over the merged trigger stream is what gives
 * `reconnect()`/`stop()` their original "teardown, then maybe reopen" behaviour for free: a fresh
 * trigger unsubscribes whatever attempt (or pending retry timer) was previously running, which is
 * exactly `LiveGateway.open`'s own teardown closing the underlying `EventSource` — matching
 * `LiveStore#teardown()` being the first thing both `reconnect()` and `stop()` used to do.
 *
 * `Stop Requested` resolves to `EMPTY`: the reducer already moved `connectionState` to `'closed'`
 * synchronously (see `live.reducer.ts`), so there is nothing left to dispatch — only the `switchMap`
 * cancellation itself matters here, same as `LiveFacade`'s constructor dispatching
 * `Reconnect Requested` once at boot is what used to be `LiveStore`'s own constructor calling
 * `connect()` immediately (mirrors `AuthFacade`'s own constructor-dispatch precedent).
 */
export const connection$ = createEffect(
  (actions$ = inject(Actions), gateway = inject(LiveGateway), store = inject(Store)) =>
    merge(
      actions$.pipe(ofType(LivePageActions.reconnectRequested), map(() => 'connect' as const)),
      actions$.pipe(ofType(LivePageActions.stopRequested), map(() => 'stop' as const)),
    ).pipe(
      switchMap((command) => {
        if (command === 'stop') {
          return EMPTY;
        }
        if (!gateway.isAvailable()) {
          console.info(`${LOG_PREFIX} EventSource unavailable in this environment — staying on polling`);
          return of(LiveSocketActions.closed());
        }
        return connectWithRetry(gateway, store);
      }),
    ),
  { functional: true },
);

/** Maps a page action's own type string back to the family it tracks — used only to look up the
 *  matching topic string, never selected on directly. */
const TRACK_FAMILY: Readonly<Record<string, LiveTopicFamily>> = {
  [LivePageActions.telemetryTracked.type]: 'telemetry',
  [LivePageActions.detectionsTracked.type]: 'detections',
  [LivePageActions.geoTracked.type]: 'geo',
  [LivePageActions.worldObjectsTracked.type]: 'worldObjects',
  [LivePageActions.cvTraceTracked.type]: 'cvTrace',
  [LivePageActions.linksTracked.type]: 'links',
};

const UNTRACK_FAMILY: Readonly<Record<string, LiveTopicFamily>> = {
  [LivePageActions.telemetryUntracked.type]: 'telemetry',
  [LivePageActions.detectionsUntracked.type]: 'detections',
  [LivePageActions.geoUntracked.type]: 'geo',
  [LivePageActions.worldObjectsUntracked.type]: 'worldObjects',
  [LivePageActions.cvTraceUntracked.type]: 'cvTrace',
  [LivePageActions.linksUntracked.type]: 'links',
};

/** `PATCH /api/live/{connectionId}/topics`, or nothing at all on success — mirrors
 *  `LiveStore#patchTopics`'s own fire-and-forget shape exactly (see `LiveApiActions.topicsPatchFailed`'s
 *  own doc comment for why a failure is silent-but-modeled). */
function patchTopics(
  api: VisionApi,
  connectionId: string,
  request: { add: readonly string[]; remove: readonly string[] },
): Observable<Action> {
  return from(api.updateLiveTopics(connectionId, request)).pipe(
    concatMap(() => EMPTY),
    catchError((error: unknown) => {
      console.warn(`${LOG_PREFIX} PATCH topics failed`, { error });
      return of(LiveApiActions.topicsPatchFailed({ topics: [...request.add, ...request.remove] }));
    }),
  );
}

/**
 * Ref-counted opt-in (docs/plans/done/REALTIME-PLAN.md §4, item 2) — PATCHes `add` only on a topic's
 * *first* subscriber (reads `topicRefs[topic] === 1` off the **post-reducer** state, since NgRx runs
 * the reducer for an action synchronously before any effect observes it — see
 * `core/state/dispatch-bridge.ts`'s own doc comment for that ordering guarantee — so a freshly
 * incremented count of exactly `1` can only mean this action was the transition from absent to
 * tracked), and only while the connection is actually open: an unopened/reconnecting connection's
 * *next* attempt already rebuilds its topic list fresh from `topicRefs` (`connectWithRetry`'s own doc
 * comment), so there is nothing to PATCH onto yet.
 */
export const patchOnTrack$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi), store = inject(Store)) =>
    actions$.pipe(
      ofType(
        LivePageActions.telemetryTracked,
        LivePageActions.detectionsTracked,
        LivePageActions.geoTracked,
        LivePageActions.worldObjectsTracked,
        LivePageActions.cvTraceTracked,
        LivePageActions.linksTracked,
      ),
      concatLatestFrom(() => store.select(liveFeature.selectLiveState)),
      concatMap(([action, state]) => {
        const topic = topicFor(TRACK_FAMILY[action.type], action.assetId);
        const isFirstSubscriber = state.topicRefs[topic] === 1;
        if (!isFirstSubscriber || state.connectionId === undefined || state.connectionState !== 'open') {
          return EMPTY;
        }
        console.info(`${LOG_PREFIX} track ${topic}`);
        return patchTopics(api, state.connectionId, { add: [topic], remove: [] });
      }),
    ),
  { functional: true },
);

/** The matching teardown for {@link patchOnTrack$} — PATCHes `remove` only on a topic's *last*
 *  unsubscribe (the topic key is now absent from the post-reducer `topicRefs` entirely). */
export const patchOnUntrack$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi), store = inject(Store)) =>
    actions$.pipe(
      ofType(
        LivePageActions.telemetryUntracked,
        LivePageActions.detectionsUntracked,
        LivePageActions.geoUntracked,
        LivePageActions.worldObjectsUntracked,
        LivePageActions.cvTraceUntracked,
        LivePageActions.linksUntracked,
      ),
      concatLatestFrom(() => store.select(liveFeature.selectLiveState)),
      concatMap(([action, state]) => {
        const topic = topicFor(UNTRACK_FAMILY[action.type], action.assetId);
        const isLastSubscriber = state.topicRefs[topic] === undefined;
        if (!isLastSubscriber || state.connectionId === undefined || state.connectionState !== 'open') {
          return EMPTY;
        }
        console.info(`${LOG_PREFIX} untrack ${topic}`);
        return patchTopics(api, state.connectionId, { add: [], remove: [topic] });
      }),
    ),
  { functional: true },
);

export const liveEffects = { connection$, patchOnTrack$, patchOnUntrack$ };
